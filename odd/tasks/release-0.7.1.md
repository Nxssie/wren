# release-0.7.1 — SoundCloud like retries and the About surface

Branch: `dev/0.7.0` (this cycle's work landed on it directly; released as `v0.7.1` from the merge to `main`)
Status: complete. Published 2026-10-15 as https://github.com/Nxssie/wren/releases/tag/v0.7.1

| Task | Route | Commit |
| --- | --- | --- |
| 1 | Merge `feat/soundcloud-like-writes` into `dev/0.7.0`, resolve the 3 conflicts | `fb7fb3e` |
| 2 | About row in `AccountsDialog` showing the installed version | `5290680` |
| 3 | Record the 0.7.0 outcome in `odd/tasks/release-0.7.0.md`; bump `appVersion` to 0.7.1 | this commit |
| 4 | Suite (273 tests) + `assembleDebug` + signed `assembleRelease` green | — |
| 5 | PR #16 → `main` (merged `cab70ca`), tag `v0.7.1` on it, CI run 38721689517 success, APK + deb/msi/dmg published | — |
| 6 | The phone updates itself: blocked on the 24 h throttle it recorded at 10:35 today | pending |

## Merge shape

The trial merge hit 3 conflicts because the likes branch forked before in-app-updates landed:
`AndroidManifest.xml` (updater's FileProvider vs the DataDome challenge activity — union),
`WrenApplication.kt` (both import sets — union) and `SoundCloudLikes.kt` (semantic). The last
one is the real decision: `dev` had made likes read-only (`CAN_TOGGLE = false`, gates in four UI
files) pending exactly this feature, and the branch deletes the flag — the feature *is* the
toggle coming back. Resolution takes the branch's file wholesale and reverts the four gates to
their pre-`465464b` form, keeping everything else that commit added (pull-to-refresh, revalidate
on entry). Suite count went 267 → 273 with the branch's new tests.

## Specs

- **S1.** "podemos sacar la 0.7.1 con alguna feature que veas necesaria?" — 0.7.1 ships the
  SoundCloud like-retry work already on `feat/soundcloud-like-writes` (`1889e64`).
- **S2.** Chosen content: "Likes + About" — plus the About surface showing the installed version
  (the follow-up declared in `odd/tasks/in-app-updates.md`).
- **S3.** The About surface is Android-only, because the follow-up it closes comes from the
  Android updater's feature document; desktop keeps its own version story untouched.
- **S4.** The 0.7.1 tag goes on the merge into `main`, like `v0.7.0`; the desktop `appVersion`
  is bumped to 0.7.1 before the tag (the 0.6.1 leak taught that the hard way).

## Conflict map (from the trial merge)

- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/kotlin/com/wren/app/WrenApplication.kt`
- `shared/src/main/kotlin/api/SoundCloudLikes.kt`

The likes branch forked before in-app-updates landed, so `WrenApplication.onCreate` changed on
both sides; resolution must keep the updater wiring intact and add whatever the like-retry work
introduces.

## Log

- **L1.** "podemos sacar la 0.7.1 con alguna feature que veas necesaria?" — pick a feature and
  cut 0.7.1.
- **L2.** Decision relayed through the question prompt: content = "Likes + About".
