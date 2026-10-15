# release-0.7.1 — SoundCloud like retries and the About surface

Branch: `dev/0.7.0` (this cycle's work lands on it directly; it releases as `v0.7.1` from a merge to `main`)
Status: in progress.

| Task | Route | Commit |
| --- | --- | --- |
| 1 | Merge `feat/soundcloud-like-writes` into `dev/0.7.0`, resolve the 3 conflicts | pending |
| 2 | About row in `AccountsDialog` showing the installed version | pending |
| 3 | Record the 0.7.0 outcome in `odd/tasks/release-0.7.0.md`; bump `appVersion` to 0.7.1 | pending |
| 4 | Suite + `:android:app:assembleDebug` + `assembleRelease` green | pending |
| 5 | PR `dev/0.7.0` → `main`, tag `v0.7.1`, publish APK + desktop | pending |
| 6 | The phone updates itself: the first real end-to-end updater pass | pending |

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
