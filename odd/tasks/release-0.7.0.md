# release-0.7.0 — cut, sign and publish 0.7.0

Branch: `feat/in-app-updates` → `dev/0.7.0` → `main` (tag `v0.7.0` on the main merge)
Status: complete. Published 2026-10-15 as https://github.com/Nxssie/wren/releases/tag/v0.7.0
Commits: release commits authorized by the user; the tag and the GitHub release are the goal.

| Task | Route | Commit |
| --- | --- | --- |
| 1 | Release ground truth: topology, CI, gh, apksigner | n/a |
| 2 | Docs: device pass recorded, stale task-4 checkbox fixed | `eff6d00` |
| 3 | `odd/tasks/release-0.7.0.md` committed with the two doc updates | `c2c9c8d` |
| 4 | `feat/in-app-updates` → `dev/0.7.0` | `918fbab` |
| 5 | `fix/oauth-loopback-bind` (PR #13) → `dev/0.7.0`, desktop `appVersion` re-bumped 0.6.1 → 0.7.0 | `e7c2c18`, `98c74c6` |
| 6 | `dev/0.7.0` → `main` (PR #15, merge commit) | `837124fa` |
| 7 | Tag `v0.7.0` on `837124fa`, pushed | — |
| 8 | gradle-ci run 38712137421 on main: completed success | — |
| 9 | `mise run android:release` created the release with `wren-0.7.0.apk`, `CN=wren` verified | — |
| 10 | `mise run desktop:release` attached deb/msi/dmg from that same CI run | — |
| 11 | PR #14 closed as superseded (its fix shipped as `a968c44`) | — |

## Specs

- **S1.** "podemos firmar una release 0.7.0" — sign a 0.7.0 release with the release keystore.
- **S2.** What ships: the in-app-updates work plus "Also PR #13 (the 0.6.1 fixes)". The 2 commits
  on `feat/soundcloud-like-writes` stay out of this release.
- **S3.** What `v0.7.0` points at: "feat → dev/0.7.0 → main, tag the main merge".
- **S4.** PR #14: "Close it as superseded by 0.7.0" — close it, do not merge it.
- **S5.** Signing comes from the four canonical variables (`WREN_RELEASE_KEYSTORE_PATH`,
  `WREN_RELEASE_KEYSTORE_PASSWORD`, `WREN_RELEASE_KEY_ALIAS`, `WREN_RELEASE_KEY_PASSWORD`),
  keystore `~/.android/keys/wren-release.jks`, alias `wren`, `CN=wren`. Never from CI.

## Facts the release rests on

- `origin/dev/0.7.0` is level with `origin/main` before this cycle; both were at `697df9d`.
- `gradle-ci.yml` runs only on pushes to `main` and builds the three desktop distributables
  there; `scripts/attach-desktop-release.sh` resolves that run by the tagged commit on `main`,
  so the tag must sit on a commit that CI built.
- `ANDROID_HOME` is unset; `scripts/publish-android-release.sh` falls back to
  `$HOME/Android/Sdk/build-tools/*/apksigner` (36.0.0 present), so its `CN=wren` gate runs.
- `main` was 14 commits behind this branch: 13 in-app-update commits plus the cherry-picked
  stream fix `a968c44` (from `fix/soundcloud-hls-pick`, whose PR #14 is `ae24a77` — same content,
  different SHA, so GitHub will not close #14 by itself).
- The signed artifact is `wren-0.7.0.apk` with `versionCode 154`, `versionName 0.7.0`, signed by
  `CN=wren` (SHA-256 `dffda0628ec2bd5797de1959a010f0484ad85274c297c6e2e18d4f1ce928460a`).

## Log

- **L1.** "okay, funciona, podemos firmar una release 0.7.0¿" — after the device pass confirmed
  the updater works, ask for a signed 0.7.0 release.
- **L2.** "haz exec fish para recargar las envs" — the signing variables were renamed to the four
  canonical names. A child process cannot change the environment of the Pi session that already
  started, so signing builds run through `fish -c`, which loads the user's own config and exports
  the canonical names to its children.
- **L3.** Decisions relayed through the question prompt: content = this branch + PR #13, tag on
  the main merge, PR #14 closed as superseded.
- **L4.** Outcome: everything above published and verified. The desktop `appVersion` had come
  into `dev/0.7.0` as 0.6.1 through the PR #13 merge and was re-bumped to 0.7.0 before the tag —
  the published deb/msi/dmg names all carry 0.7.0, which is the check that caught it. Suite
  green at 267 tests / 0 failures before the merge; CI run 38712137421 success; APK signature
  `dffda062…` (`CN=wren`). PR #14 closed by hand: the cherry-pick is a different SHA, so GitHub
  would never have closed it.
