# in-app-updates — in-app auto-update for the sideloaded Android APK

Branch: `feat/in-app-updates` (from `origin/dev/0.7.0`, base `697df9d`)
Status: in progress

## Context

Wren ships four artifacts per release. Android is the only one with no package manager behind
it: `wren-<version>.apk` sits on the GitHub release page and the user has to find the release,
download the file, and sideload it. Every version therefore strands the installed base.

Desktop is left alone by this feature — `deb`, `msi` and `dmg` keep the release page.

## Goal

An installed APK notices a newer release, fetches it itself, and installs it after a single
confirmation from the user. The user never opens a browser or the release page again.

## Decisions (agreed with the user)

1. **Scope: Android APK only.** Desktop installers have no updater in this feature.
2. **Flow: auto-download, one tap to install.** The check runs on launch, throttled to at most
   one network call per day; a newer release is downloaded in the background; a banner offers
   *Install*; tapping it hands the file to Android's package installer, which shows its own
   confirmation dialog. Nothing installs without that tap.
3. **Fully silent install is out of scope and impossible here.** Android reserves silent
   installs for device owners and system apps; the `PackageInstaller` confirmation dialog is
   the OS, not us.

## Non-goals

- Updating `deb`, `msi` or `dmg`, or a desktop tray notification.
- Automatic rollback or downgrade. Android refuses lower `versionCode`s anyway.
- Play Store or F-Droid distribution.
- A self-updating `yt-dlp` binary.

## Constraints that shape the design

- **Same signing key or nothing.** Android refuses to replace an app whose signature differs.
  Release APKs are signed locally with `~/.android/keys/wren-release.jks`; CI never signs.
  `scripts/publish-android-release.sh` already rejects anything not `CN=wren`, and unsigned
  builds are never published. The updater can therefore assume a signed asset — and should say
  so plainly when the assumption breaks instead of failing with a raw install error.
- **`versionName` is a `git describe` string.** e.g. `0.6.0`, but also `0.6.0-12-gabc1234` and
  `0.6.0-dirty` on a development build. Comparison has to handle those, not just `x.y.z`.
- **A `git describe` suffix means *ahead*, not *older*.** `0.6.0-12-gabc1234` is twelve commits
  past the `0.6.0` tag, so `versionCode` (the commit count) is higher than the tagged release's.
  Ordering it as a pre-release, the semver instinct, would offer the `0.6.0` APK to that install,
  download it, and then have Android refuse the downgrade. Suffixed versions therefore outrank
  their own tag, and two suffixed versions compare by commit count.
- **`versionCode` is the commit count.** A release built from a later commit always outranks an
  earlier one, so a name-based "is it newer" check agrees with what the installer will accept.
- **`shared` cannot see the Android version.** It is a plain JVM library. Android must inject
  the installed version, mirroring how `OAuthConfig.clientId` is injected in `WrenApplication`.
- **One unauthenticated GitHub API call per day** from an IP-limited endpoint (60/hour). The
  throttle is not a nicety; it is what keeps the feature from being rate-limited.
- **`REQUEST_INSTALL_PACKAGES` is a user-visible grant.** Sideloading already requires enabling
  "install unknown apps" for Wren, but the app must detect the grant being revoked and route the
  user to it rather than failing silently.

## Test strategy

`shared` is a plain JVM library with JUnit 5 (`useJUnitPlatform()`), kotlinx-serialization and
OkHttp on its API classpath. It already holds all logic that is not platform glue, and the
Android module has no test source set at all.

Therefore: **all deterministic logic goes in `shared` and is written test-first.** Version
parsing, release parsing, asset selection, the throttle and the checker state machine are pure
or have injectable seams (fetcher, clock), so each gets a real RED before its GREEN.

Android-side code stays thin platform glue — file download, signature check, install intent,
Compose banner — and is verified by compilation plus a manual device pass. There is no
meaningful RED available there without introducing an instrumented-test runner the project does
not have, and inventing one is a bigger change than this feature.

## Tasks

