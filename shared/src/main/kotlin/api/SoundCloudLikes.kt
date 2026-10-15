package api

import auth.SoundCloudAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import util.Log

/**
 * The signed-in user's SoundCloud likes, keyed by track permalink so any item in the app
 * (search result, queue entry, library row) can ask "is this liked?" without an id.
 * Toggling is optimistic: the heart flips at once and rolls back if the API says no.
 */
object SoundCloudLikes {
    private const val TAG = "SoundCloudLikes"

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    /** Permalinks of every liked track; empty when signed out. */
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    /** Set by the provider whose cached library a like invalidates; see SoundCloudProvider. */
    var onChanged: (suspend () -> Unit)? = null

    /**
     * Set by the platform shell to tell the user a write was refused. The heart rolls back on its
     * own, but a heart that flips back with no word is indistinguishable from a missed tap; the
     * argument is whether it was a like (true) or an unlike being refused.
     */
    var onRefused: ((liked: Boolean) -> Unit)? = null

    private val ids = HashMap<String, Long>()
    private val lock = Mutex()
    private var loadedForUser: Long? = null

    /**
     * Writes run here rather than in the caller's scope: a heart is tapped from a screen whose
     * scope dies with it, and a write that dies with the screen leaves the heart flipped with
     * nothing on the server to match — the browser fallback in particular takes seconds.
     */
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** (Re)loads the set for the current session; call on start and whenever auth changes. */
    suspend fun refresh() {
        val userId = SoundCloudAuth.userId
        if (userId == null) {
            lock.withLock { ids.clear(); loadedForUser = null }
            _liked.value = emptySet()
            return
        }
        val fetched = runCatching { SoundCloud.userLikeIds(userId) }
            .onFailure { Log.w("SoundCloudLikes", "could not load likes", it) }
            .getOrNull() ?: return
        lock.withLock {
            ids.clear(); ids.putAll(fetched); loadedForUser = userId
        }
        _liked.value = fetched.keys.toSet()
    }

    fun isLiked(permalink: String): Boolean = permalink in _liked.value

    /**
     * Flips the like on [permalink]. [knownId] saves a resolve round-trip when the caller
     * already has the numeric id (search results do; queue items do not).
     */
    suspend fun toggle(permalink: String, knownId: Long? = null): Boolean {
        if (!SoundCloudAuth.isAuthenticated) return false
        val id = knownId
            ?: lock.withLock { ids[permalink] }
            ?: SoundCloud.resolveTrackId(permalink)
            ?: return false
        val target = !isLiked(permalink)
        _liked.update { if (target) it + permalink else it - permalink }
        // Awaited, so a caller that stays gets the answer; detached, so one that leaves does not
        // cancel the write or skip the rollback.
        return writes.async {
            val ok = SoundCloud.setLiked(id, target)
            if (ok) {
                lock.withLock { if (target) ids[permalink] = id else ids.remove(permalink) }
                // The library lists are cached for minutes; the row that just changed must not
                // wait for their TTL to show up.
                runCatching { onChanged?.invoke() }
                    .onFailure { Log.w(TAG, "could not invalidate the library after a like", it) }
            } else {
                _liked.update { if (target) it - permalink else it + permalink }
                runCatching { onRefused?.invoke(target) }
                    .onFailure { Log.w(TAG, "could not report the refused like", it) }
            }
            ok
        }.await()
    }
}
