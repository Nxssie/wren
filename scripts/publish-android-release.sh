#!/usr/bin/env bash
#
# Publishes the signed Android APK to the GitHub release of the current tag.
#
# Run by hand, deliberately: nothing here is wired into CI, so which builds become
# public is a decision, not a side effect of pushing. Attaches to the release the tag
# already has (the desktop artifact release) and uploads nothing else — the keystore
# is the one file that must never leave this machine and the vault.
set -euo pipefail

cd "$(dirname "$0")/.."

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

required_vars=(
  WREN_RELEASE_KEYSTORE_PATH
  WREN_RELEASE_KEYSTORE_PASSWORD
  WREN_RELEASE_KEY_ALIAS
  WREN_RELEASE_KEY_PASSWORD
)
for name in "${required_vars[@]}"; do
  [ -n "${!name:-}" ] || die "$name is unset — the APK would come out unsigned (see the wren item in Bitwarden)"
done

# The APK name comes from `git describe`, so an uncommitted tree would be published under a
# version that does not exist anywhere in history.
[ -z "$(git status --porcelain)" ] || die "working tree is not clean; commit first"

tag=$(git describe --tags --exact-match 2>/dev/null) \
  || die "HEAD is not tagged — tag the release first (git tag v0.5.0) and push it"
if [[ $tag != v* ]]; then
  die "tag '$tag' does not start with v"
fi
version=${tag#v}

printf 'building wren %s…\n' "$version"
./gradlew :android:app:assembleRelease

apk="android/app/build/outputs/apk/release/wren-${version}.apk"
[ -f "$apk" ] || die "expected $apk — got: $(ls android/app/build/outputs/apk/release 2>/dev/null)"

# An unsigned or wrongly-signed APK must never reach a release page.
apksigner=$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2>/dev/null | tail -1)
[ -n "$apksigner" ] || die "apksigner not found; set ANDROID_HOME"
certs=$("$apksigner" verify --print-certs "$apk") || die "$apk is not signed"
grep -q "CN=wren" <<<"$certs" || die "$apk is signed by something other than CN=wren"
printf '%s\n' "$(grep 'SHA-256' <<<"$certs" | head -1)"

# One release per version holds every platform's artifacts, so this normally adds an asset to
# the release the tag already created.
if gh release view "$tag" >/dev/null 2>&1; then
  gh release upload "$tag" "$apk" --clobber
  printf 'uploaded %s to existing release %s\n' "$(basename "$apk")" "$tag"
else
  gh release create "$tag" "$apk" --title "$tag" --generate-notes
  printf 'created release %s with %s\n' "$tag" "$(basename "$apk")"
fi

gh release view "$tag" --json assets \
  | python3 -c 'import json,sys; [print(" asset:", a["name"]) for a in json.load(sys.stdin)["assets"]]'
