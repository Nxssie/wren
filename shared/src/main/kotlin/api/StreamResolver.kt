package api

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * The platform serves this track only behind content protection (SoundCloud's encrypted HLS on
 * monetised tracks). Not a fetch that went wrong: retrying will not help, and the user should be
 * told why the track was skipped.
 */
class ProtectedStreamException(trackKey: String) : Exception("protected stream: $trackKey")

/** Resolves a playable URL for a track key — a bare YouTube video id or a SoundCloud permalink. */
interface StreamResolver {
    suspend fun resolve(trackKey: String): String?
}

/**
 * Active resolver, picked per platform at startup: desktop keeps yt-dlp (it deciphers
 * YouTube signatures reliably), Android uses [HttpStreamResolver] since it cannot spawn
 * processes.
 */
object Streams {
    @Volatile
    var resolver: StreamResolver = YtDlpResolver
}

private data class CachedUrl(val url: String, val fetchedAt: Long, val ttlMs: Long)

private val urlCache = ConcurrentHashMap<String, CachedUrl>()
private const val YOUTUBE_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours
private const val SOUNDCLOUD_TTL_MS = 20 * 60 * 1000L  // 20 min — SC progressive URLs expire sooner

internal fun isSoundCloud(key: String): Boolean = key.startsWith("http") && "soundcloud.com" in key

// Call at app start to pre-establish TCP+TLS so the first real request doesn't pay connection cost.
fun warmupStreamConnection() {
    Thread {
        runCatching { util.Http.get("https://www.youtube.com/") }
    }.also { it.isDaemon = true }.start()
}

/**
 * Drops the cached URL for [trackKey], so the next resolve goes to the network. For after a
 * playback error: YouTube URLs are bound to the IP that asked for them and stop working when the
 * phone changes network, well before their TTL runs out.
 */
fun forgetStreamUrl(trackKey: String) {
    urlCache.remove(trackKey)
}

private val _protectedStreams = MutableStateFlow<Set<String>>(emptySet())

/**
 * Track keys found to be served only behind content protection, for lists to mark and refuse
 * before a tap. Filled as tracks are resolved (the lists prefetch their first rows), so a row can
 * flip after it appears.
 */
val protectedStreams: StateFlow<Set<String>> = _protectedStreams.asStateFlow()

/** Whether the last resolve found [trackKey] to be served only behind content protection. */
fun isProtectedStream(trackKey: String): Boolean = trackKey in _protectedStreams.value

/** Reads the persisted set; call once after [util.AppDirs] is initialised, before any listing loads. */
fun loadProtectedStreams() {
    val stored = ProtectedStreamStore.load()
    if (stored.isNotEmpty()) _protectedStreams.update { it + stored }
}

/** Records [trackKey] as protected; called by the resolver and by parsers that can see the renditions. */
fun markProtectedStream(trackKey: String) {
    if (trackKey in _protectedStreams.value) return
    val updated = _protectedStreams.updateAndGet { it + trackKey }
    // Off the caller's thread: parsers run wherever the listing was fetched, sometimes on the UI.
    Thread { ProtectedStreamStore.save(updated) }.also { it.isDaemon = true }.start()
}

/**
 * Whether a SoundCloud track's transcodings leave nothing Wren will play. A track that lists any
 * encrypted rendition still lists the plain MP3 ones, but those answer 404 (checked across
 * ad-supported, Blackbox and open tracks alike), so the encrypted entry alone decides it, from
 * the listing, before any media call.
 */
fun soundCloudProtected(protocols: List<String>): Boolean = protocols.any { "encrypted" in it }

/**
 * Null when there is nothing to play. A protected track is remembered and answered without a
 * network round-trip from then on; ask [isProtectedStream] to tell that case from an outage.
 */
suspend fun resolveStreamUrl(trackKey: String): String? {
    if (isProtectedStream(trackKey)) return null
    val cached = urlCache[trackKey]
    if (cached != null && System.currentTimeMillis() - cached.fetchedAt < cached.ttlMs) {
        return cached.url
    }
    val resolved = try {
        Streams.resolver.resolve(trackKey)
    } catch (e: ProtectedStreamException) {
        markProtectedStream(trackKey)
        return null
    }
    return resolved?.also {
        val ttl = if (isSoundCloud(trackKey)) SOUNDCLOUD_TTL_MS else YOUTUBE_TTL_MS
        urlCache[trackKey] = CachedUrl(it, System.currentTimeMillis(), ttl)
    }
}
