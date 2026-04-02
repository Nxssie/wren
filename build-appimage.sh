#!/usr/bin/env bash
set -euo pipefail

APP_NAME="wren"
APP_DIR="Wren.AppDir"
OUTPUT="Wren.AppImage"
APPIMAGETOOL_URL="https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
APPIMAGETOOL="./appimagetool"

# 1. Build distributable
echo ">>> Building distributable..."
./gradlew createDistributable

DIST_DIR="build/compose/binaries/main/app/${APP_NAME}"
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

# AppRun — entry point
cat > "$APP_DIR/AppRun" << 'EOF'
#!/bin/bash
HERE="$(dirname "$(readlink -f "$0")")"
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
if [ -f "icon.png" ]; then
    cp icon.png "$APP_DIR/${APP_NAME}.png"
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

# 4. Package AppImage
echo ">>> Packaging AppImage..."
ARCH=x86_64 "$APPIMAGETOOL" "$APP_DIR" "$OUTPUT"

echo ""
echo "Done! Created: $OUTPUT"
echo "Run with: ./$OUTPUT"
