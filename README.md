# Wren

A native Linux music player built with Kotlin and Compose Desktop. Searches and streams audio from both YouTube Music and YouTube, with optional Google account integration for playlist access and popularity-based sorting.

## Features

- Search songs across YouTube Music and YouTube simultaneously, results interleaved and sortable by popularity, duration, or source
- Artist pages with top songs, albums, singles, and EPs
- Queue playback with automatic prefetching of upcoming tracks
- Google OAuth login to access your YouTube Music playlists and library
- Popularity-based artist sorting using monthly listener counts parsed directly from the YTMusic API
- Collapsible sidebar, persistent player bar with seek, volume, and queue controls
- Stream URL resolution via the YouTube InnerTube API (no `yt-dlp` dependency)
- 4-hour stream URL cache to avoid redundant requests

## Stack

- **UI**: [Compose Desktop](https://www.jetbrains.com/compose-multiplatform/) (Jetpack Compose for JVM)
- **Language**: Kotlin
- **Player**: [mpv](https://mpv.io/) controlled over a Unix domain socket IPC
- **Concurrency**: Kotlin coroutines (`async`/`coroutineScope` for parallel API calls)
- **Serialization**: `kotlinx.serialization`
- **Auth**: OAuth 2.0 with PKCE-style local redirect, implemented from scratch without third-party auth libraries

## Architecture

```
src/main/kotlin/
├── api/
│   ├── YoutubeMusic.kt       # Public facade — search, artist lookup
│   ├── YtMusicSearch.kt      # InnerTube search parsing (songs + artists)
│   ├── YtMusicArtist.kt      # Artist page + album track parsing
│   ├── YtMusicPlaylists.kt   # YouTube Data API v3 (playlists, view counts)
│   ├── YtMusicStream.kt      # Stream URL resolution + cache
│   └── YtSearch.kt           # YouTube (non-Music) video search
├── auth/
│   ├── AuthManager.kt        # Token lifecycle, yt-dlp cache sync
│   └── OAuthFlow.kt          # Auth URL, local redirect server, token exchange
├── player/
│   └── MpvPlayer.kt          # mpv process + IPC socket + queue
└── ui/
    ├── App.kt                # Window, sidebar, navigation
    ├── SearchScreen.kt       # Search UI, sort dropdown, artist rows
    ├── ArtistScreen.kt       # Artist page UI
    ├── LibraryScreen.kt      # Playlist library
    ├── PlayerBar.kt          # Persistent playback controls
    └── AuthDialog.kt         # OAuth login dialog
```

## Requirements

- JDK 21
- `mpv` installed and available on `$PATH`

## Build

```bash
# Copy and configure the Gradle properties
cp gradle.properties.example gradle.properties
# Edit gradle.properties if you need to point to a specific JDK

./gradlew run
```

### AppImage

```bash
./build-appimage.sh
./Wren.AppImage
```

### Deb / RPM

```bash
./gradlew packageDeb
./gradlew packageRpm
```

## Authentication (optional)

Wren works without a Google account — search and playback are fully available without login.

Logging in unlocks:
- Your YouTube Music playlists and library
- View count data for popularity sorting

To enable login, create an OAuth 2.0 client ID in the [Google Cloud Console](https://console.cloud.google.com/) (Desktop app type, YouTube Data API v3 scope) and place the downloaded `client_secret_*.json` at:

```
~/.config/wren/oauth.json
```

Tokens are stored at `~/.config/wren/tokens.json` and refreshed automatically.

## Notes

This project uses YouTube's internal InnerTube API, which is not publicly documented or officially supported for third-party use. It may break without notice if YouTube changes their API structure. No content is redistributed — the app streams directly from YouTube's CDN.
