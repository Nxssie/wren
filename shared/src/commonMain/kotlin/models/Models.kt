package models

import kotlinx.serialization.Serializable

@Serializable
data class QueueItem(
    val url: String,
    val videoId: String,
    val title: String,
    val artist: String = ""
)

enum class RepeatMode { OFF, ALL, SINGLE }

enum class Source { YT_MUSIC, YOUTUBE }

data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val duration: String,
    val thumbnailUrl: String,
    val source: Source = Source.YT_MUSIC,
    val viewCount: Long? = null
) {
    val url: String get() = when (source) {
        Source.YT_MUSIC -> "https://music.youtube.com/watch?v=$videoId"
        Source.YOUTUBE  -> "https://www.youtube.com/watch?v=$videoId"
    }
}

data class ArtistResult(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String?,
    val subtitle: String,
    val subscriberCount: Long? = null
)

data class AlbumCard(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?
)

data class ArtistData(
    val name: String,
    val thumbnailUrl: String?,
    val topSongs: List<SearchResult>,
    val releaseSections: List<Pair<String, List<AlbumCard>>>
)

data class Playlist(
    val id: String,
    val title: String,
    val itemCount: Int,
    val thumbnailUrl: String
)

data class PlaylistTrack(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String,
    val duration: String = ""
) {
    val url get() = "https://music.youtube.com/watch?v=$videoId"
}

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsResult(val lines: List<LyricLine>, val synced: Boolean)
