package api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import models.ArtistResult
import models.SearchResult
import models.ShelfCard
import util.Log

private const val TAG = "YtMusicPersonal"

/** How many followed artists are probed for a release before Home settles for what it has. */
private const val RELEASE_PROBES = 24

/** One browse per artist, six in flight: more trips the endpoint's throttling. */
private const val RELEASE_CONCURRENCY = 6

/** How many radios Quick picks interleaves. Each one is a `next` call. */
private const val QUICK_PICKS_SEEDS = 3

/**
 * The followed artists worth asking for a release, most-played first. An artist the user actually
 * plays should win the [RELEASE_PROBES] budget over one that was merely subscribed to once; a
 * name with no plays keeps its library order, since a stable tie is better than a random one.
 */
internal fun rankFollowedArtists(
    followed: List<ArtistResult>,
    playCounts: Map<String, Int>,
): List<ArtistResult> = followed.sortedByDescending { playCounts[it.name.lowercase()] ?: 0 }

/** Case-insensitive play tally of [records] by artist, which is all the ranking needs. */
internal fun playCountsOf(records: List<PlayRecord>): Map<String, Int> =
    records.mapNotNull { it.artist.takeIf(String::isNotBlank) }
        .groupingBy { it.lowercase() }
        .eachCount()

/**
 * Release cards for the followed artists, one per artist, in the ranked order. A channel that is
 * not a music artist has no release section, so it drops out on its own rather than being filtered
 * out with a second browse.
 */
suspend fun newReleasesFrom(followed: List<ArtistResult>, limit: Int = 12): List<ShelfCard> =
    coroutineScope {
        val gate = Semaphore(RELEASE_CONCURRENCY)
        followed.take(RELEASE_PROBES)
            .map { artist -> async { gate.withPermit { latestRelease(artist) } } }
            .awaitAll()
            .filterNotNull()
            .distinctBy { it.id }
            .take(limit)
    }

/** The newest card of an artist's first release section — albums before singles, as YT Music lists them. */
private suspend fun latestRelease(artist: ArtistResult): ShelfCard? =
    runCatching { fetchArtistPage(artist.browseId) }
        .onFailure { Log.w(TAG, "artist page failed for ${artist.browseId}", it) }
        .getOrNull()
        ?.releaseSections?.firstOrNull()?.second?.firstOrNull()
        ?.let { card ->
            ShelfCard(
                id = card.browseId,
                title = card.title,
                subtitle = card.subtitle.ifBlank { artist.name },
                artworkUrl = card.thumbnailUrl,
            )
        }

/**
 * A mix of the radios for [seeds], interleaved so no one seed takes over the shelf. Each seed is a
 * track the user played here, which is the only personal signal YouTube will accept without a
 * browser session.
 */
suspend fun quickPicksFrom(seeds: List<String>, limit: Int = 20): List<SearchResult> =
    coroutineScope {
        seeds.distinct().take(QUICK_PICKS_SEEDS)
            .map { seed -> async { youtubeRadio(seed, limit) } }
            .awaitAll()
            .let { lists -> roundRobin(lists).distinctBy { it.videoId }.take(limit) }
    }

/** Round-robin takes one item from each list in turn, so equal shares land up front. */
internal fun <T> roundRobin(lists: List<List<T>>): List<T> {
    val out = mutableListOf<T>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) {
        for (list in lists) if (i < list.size) out += list[i]
    }
    return out
}
