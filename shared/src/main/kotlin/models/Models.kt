package models

import kotlinx.serialization.Serializable

@Serializable
data class QueueItem(
    val url: String,
    val videoId: String,
    val title: String,
    val artist: String = "",
    val artworkUrl: String? = null,
    val source: Source = Source.YT_MUSIC,
    val genre: String? = null
)

enum class RepeatMode { OFF, ALL, SINGLE }

enum class Source { YT_MUSIC, YOUTUBE, SOUNDCLOUD }

@Serializable
data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val duration: String = "",
    val thumbnailUrl: String = "",
    val source: Source = Source.YT_MUSIC,
    val viewCount: Long? = null,
    val soundcloudId: Long? = null,
    val genre: String? = null
) {
    val url: String get() = when (source) {
        Source.YT_MUSIC    -> "https://music.youtube.com/watch?v=$videoId"
        Source.YOUTUBE     -> "https://www.youtube.com/watch?v=$videoId"
        Source.SOUNDCLOUD  -> videoId
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
    val thumbnailUrl: String,
    /** Channel name when the playlist belongs to someone else but is saved by the user. */
    val owner: String? = null
)

data class PlaylistTrack(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String,
    val duration: String = "",
    val source: Source = Source.YT_MUSIC
) {
    /** SoundCloud ids are already permalinks (see SearchResult.url). */
    val url get() = when (source) {
        Source.YT_MUSIC   -> "https://music.youtube.com/watch?v=$videoId"
        Source.YOUTUBE    -> "https://www.youtube.com/watch?v=$videoId"
        Source.SOUNDCLOUD -> videoId
    }
}

fun PlaylistTrack.toQueueItem() = QueueItem(
    url = url,
    videoId = videoId,
    title = title,
    artist = channelTitle,
    artworkUrl = if (source == Source.SOUNDCLOUD) thumbnailUrl else null,
    source = source
)

fun SearchResult.toQueueItem() = QueueItem(
    url = url,
    videoId = videoId,
    title = title,
    artist = artist,
    artworkUrl = if (source == Source.SOUNDCLOUD) thumbnailUrl else null,
    source = source,
    genre = genre
)

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsResult(val lines: List<LyricLine>, val synced: Boolean)

/**
 * What tapping a [ShelfCard] opens. Most cards are a collection of tracks; a mood or genre page is
 * a feed of further shelves, which is why the distinction has to travel with the card.
 */
enum class ShelfCardKind { TRACKS, SHELVES }

/**
 * A card on a feed shelf: a mix, station, album, curated playlist, or a door into another feed.
 * [id] is the provider's opaque handle for `MusicProvider.collectionTracks`/`collectionShelves` —
 * for YouTube Music a browse id, carrying the `params` the endpoint needs when there is one.
 */
data class ShelfCard(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val kind: ShelfCardKind = ShelfCardKind.TRACKS,
    /** Set when the list travels with the shelf and no `collectionTracks` roundtrip is needed. */
    val tracks: List<SearchResult>? = null
)

/** One feed shelf. A flat track list, a row of cards, or both — a screen renders what is non-empty. */
data class Shelf(
    val title: String,
    val caption: String? = null,
    val tracks: List<SearchResult> = emptyList(),
    val cards: List<ShelfCard> = emptyList()
)