- [ ] **1. Version model and comparison** — `shared/src/main/kotlin/update/AppVersion.kt`
  Parse `git describe`-style strings into a comparable form; strictly-newer semantics, with a
  describe suffix counting as *ahead* of its tag (see Constraints) and suffixed versions ordering
  by commit count.
  Test-first: `shared/src/test/kotlin/update/AppVersionTest.kt` over `0.6.0`, `0.6.0-12-gabc1234`,
  `0.6.0-dirty`, `v0.6.0`, equal, older, and unparseable input.
  Evidence: _pending_

- [ ] **2. Release parsing and APK asset selection** — `shared/src/main/kotlin/update/UpdateRelease.kt`
  Serialization models for the GitHub `releases/latest` payload; `tag_name` → version;
  select the `wren-*.apk` asset and never a `-unsigned` one.
  Test-first: fixture JSON covering extra `deb`/`msi`/`dmg` assets, an unsigned APK present,
  no APK at all, draft and prerelease.
  Evidence: _pending_

- [ ] **3. Throttle and dismissal persistence** — `shared/src/main/kotlin/update/UpdatePreferences.kt`
  Follows the existing `DownloadPreference` pattern (plain scalar file under `AppDirs.config`):
  last successful check timestamp, and the version the user dismissed.
  Test-first against a temp `AppDirs.init`.
  Evidence: _pending_

- [ ] **4. Update checker state machine** — `shared/src/main/kotlin/update/UpdateChecker.kt`
  `StateFlow<UpdateState>`, injectable fetcher and clock; `check(force)` honours the daily
  throttle; states idle / checking / available(version, url, size) / failed.
  Test-first: throttle suppresses, `force` bypasses, newer releases surface, equal or older do
  not, a fetch failure becomes `Failed` without throwing and without burning the throttle.
  Evidence: _pending_

- [ ] **5. Android: download, verify, install** —
  `android/app/src/main/kotlin/com/wren/app/update/ApkInstaller.kt`
  Stream the APK into `cacheDir` via a temp file and rename on success; reject an APK whose
  signing certificate differs from the installed app's; install through a `FileProvider`
  content URI; detect a revoked install grant and route to the system setting. Manifest gains
  `REQUEST_INSTALL_PACKAGES`, the provider, and `res/xml/file_paths.xml`; stale APKs are cleared.
  Verify: `:android:app:assembleDebug` plus a manual device pass.
  Evidence: _pending_

- [ ] **6. Android: banner, wiring, docs** —
  `android/app/src/main/kotlin/com/wren/app/ui/UpdateBanner.kt`, wired into `WrenApp.kt` above
  the tab content so it survives tab switches, built from existing theme tokens and
  `SectionLabel`. Checker constructed in `WrenApplication` with the version read from
  `PackageManager`; launch check triggered from `MainActivity`. README documents the flow, the
  one-time install grant and the same-keystore requirement.
  Verify: `:android:app:assembleDebug`, manual device pass, README review.
  Evidence: _pending_

- [ ] **7. Close** — full `mise run test`, `:android:app:assembleDebug`, README read-through,
  native review candidate prepared from the work-unit commits.
  Evidence: _pending_

## Verification plan

- `mise run test` (`:shared:test :desktop:test`) green, plus `:android:app:assembleDebug`.
- Manual device pass for the Android-only paths: banner appearance, download progress, install
  dialog, revoked-permission path, and a same-version launch producing no banner.
- A real end-to-end update can only be proven against a published newer release; if none exists
  at verification time, that step is reported explicitly as unverified rather than assumed.

## Risks

- **A newer tag with an older commit count** would be offered but refused by Android. Judging
  newness by version name and letting the installer enforce the code is the documented
  behaviour; a mismatch surfaces as an install error.
- **The `1.0.0` fallback.** `versionName` falls back to `1.0.0` when `git describe` fails, which
  would look newer than any real release and silently disable updates. Release builds pin
  `WREN_VERSION_NAME`, so this only affects a hand-built APK; it is documented, not coded around.
- **Rate limiting.** Anonymous GitHub API calls are capped per IP; the daily throttle is the
  mitigation.
- **Scope creep into desktop.** Explicitly a non-goal.

## Follow-ups (not in this feature)

- Move Android signing into CI so releases stop depending on one machine. Only worth it if APK
  publishing ever needs to happen without the keystore holder present.
- Surface the installed version in an About/settings surface, which the app does not have yet.
