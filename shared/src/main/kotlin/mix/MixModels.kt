package mix

import kotlinx.serialization.Serializable
import java.util.Locale
import models.QueueItem
import models.SearchResult
import models.Source

/**
 * One track inside a set. Identity is [id] (`SOURCE:videoId`), so the same track added twice from
 * two different searches is still one row.
 *
 * [bpm] and [camelot] are the analysis the affinity ranking reads. They are nullable on purpose:
 * a track whose tempo or key no provider knows stays in the set and is simply not rankable, rather
 * than being dropped or given a guess. [camelot] is stored as text ("8A") so an unknown provider
 * answer never fails to deserialize.
 */
@Serializable
data class MixTrack(
    val id: String,
    val videoId: String,
    val title: String,
    val artist: String = "",
    val source: Source = Source.YT_MUSIC,
    val artworkUrl: String? = null,
    val duration: String = "",
    val bpm: Double? = null,
    val camelot: String? = null
) {
    val key: CamelotKey? get() = CamelotKey.parse(camelot)

    /** True when this track can take part in an affinity comparison. */
    val isAnalysed: Boolean get() = key != null || (bpm ?: 0.0) > 0.0

    /**
     * What the analysis chip shows. `unknown` means nothing was analysed at all; a `?` on one side
     * means that half was analysed and could not be determined, which is a different fact from "not
     * looked at yet" and must not be shown the same way.
     */
    val analysisLabel: String
        get() {
            val tempo = bpm?.let { value ->
                if (value == value.toLong().toDouble()) value.toLong().toString()
                else String.format(Locale.ROOT, "%.1f", value)
            }
            if (tempo == null && key == null) return "unknown"
            return "${tempo ?: "?"} · ${key?.toString() ?: "?"}"
        }

    val url: String
        get() = when (source) {
            Source.YT_MUSIC -> "https://music.youtube.com/watch?v=$videoId"
            Source.YOUTUBE -> "https://www.youtube.com/watch?v=$videoId"
            Source.SOUNDCLOUD -> videoId
        }

    /** The queue shape, so a set can be played straight from the Mix view. */
    fun toQueueItem(): QueueItem = QueueItem(
        url = url,
        videoId = videoId,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        source = source
    )
}

/**
 * An ordered, hand-built set: the tracks in the order they would be played. Order lives in the
 * list, not in a field on each track, so moving a row is one list operation.
 */
@Serializable
data class MixSet(
    val id: String,
    val name: String,
    val createdAt: Long,
    val tracks: List<MixTrack> = emptyList()
) {
    fun track(trackId: String): MixTrack? = tracks.firstOrNull { it.id == trackId }
}

fun SearchResult.toMixTrack(): MixTrack = MixTrack(
    id = "${source.name}:$videoId",
    videoId = videoId,
    title = title,
    artist = artist,
    source = source,
    artworkUrl = thumbnailUrl.ifBlank { null },
    duration = duration
)
