#!/usr/bin/env bash
#
# Attaches the CI-built desktop packages (.deb, .msi, .dmg) of a tag to its GitHub release.
#
# The desktop installers are the one thing that cannot be built here: they need Windows and
# macOS. `.github/workflows/gradle-ci.yml` already builds all three on its runners for every
# push to main, so this only has to fetch the run for the tagged commit and publish its
# artifacts — by hand, when you decide, never as a side effect of the CI itself.
#
#   scripts/attach-desktop-release.sh [tag]     # defaults to the tag on HEAD
#   DRY_RUN=1 scripts/attach-desktop-release.sh # download and list, upload nothing
set -euo pipefail

cd "$(dirname "$0")/.."

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

tag=${1:-$(git describe --tags --exact-match 2>/dev/null || true)}
[ -n "$tag" ] || die "no tag given and HEAD is not tagged — pass one, e.g. v0.5.1"

commit=$(git rev-parse "$tag^{commit}" 2>/dev/null) || die "unknown tag $tag"
gh release view "$tag" >/dev/null 2>&1 \
  || die "no GitHub release for $tag yet — publish the APK first (mise run android:release)"

# The release has to carry the packages built from the commit the tag points at, which is the
# main-branch run for that commit. Anything else would publish binaries nobody can trace.
# `--jq`, not `--template`: a Go template renders the id as 3.4576914953e+10, which then 404s.
mapfile -t runs < <(gh run list --workflow gradle-ci.yml --branch main --commit "$commit" \
  --limit 20 --json databaseId,status,conclusion \
  --jq '.[] | "\(.databaseId)\t\(.status)\t\(.conclusion)"')
[ ${#runs[@]} -gt 0 ] || die "no Gradle CI run for $commit on main — push the merge and wait for it"

run_id=""
for row in "${runs[@]}"; do
  IFS=$'\t' read -r id status conclusion <<<"$row"
  if [ "$status" = "completed" ] && [ "$conclusion" = "success" ]; then
    run_id=$id
    break
  fi
done
[ -n "$run_id" ] || die "no successful run for $commit yet (see: gh run list --commit $commit)"

printf 'using CI run %s for %s (%s)\n' "$run_id" "$tag" "${commit:0:7}"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gh run download "$run_id" --pattern 'wren-*' --dir "$work" >/dev/null \
  || die "could not download the artifacts of run $run_id"

mapfile -t packages < <(find "$work" -type f | sort)
[ ${#packages[@]} -gt 0 ] || die "run $run_id produced no wren-* artifacts"

printf 'attaching to %s:\n' "$tag"
printf '  %s\n' "${packages[@]##*/}"
if [ -n "${DRY_RUN:-}" ]; then
  printf 'DRY_RUN set: nothing uploaded\n'
  exit 0
fi
gh release upload "$tag" "${packages[@]}" --clobber

gh release view "$tag" --json assets --template '{{range .assets}} asset: {{.name}}{{"\n"}}{{end}}'
