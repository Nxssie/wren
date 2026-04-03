package api

import auth.AuthManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class Source { YT_MUSIC, YOUTUBE }

data class ArtistResult(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String?,
    val subtitle: String,
    val subscriberCount: Long? = null
)

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

object YoutubeMusic {
    suspend fun searchArtists(query: String): List<ArtistResult> = coroutineScope {
        if (AuthManager.isAuthenticated) AuthManager.ensureValidToken()
        val filtered = async { searchYouTubeMusicArtists(query) }
        val general = async { searchYouTubeMusicArtistsFromGeneral(query) }
        // Merge: prefer general's entry (has listener count) over filtered's when both exist
        val generalMap = general.await().associateBy { it.browseId }
        val combined = (filtered.await().map { generalMap[it.browseId] ?: it } + general.await())
            .distinctBy { it.browseId }
        combined.sortedByDescending { it.subscriberCount ?: -1L }
    }

    suspend fun search(query: String, limit: Int = 20): List<SearchResult> = coroutineScope {
        if (AuthManager.isAuthenticated) AuthManager.ensureValidToken()
        val music = async { searchYouTubeMusic(query, limit) }
        val video = async { searchYouTube(query, limit) }
        val combined = interleave(music.await(), video.await())
        if (!AuthManager.isAuthenticated) return@coroutineScope combined
        val counts = fetchViewCounts(combined.map { it.videoId })
        combined.map { it.copy(viewCount = counts[it.videoId]) }
    }
}

private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
    val out = mutableListOf<T>()
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        if (i < a.size) out += a[i]
        if (i < b.size) out += b[i]
    }
    return out
}
