#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-$(git describe --tags --always --dirty)}"
GITHUB_TOKEN="${2:-$GITHUB_TOKEN}"

if [ -z "$GITHUB_TOKEN" ]; then
    echo "Usage: $0 <version> [github_token]"
    echo "  e.g.: $0 v1.0.0"
    exit 1
fi

OWNER="Nxssie"
REPO="wren"

# 1. Build AppImage
echo ">>> Building AppImage..."
rm -f Wren.AppImage
./build-appimage.sh

# 2. Rename to convention: Wren-{version}-x86_64.AppImage
APPIMAGE_NAME="Wren-${VERSION}-x86_64.AppImage"
mv Wren.AppImage "$APPIMAGE_NAME"

# 3. Check if release already exists
echo ">>> Checking for existing release $VERSION..."
RELEASE_ID=$(gh api repos/$OWNER/$REPO/releases/tags/$VERSION --jq '.id' 2>/dev/null || echo "")

if [ -n "$RELEASE_ID" ]; then
    echo ">>> Release $VERSION already exists, uploading asset..."
    gh release upload "$VERSION" "$APPIMAGE_NAME" --clobber
else
    echo ">>> Creating release $VERSION..."
    gh release create "$VERSION" \
        --title "Wren $VERSION" \
        --notes "Auto-generated release." \
        "$APPIMAGE_NAME"
fi

echo ""
echo "Done! Release: https://github.com/$OWNER/$REPO/releases/tag/$VERSION"
echo "Asset: $APPIMAGE_NAME"
