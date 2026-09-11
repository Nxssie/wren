<p align="center">
  <img src="desktop/src/main/resources/wren-256.png" alt="Wren logo" width="128" height="128" />
</p>

<h1 align="center">Wren</h1>

<p align="center">
  A native desktop music player built with Kotlin and Compose Desktop.<br/>
  Searches and streams audio from YouTube Music, YouTube, and SoundCloud — no ads, login optional.<br/>
  An Android app shares the same API, auth and provider code.
</p>

<p align="center">
  <a href="https://github.com/Nxssie/wren/actions/workflows/gradle-ci.yml"><img src="https://github.com/Nxssie/wren/actions/workflows/gradle-ci.yml/badge.svg" alt="Build status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Compose%20Multiplatform-4285F4.svg" alt="Compose Multiplatform">
</p>

## Features

- Search songs across YouTube Music, YouTube, and SoundCloud simultaneously, results interleaved and sortable by popularity, duration, or source
- SoundCloud Station: start a radio of related tracks from any SoundCloud result
- Discover per platform: SoundCloud "Made for you" mixes, curated and trending selections plus a weekly list generated from your listening history; YouTube radios seeded by your recent plays
- Artist pages with top songs, albums, singles, and EPs
- Queue playback with automatic prefetching of upcoming tracks
- Download SoundCloud tracks as local audio files (mp3/m4a) straight from the search results
- Google OAuth login to access your YouTube Music playlists and library
- Popularity-based artist sorting using monthly listener counts parsed directly from the YTMusic API
- Now Playing screen with synced/plain lyrics (from [lrclib.net](https://lrclib.net)) and resizable queue panel
- Dark/light theme toggle in the sidebar
- Persistent player bar with seek, volume, and queue controls
- Stream URL resolution via `yt-dlp` (bundled in AppImage, or available in PATH)
- 4-hour stream URL cache for YouTube, 20-minute cache for SoundCloud progressive streams

## Stack

- **UI**: [Compose Desktop](https://www.jetbrains.com/compose-multiplatform/) (Jetpack Compose for JVM)
- **Language**: Kotlin
- **Player**: [JavaCV](https://github.com/bytedeco/javacv) + FFmpeg (in-process audio decoding and playback)
- **Stream resolution**: `yt-dlp` (for reliable YouTube stream URL extraction)
- **Concurrency**: Kotlin coroutines (`async`/`coroutineScope` for parallel API calls)
- **Serialization**: `kotlinx.serialization`
- **Auth**: OAuth 2.0 with PKCE (S256) local redirect, implemented from scratch without third-party auth libraries

## Architecture

Wren is split into Gradle modules: `shared` holds everything platform-agnostic (models, API
clients, auth, providers, HTTP), `desktop` is the shipping Linux app, and `android` is the
Android app (search, streaming and queue; sessions come later).

```
shared/src/main/kotlin/
├── models/Models.kt          # Domain models (SearchResult, QueueItem, Playlist, ...)
├── api/
│   ├── YoutubeMusic.kt       # Public facade — search, artist lookup
│   ├── YtMusicSearch.kt      # InnerTube search parsing (songs + artists)
│   ├── YtMusicArtist.kt      # Artist page + album track parsing
│   ├── YtMusicPlaylists.kt   # YouTube Data API v3 (playlists, view counts)
│   ├── YtMusicRadio.kt       # Per-track YouTube Music radio
│   ├── YtSearch.kt           # YouTube (non-Music) video search
│   ├── SoundCloud.kt         # SoundCloud search/stations/library (client_id scrape)
│   ├── SoundCloudDiscovery.kt# SoundCloud weekly discovery
│   ├── Lyrics.kt             # Synced/plain lyrics from lrclib.net
│   ├── ListeningHistory.kt   # Local play history (feeds discovery)
│   ├── ApiKeyManager.kt      # API key management with config file fallback
│   ├── StreamResolver.kt     # Stream URL cache + pluggable resolver
│   ├── YtDlpResolver.kt      # Desktop resolver (yt-dlp)
│   └── HttpStreamResolver.kt # Android resolver (InnerTube player + SoundCloud progressive)
├── auth/
│   ├── LocalProfile.kt       # Local profile + Google/SoundCloud session store
│   ├── GoogleAuth.kt         # Google session lifecycle
│   ├── OAuthFlow.kt          # Auth URL, loopback redirect, token exchange
│   ├── SoundCloudAuth.kt     # SoundCloud session lifecycle
│   ├── SoundCloudOAuth.kt    # SoundCloud PKCE flow
│   └── AuthEvents.kt         # Change notifications for Compose
├── provider/
│   ├── MusicProvider.kt      # Platform abstraction: search, discover, station, library
│   ├── YouTubeProvider.kt    # YouTube + YouTube Music behind one provider (radio, library)
│   └── SoundCloudProvider.kt # SoundCloud provider (stations, selections, likes, playlists)
├── player/PlayerEngine.kt    # Transport + observable state contract
└── util/
    ├── AppDirs.kt            # Per-platform config/state directories
    ├── Http.kt               # OkHttp helper (java.net.http needs Android 13+)
    └── Log.kt                # File logger for diagnostics

desktop/src/main/kotlin/
├── player/FFmpegPlayer.kt    # in-process FFmpeg decoder + Java Sound API playback
├── ui/                       # Compose Desktop UI (window, screens, dialogs)
├── util/Browser.kt           # Opens URLs in the system browser
└── main.kt                   # Entry point, UI scale detection

android/app/src/main/kotlin/com/wren/app/
├── MainActivity.kt           # Compose host
├── WrenApplication.kt        # AppDirs + stream resolver wiring
├── player/ExoPlayerEngine.kt # Media3/ExoPlayer PlayerEngine implementation
├── playback/                 # Foreground service + transport notification
└── ui/                       # Compose UI (search, discover, now playing, player bar)
```

## Requirements

- JDK 21
- `yt-dlp` available in PATH (bundled in AppImage, or install via `sudo apt install yt-dlp`)
- No external media player required — FFmpeg is bundled as a JAR dependency via JavaCV

## Build

```bash
# Copy and configure the Gradle properties
cp gradle.properties.example gradle.properties
# Edit gradle.properties if you need to point to a specific JDK

./gradlew :desktop:run
```

### AppImage

```bash
./build-appimage.sh
./Wren.AppImage
```

### Deb / RPM

```bash
./gradlew :desktop:packageDeb
./gradlew :desktop:packageRpm
```

## Android

The Android app (API 26+) covers the desktop experience without sessions yet: search across
YouTube/YouTube Music and SoundCloud, streaming playback with a foreground notification,
queue, discover and lyrics.

```bash
# Build the debug APK
./gradlew :android:app:assembleDebug

# Install on a connected device or emulator
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed locally — no CI involved. Create the keystore once and keep it safe:
it is the only thing that can update an installed app in place.

```bash
keytool -genkeypair -v -keystore ~/.android/keys/wren-release.jks -storetype PKCS12 \
  -alias wren -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$WREN_KEYSTORE_PASSWORD" -keypass "$WREN_KEYSTORE_PASSWORD" -dname "CN=wren"
```

Then build it (the four variables come from the environment, never from the repo — see the
secrets note in `AGENTS.md`). With them unset the release task still succeeds and produces an
unsigned APK:

```bash
./gradlew :android:app:assembleRelease
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Note: PKCS12 keystores use one password for the store and the key, so `WREN_KEY_PASSWORD` and
`WREN_KEYSTORE_PASSWORD` hold the same value. Signing with a different key than an installed
build already uses needs an uninstall first.

Requirements: JDK 21, an Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`),
and `android.useAndroidX=true` in `gradle.properties` (see `gradle.properties.example`).

Playback differs from desktop:

- Streams are resolved over HTTP — InnerTube's `player` endpoint with the `ANDROID_VR`
  client, and SoundCloud progressive transcodings — because Android cannot spawn `yt-dlp`.
- YouTube answers `Sign in to confirm you're not a bot` for some tracks (typically gated /
  label-restricted music videos, more often from datacenter IPs). Those tracks fall back to
  skipping in the queue. SoundCloud playback is unaffected.
- Google and SoundCloud sign-in are a later phase; connecting sessions currently lives in the
  desktop app.

## SoundCloud (no login required)

SoundCloud search, station, and weekly discovery work out of the box. The client ID is scraped automatically from the SoundCloud web app.

To use your own client ID, create `~/.config/wren/soundcloud.json`:

```json
{
  "client_id": "YOUR_SOUNDCLOUD_CLIENT_ID"
}
```

Listening history is stored locally at `~/.config/wren/history.json` and used to generate the weekly discovery playlist.

## Fractional scaling on Wayland (Hyprland, etc.)

Wren renders through XWayland (AWT has no stable native Wayland toolkit yet). Wren detects your
monitor scale at startup and renders natively, but the compositor must be told not to rescale
XWayland buffers — otherwise the UI looks pixelated under fractional scaling. In `hyprland.conf`:

```ini
xwayland {
    force_zero_scaling = true
}
```

## Profile & Sessions (optional)

Wren works without any login — search, playback, station, and weekly discovery are fully available.

Logging in unlocks provider-specific features:

| Provider | What it unlocks |
|----------|----------------|
| **Google** | YouTube Music playlists, library, view count data |
| **SoundCloud** | Liked tracks, your playlists, "Made for you" mixes in Discover |

### Profile

A local profile is created automatically at `~/.config/wren/profile.json`. You can rename it from the sidebar.

### Google login

Official builds bundle a Google OAuth client, so just click **GOO → connect** in the sidebar
and authorize in the browser. Until the app passes Google's OAuth verification you may see an
"unverified app" warning on the consent screen.

If you build from source, or want to use your own Cloud project, either:

- export `WREN_GOOGLE_CLIENT_ID` / `WREN_GOOGLE_CLIENT_SECRET` before running Gradle (baked in at build time), or
- create an OAuth 2.0 client ID in the [Google Cloud Console](https://console.cloud.google.com/)
  (Desktop app type, YouTube Data API v3 scope) and save the downloaded `client_secret_*.json`
  to `~/.config/wren/oauth.json`. The file always takes precedence over the bundled client.

Tokens are stored at `~/.config/wren/sessions/google.json` and refreshed automatically.

### SoundCloud login

Two ways to connect:

1. **Browser sign-in** (recommended): click **SC → connect**, then **sign_in_with_browser** — a window opens with the SoundCloud login page. Sign in normally; Wren captures the session cookie automatically.
2. **Manual token**: get your OAuth token from the browser (cookie `oauth_token` on soundcloud.com) and paste it.

Session stored at `~/.config/wren/sessions/soundcloud.json`.

### API Keys (optional)

Wren ships with default API keys for YouTube Search and InnerTube. To use your own keys, create `~/.config/wren/api.json`:

```json
{
  "youtubeApiKey": "YOUR_YOUTUBE_DATA_API_KEY",
  "innerTubeApiKey": "YOUR_INNERTUBE_KEY"
}
```

Keys in this file override the built-in defaults.

### API Keys (optional)

Wren ships with default API keys for YouTube Search and InnerTube. To use your own keys, create `~/.config/wren/api.json`:

```json
{
  "youtubeApiKey": "YOUR_YOUTUBE_DATA_API_KEY",
  "innerTubeApiKey": "YOUR_INNERTUBE_KEY"
}
```

Keys in this file override the built-in defaults.

## Notes

Downloads are saved to `~/Music/Wren` on desktop (falling back to the app state dir when
`~/Music` is not writable) and to `Android/data/com.wren.app/files/Music` on Android. No
extra binary is required — the audio is copied from the same stream URL the player uses.

This project uses YouTube's internal InnerTube API, which is not publicly documented or officially supported for third-party use. It may break without notice if YouTube changes their API structure. No content is redistributed — the app streams directly from YouTube's CDN.
