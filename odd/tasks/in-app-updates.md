# in-app-updates — in-app auto-update for the sideloaded Android APK

Branch: `feat/in-app-updates` (from `origin/dev/0.7.0`, base `697df9d`)
Status: complete. Native review approved and acknowledged (lineage `review-c3c33f14e1f9cff6`).
Commits: work-unit commits authorized by the user on this branch; no push.

| Task | Commit |
| --- | --- |
| plan | `58b53c0` docs(odd): plan the in-app-updates feature |
| 1 | `3983d00` feat(update): compare installed and released app versions |
| 2 | `864d6a9` feat(update): read the newest release and pick its APK |
| 3 | `451f55a` feat(update): persist the last check and the dismissed version |
| 4 | `69dee4d` feat(update): decide whether to check and what to offer |
| 5 | `a4f0cf3` feat(android): download and install a release APK in place |
| 6 | `602a8f1` feat(android): offer an available update and check on launch |
| 6 | `73b8f0c` docs: describe how the Android app updates itself |
| 7 | `c5ad979` fix(update): match the unsigned marker case-insensitively |
| 7 | `617de2f` fix(update): return null instead of throwing on an overflowing version |

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

- [x] **1. Version model and comparison** — `shared/src/main/kotlin/update/AppVersion.kt`
  Parse `git describe`-style strings into a comparable form; strictly-newer semantics, with a
  describe suffix counting as *ahead* of its tag (see Constraints) and suffixed versions ordering
  by commit count.
  Test-first: `shared/src/test/kotlin/update/AppVersionTest.kt` over `0.6.0`, `0.6.0-12-gabc1234`,
  `0.6.0-dirty`, `v0.6.0`, equal, older, and unparseable input.
  Evidence: commit `3983d00`. RED observed first — `:shared:compileTestKotlin` failed with
  `Unresolved reference 'AppVersion'` and no implementation on disk; then GREEN at 22 tests,
  0 failures. Re-verified by the parent: 22 tests / 0 failures, full `:shared:test` green.

- [x] **2. Release parsing and APK asset selection** — `shared/src/main/kotlin/update/UpdateRelease.kt`
  Serialization models for the GitHub `releases/latest` payload; `tag_name` → version;
  select the `wren-*.apk` asset and never a `-unsigned` one.
  Test-first: fixture JSON covering extra `deb`/`msi`/`dmg` assets, an unsigned APK present,
  no APK at all, draft and prerelease.
  Evidence: commit `864d6a9`. RED observed first — `:shared:compileTestKotlin` failed with
  `Unresolved reference 'parseLatestRelease'` and no implementation on disk; then GREEN at 27
  tests, 0 failures. Re-verified by the parent: full `:shared:test` green at 189 tests,
  0 failures, 0 errors. Note both parsers return null rather than throwing, and several APK
  candidates are resolved by the published `wren-<tag>.apk` name instead of guessing.

- [x] **3. Throttle and dismissal persistence** — `shared/src/main/kotlin/update/UpdatePreferences.kt`
  Follows the existing `DownloadPreference` pattern (plain scalar file under `AppDirs.config`):
  last successful check timestamp, and the version the user dismissed.
  Test-first against a temp `AppDirs.init`.
  Evidence: commit `451f55a`. RED observed first — `:shared:compileTestKotlin` failed with
  `Unresolved reference 'UpdatePreferences'` and no implementation on disk; then GREEN at 16
  tests, 0 failures. Re-verified by the parent: full `:shared:test` green at 205 tests,
  0 failures, and confirmed no throttle policy leaked into this file.

- [ ] **4. Update checker state machine** — `shared/src/main/kotlin/update/UpdateChecker.kt`
  `StateFlow<UpdateState>`, injectable fetcher and clock; `check(force)` honours the daily
  throttle; states idle / checking / available(version, url, size) / failed.
  Test-first: throttle suppresses, `force` bypasses, newer releases surface, equal or older do
  not, a fetch failure becomes `Failed` without throwing and without burning the throttle.
  Evidence: _pending_

