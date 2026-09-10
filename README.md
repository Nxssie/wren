<p align="center">
  <img src="desktop/src/main/resources/wren-256.png" alt="Wren logo" width="128" height="128" />
</p>

<h1 align="center">Wren</h1>

<p align="center">
  A native desktop music player built with Kotlin and Compose Desktop.<br/>
  Searches and streams audio from YouTube Music, YouTube, and SoundCloud — no ads, login optional.
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

Wren is split into Gradle modules: `desktop` is the shipping Linux app today, `shared` holds
domain models reused by future targets, and `android` is an early, unfinished scaffold.

```
shared/src/commonMain/kotlin/
└── models/
    └── Models.kt             # Domain models (SearchResult, QueueItem, Playlist, ...)

desktop/src/main/kotlin/
├── api/
│   ├── YoutubeMusic.kt       # Public facade — search, artist lookup
│   ├── YtMusicSearch.kt      # InnerTube search parsing (songs + artists)
│   ├── YtMusicArtist.kt      # Artist page + album track parsing
│   ├── YtMusicPlaylists.kt   # YouTube Data API v3 (playlists, view counts)
│   ├── YtMusicStream.kt      # Stream URL resolution + cache
│   ├── YtSearch.kt           # YouTube (non-Music) video search
│   ├── Lyrics.kt             # Synced/plain lyrics from lrclib.net
│   └── ApiKeyManager.kt      # API key management with config file fallback
├── auth/
│   ├── AuthManager.kt        # Token lifecycle, yt-dlp cache sync
│   └── OAuthFlow.kt          # Auth URL, local redirect server, token exchange
├── player/
│   └── FFmpegPlayer.kt       # in-process FFmpeg decoder + Java Sound API playback
├── ui/
│   ├── App.kt                # Window, sidebar, platform switcher, navigation
│   ├── SearchScreen.kt       # Search UI, sort dropdown, artist rows
│   ├── ArtistScreen.kt       # Artist page UI
│   ├── LibraryScreen.kt      # Playlist library
│   ├── NowPlayingScreen.kt   # Now Playing with lyrics + queue
│   ├── PlayerBar.kt          # Persistent playback controls
│   ├── DiscoverScreen.kt     # Platform-scoped discover: sections, collection cards, inline open
│   ├── ProfileDialog.kt      # Local profile + Google/SoundCloud sessions
│   └── SoundCloudLoginWindow.kt # Embedded WebView sign-in for SoundCloud
├── provider/
│   ├── MusicProvider.kt      # Platform abstraction: search, discover, station, library
│   ├── YouTubeProvider.kt    # YouTube + YouTube Music behind one provider (radio, library)
│   └── SoundCloudProvider.kt # SoundCloud provider (stations, selections, likes, playlists)
└── util/
    └── Log.kt                # File logger (~/.local/state/wren/wren.log) for diagnostics
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

This project uses YouTube's internal InnerTube API, which is not publicly documented or officially supported for third-party use. It may break without notice if YouTube changes their API structure. No content is redistributed — the app streams directly from YouTube's CDN.
