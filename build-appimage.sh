#!/usr/bin/env bash
set -euo pipefail

APP_NAME="wren"
APP_DIR="Wren.AppDir"
OUTPUT="Wren.AppImage"
APPIMAGETOOL_URL="https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
APPIMAGETOOL="./appimagetool"

# 0. Resolve a JDK 21 for Gradle. The Kotlin toolchain is pinned to 21 and Gradle
#    can't auto-provision it, so look in mise first (our version manager), then JAVA_HOME.
#    Passing the path explicitly also makes the toolchain resolvable when PATH has a newer JDK.
resolve_jdk21() {
    local candidate
    if command -v mise &>/dev/null; then
        candidate=$(mise where java@21 2>/dev/null || true)
        [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && { echo "$candidate"; return; }
    fi
    for candidate in "${MISE_DATA_DIR:-$HOME/.local/share/mise}"/installs/java/*21*; do
        [ -x "$candidate/bin/java" ] && { readlink -f "$candidate"; return; }
    done
    if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
        echo "$JAVA_HOME"; return
    fi
    return 1
}

if ! JDK21=$(resolve_jdk21); then
    echo "ERROR: No JDK 21 found. Install one with: mise install java@21" >&2
    exit 1
fi
echo ">>> Using JDK 21 at $JDK21"
export JAVA_HOME="$JDK21"

# 1. Build distributable
echo ">>> Building distributable..."
# in-process: the Kotlin compile daemon chokes on 4-part JDK versions (e.g. Corretto 25.0.4.1)
./gradlew :desktop:createDistributable \
    -Porg.gradle.java.installations.paths="$JDK21" \
    -Pkotlin.compiler.execution.strategy=in-process

DIST_DIR="desktop/build/compose/binaries/main/app/${APP_NAME}"
if [ ! -d "$DIST_DIR" ]; then
    echo "ERROR: Expected distributable at $DIST_DIR — check the app name in build.gradle.kts"
    exit 1
fi

# 2. Download appimagetool if needed
if [ ! -f "$APPIMAGETOOL" ]; then
    echo ">>> Downloading appimagetool..."
    wget -q --show-progress -O "$APPIMAGETOOL" "$APPIMAGETOOL_URL"
    chmod +x "$APPIMAGETOOL"
fi

# 3. Prepare AppDir
echo ">>> Preparing AppDir..."
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR"
cp -r "$DIST_DIR"/. "$APP_DIR/"

# Bundle yt-dlp (required for stream URL resolution)
YT_DLP_BIN=$(which yt-dlp 2>/dev/null || echo "")
if [ -n "$YT_DLP_BIN" ]; then
    echo ">>> Bundling yt-dlp from $YT_DLP_BIN..."
    cp "$YT_DLP_BIN" "$APP_DIR/bin/yt-dlp"
    chmod +x "$APP_DIR/bin/yt-dlp"
else
    echo ">>> WARNING: yt-dlp not found in PATH — stream URL resolution will fail at runtime"
fi

# AppRun — entry point
cat > "$APP_DIR/AppRun" << 'EOF'
#!/bin/bash
HERE="$(dirname "$(readlink -f "$0")")"
export PATH="$HERE/bin:$PATH"
exec "$HERE/bin/wren" "$@"
EOF
chmod +x "$APP_DIR/AppRun"

# .desktop file
cat > "$APP_DIR/${APP_NAME}.desktop" << EOF
[Desktop Entry]
Type=Application
Name=Wren
Exec=wren
Icon=wren
Categories=AudioVideo;Music;
EOF

# Icon — use provided one or generate a placeholder
if [ -f "desktop/wren.png" ]; then
    cp desktop/wren.png "$APP_DIR/${APP_NAME}.png"
else
    echo ">>> No icon.png found, generating placeholder..."
    # Try to generate with ImageMagick, fall back to a minimal PNG
    if command -v convert &>/dev/null; then
        convert -size 256x256 xc:'#1a1a2e' \
            -fill white -pointsize 64 -gravity Center -annotate 0 "▶" \
            "$APP_DIR/${APP_NAME}.png"
    else
        # Minimal 1x1 transparent PNG (base64)
        echo "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==" \
            | base64 -d > "$APP_DIR/${APP_NAME}.png"
        echo "    (placeholder 1x1 PNG — replace icon.png next to this script for a real icon)"
    fi
fi

# 4. Package AppImage — extract appimagetool first when libfuse2 is missing
echo ">>> Packaging AppImage..."
if ! ldconfig -p 2>/dev/null | grep -q libfuse.so.2; then
    if [ ! -d "squashfs-root" ]; then
        echo ">>> libfuse2 not found — extracting appimagetool to run it without FUSE"
        "$APPIMAGETOOL" --appimage-extract >/dev/null
    fi
    APPIMAGETOOL="./squashfs-root/AppRun"
fi
# Write to a temp file and rename: overwriting in place fails with "Text file busy"
# while a previous build of the AppImage is still running.
ARCH=x86_64 "$APPIMAGETOOL" "$APP_DIR" "$OUTPUT.tmp"
mv -f "$OUTPUT.tmp" "$OUTPUT"

echo ""
echo "Done! Created: $OUTPUT"
echo "Run with: ./$OUTPUT"