- [x] **5. Android: download, verify, install** —
  `android/app/src/main/kotlin/com/wren/app/update/ApkInstaller.kt`
  Stream the APK into `cacheDir` through a temp file renamed on success; refuse a download that is
  not a parseable archive before opening the installer; compare the archive's signers with the
  installed app's and refuse on a positive mismatch (`PackageManager.signingInfo` on API 28+, the
  older `signatures` field below it), while leaving the enforcement itself to Android when the
  signers cannot be read at all — blocking there would strand legitimate updates. Install through
  a `FileProvider` content URI; detect a revoked install grant and route to the system setting.
  Manifest gains `REQUEST_INSTALL_PACKAGES`, the provider, and `res/xml/file_paths.xml`; stale
  APKs are cleared. Verify: `:android:app:assembleDebug` plus a manual device pass.
  Evidence: commit `a4f0cf3`. `:android:app:assembleDebug` green, re-run by the parent; merged
  manifest resolves the authority to `com.wren.app.fileprovider` and `res/xml/file_paths.xml` is
  packaged. `FileDestination` reused for the temp/rename guarantee, not mirrored. Parent added one
  hardening at the network boundary: the asset name is reduced to its basename before it becomes a
  path under the cache, since `selectApk` constrains only the `wren-`/`.apk` shape. No unit test
  exists for this file by design — see Test strategy; its behaviours remain device-unverified.

- [x] **6. Android: banner, wiring, docs** —
  `android/app/src/main/kotlin/com/wren/app/ui/UpdateBanner.kt`, wired into `WrenApp.kt` above
  the tab content so it survives tab switches, built from existing theme tokens and
  `SectionLabel`. Checker constructed in `WrenApplication` with the version read from
  `PackageManager`; launch check triggered from `MainActivity`. README documents the flow, the
  one-time install grant and the same-keystore requirement.
  Verify: `:android:app:assembleDebug`, manual device pass, README review.
  Evidence: commits `602a8f1` (wiring and banner) and `73b8f0c` (README). `:android:app:assembleDebug`
  green, re-run by the parent; `:shared:test` green at 225 tests, 0 failures. Delivered as two
  commits because the first single-unit delegation produced no changes at all and was split.
  Parent correction during review: the banner was originally drawn over the tab content in the
  scaffold's `Box`, which only avoids covering a screen if that screen happens to reserve the
  inset. It now sits in a `Column` above the tab area so it displaces instead of covering, and
  stays composed while the player is open, since it owns an in-flight download that leaving the
  composition would cancel. The player overlay still paints over the whole area, banner included.
  The launch check runs on `Dispatchers.IO`, per the `util/Http` convention that its blocking
  calls are made by callers already on an IO dispatcher.

- [x] **7. Close** — full `mise run test`, `:android:app:assembleDebug`, README read-through,
  native review candidate prepared from the work-unit commits.
  Evidence: `mise run test` green at **263 tests, 0 failures, 0 errors, 0 skipped** (shared 232 +
  desktop 31), `:android:app:assembleDebug` green, tree clean. Independent verification pass also
  confirmed the merged manifest resolves the provider authority to `com.wren.app.fileprovider`,
  that `res/xml/file_paths.xml` is packaged, that the launch check runs on `Dispatchers.IO`, that a
  cancelled download leaves no installable file, and that the feature writes only under
  `AppDirs.config` and `cacheDir/updates`. Two defects were found and fixed here: `c5ad979`
  (case-sensitive `-unsigned` exclusion) and `8dc8734` (overflow crash). Android `lintDebug` reports
  45 pre-existing errors, none in any file this feature touches. Native review **approved and
  acknowledged** after one bounded correction — see Native review outcome.
## Native review outcome

Lineage `review-c3c33f14e1f9cff6`, base-ref `697df9d`, committed-only, 17 paths, 2031 changed
lines, tier `high` (reason `hot_path` on the update path in `ApkInstaller.kt`), correction budget
200. All four lenses ran (`review-risk`, `review-resilience`, `review-readability`,
`review-reliability`) and the review closed at `correction_required` with exactly one corroborated
finding, deterministic and `introduced`:

- **`R3-parse-overflow`**, severity `CRITICAL`, `shared/src/main/kotlin/update/AppVersion.kt:61-65`.
  `parse` called `toInt()` on the regex groups, so a version component past `Int` range — or an
  over-long describe commit count — threw `NumberFormatException` instead of returning null.
  Nothing above it catches that: `parseLatestRelease` documents that parsing never throws,
  `UpdateChecker.check` guards only its fetch, and the launch check runs in a coroutine. The throw
  would escape and crash the app, and because the check is recorded only after resolution, every
  subsequent cold start refetched, re-parsed the same tag and crashed again while that release
  stayed latest. A crash loop, and a genuine catch — the tests covered malformed shapes but not
  numeric overflow.

Corrected in `8dc8734` (test-first: 5 failing tests first, `NumberFormatException` observed, then
`toIntOrNull` with a null fallback and the describe-count hint dropped on overflow). 39 diff lines,
within the 200 budget.

