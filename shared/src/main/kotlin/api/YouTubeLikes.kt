package api

import auth.GoogleAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import util.Http
import util.Log

/**
 * The signed-in user's YouTube likes, which is what "add to the collection" means on YouTube and
 * YouTube Music: a liked video lands in the "LL" playlist the library's Songs tab lists.
 *
 * The Data API has no bulk "is this liked?" for arbitrary ids short of walking the whole playlist,
 * so ratings are looked up per video as tracks come into view ([ensureKnown]) and remembered.
 * Toggling is optimistic, like [SoundCloudLikes]: the icon flips at once and rolls back on failure.
 */
object YouTubeLikes {
    private const val TAG = "YouTubeLikes"
    private const val API = "https://www.googleapis.com/youtube/v3/videos"

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    /** Video ids known to be liked; only meaningful for ids that have been looked up or toggled. */
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    private val known = HashSet<String>()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun isLiked(videoId: String): Boolean = videoId in _liked.value

    /** Looks the rating up once per video; later calls for the same id are free. */
    suspend fun ensureKnown(videoId: String) {
        if (!GoogleAuth.isAuthenticated) return
        if (lock.withLock { !known.add(videoId) }) return
        val rating = fetchRating(videoId)
        if (rating == null) {
            // Unknown stays unknown, so the next look retries instead of trusting a failed call.
            lock.withLock { known.remove(videoId) }
            return
        }
        _liked.update { if (rating == "like") it + videoId else it - videoId }
    }

    /** Flips the like on [videoId]; returns whether the API accepted it. */
    suspend fun toggle(videoId: String): Boolean {
        if (!GoogleAuth.isAuthenticated) return false
        ensureKnown(videoId)
        val target = !isLiked(videoId)
        _liked.update { if (target) it + videoId else it - videoId }
        val ok = rate(videoId, if (target) "like" else "none")
        if (!ok) _liked.update { if (target) it - videoId else it + videoId }
        return ok
    }

    /** Forgets everything; call when the Google account changes. */
    suspend fun reset() {
        lock.withLock { known.clear() }
        _liked.value = emptySet()
    }

    private suspend fun fetchRating(videoId: String): String? = withContext(Dispatchers.IO) {
        val token = token() ?: return@withContext null
        val response = Http.get("$API/getRating?id=$videoId", headers = mapOf("Authorization" to "Bearer $token"))
        if (!response.isSuccessful) {
            Log.w(TAG, "getRating $videoId failed: ${response.code}")
            return@withContext null
        }
        runCatching {
            json.parseToJsonElement(response.body).jsonObject["items"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("rating")?.jsonPrimitive?.content
        }.onFailure { Log.w(TAG, "getRating $videoId unreadable", it) }.getOrNull()
    }

    private suspend fun rate(videoId: String, rating: String): Boolean = withContext(Dispatchers.IO) {
        val token = token() ?: return@withContext false
        val response = Http.post(
            "$API/rate?id=$videoId&rating=$rating",
            body = "",
            headers = mapOf("Authorization" to "Bearer $token"),
        )
        if (!response.isSuccessful) Log.w(TAG, "rate $videoId=$rating failed: ${response.code} ${response.body.take(200)}")
        response.isSuccessful
    }

    private suspend fun token(): String? {
        GoogleAuth.ensureValidToken()
        return GoogleAuth.accessToken
    }
}
