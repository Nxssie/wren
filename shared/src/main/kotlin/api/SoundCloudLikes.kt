package api

import auth.SoundCloudAuth
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
    /**
     * Whether the heart is offered as a control at all. SoundCloud's bot protection refuses
     * writes from anything but its own player — every attempt from a phone ends in a DataDome
     * block (the full retry-through-a-browser-and-captcha path lives on the
     * `feat/soundcloud-like-writes` branch) — and a stream of refused writes carrying a real
     * token is a signal against the account. Until that changes, likes are read, never written.
     */
    const val CAN_TOGGLE: Boolean = false

    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    /** Permalinks of every liked track; empty when signed out. */
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    private val ids = HashMap<String, Long>()
    private val lock = Mutex()
    private var loadedForUser: Long? = null

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
        // The screens hide the control; this is the backstop for any path that still reaches it.
        if (!CAN_TOGGLE || !SoundCloudAuth.isAuthenticated) return false
        val id = knownId
            ?: lock.withLock { ids[permalink] }
            ?: SoundCloud.resolveTrackId(permalink)
            ?: return false
        val target = !isLiked(permalink)
        _liked.update { if (target) it + permalink else it - permalink }
        val ok = SoundCloud.setLiked(id, target)
        if (ok) {
            lock.withLock { if (target) ids[permalink] = id else ids.remove(permalink) }
        } else {
            _liked.update { if (target) it - permalink else it + permalink }
        }
        return ok
    }
}