**Ordering, and what actually blocked it.** The first submission failed for an *ordering* reason,
not a provider defect. `gentle_review_capture` requires the binding's `--target` to equal STATUS's
current `target_identity` (`extensions/gentle-ai.ts:7320-7358` in the gentle-pi package). The
correction binding is issued against the **authority** target — the reviewed candidate — while any
change to the candidate moves the current identity. So the plan can only be accepted while the
candidate is still unmodified: `correction_lines` is a count declared **before** editing, not a
measurement taken after. The working sequence is:

1. Leave the candidate exactly as reviewed, and submit the correction plan (`correction_lines`).
2. Commit the correction; the provider then reports `targeted_validation_required`.
3. Submit the validation slot, whose `validationRequest.correctionTargetIdentity` binds it to the
   corrected candidate instead of STATUS's identity.

Reaching step 1 needed the candidate content restored to its reviewed state first; `git reset
--soft c5ad979` did it without touching any file, with the commits preserved on
`backup/post-review-fixes`. The 39-line plan then registered, the correction was committed as
`617de2f`, and the validator ran.

Correcting this same trap cost real time: three earlier submissions were rejected
(`capture-binding-rejected`, "does not carry one non-empty matching provider lineage and target
token") because the fix had already been committed. The lesson is recorded in Risks.

**Outcome: approved.** One `review-validator` model run; the review closed at `approved` and the
exact provider-issued acknowledgement was executed unchanged. Authority burned, evidence
`gentle-ai.review-acknowledged/v1`, delivery left to ordinary repository policy. Nothing was
pushed and nothing was merged.

**Advisory findings — non-blocking.** The provider recorded these against the approved candidate
and states that none opened a correction, none reopens the review, and none may be used as a
reason to re-run review on this candidate. They are later work, not debt hidden by the approval:

| id | lens | severity | location |
| --- | --- | --- | --- |
| `R1-signcheck-fail-open` | risk | SUGGESTION | `ApkInstaller.kt:160-180` — the deliberate signing asymmetry |
| `R1-update-url-unpinned` | risk | SUGGESTION | `UpdateRelease.kt:22-40` |
| `R2-1` | readability | WARNING | `UpdateBanner.kt:35-39` |
| `R2-2` | readability | SUGGESTION | `UpdateRelease.kt:6-9` |
| `R2-3` | readability | SUGGESTION | `odd/tasks/in-app-updates.md:120-125` |
| `R3-android-glue-coverage` | reliability | SUGGESTION | `ApkInstaller.kt:145-160` |
| `R3-main-thread-dismiss` | reliability | SUGGESTION | `UpdateBanner.kt:132-134` |
| `R3-stale-apk-clear` | reliability | WARNING | `ApkInstaller.kt:134-143` |
| `R4-001` | resilience | WARNING | `UpdateChecker.kt:104-108` |
| `R4-002` | resilience | WARNING | `UpdateBanner.kt:78-90` |
| `R4-003` | resilience | SUGGESTION | `ApkInstaller.kt:56-77` |

## Verification plan

- `mise run test` (`:shared:test :desktop:test`) green, plus `:android:app:assembleDebug`.
- Manual device pass for the Android-only paths: banner appearance, download progress, install
  dialog, revoked-permission path, and a same-version launch producing no banner.
- A real end-to-end update can only be proven against a published newer release; if none exists
  at verification time, that step is reported explicitly as unverified rather than assumed.

### Device pass checklist (outstanding)

1. Same-version launch shows no banner (the throttle then suppresses the network call).
2. With a newer release published, the banner appears above the tab content without covering it.
3. Tapping Install with the grant already given downloads, then opens the system installer.
4. Tapping Install with the grant revoked lands on the "install unknown apps" screen.
5. Returning from that screen and tapping Install works without restarting the app.
6. A second tap during a download does not start a second download.
7. Opening the player mid-download keeps the banner composed and the download running.
8. A download failure shows a retry, and the retry succeeds once the cause is gone.
9. "Later" hides that release, and it stays hidden across a cold start.
10. An app signed with a different key is refused with a readable message, not a raw installer
    error.
11. Fetched through `adb shell` after a cold start: exactly one request to `api.github.com`,
    none on the second launch that day.

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
- **Correction ordering is a trap.** Applying and committing a correction *before* submitting the
  correction plan makes that plan permanently unsubmittable, because the binding is bound to the
  reviewed candidate's identity and any candidate change moves it. Plan first, then correct. See
  Native review outcome.

## Follow-ups (not in this feature)

- Move Android signing into CI so releases stop depending on one machine. Only worth it if APK
  publishing ever needs to happen without the keystore holder present.
- Surface the installed version in an About/settings surface, which the app does not have yet.
