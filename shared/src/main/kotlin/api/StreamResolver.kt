package api

import java.util.concurrent.ConcurrentHashMap

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

suspend fun resolveStreamUrl(trackKey: String): String? {
    val cached = urlCache[trackKey]
    if (cached != null && System.currentTimeMillis() - cached.fetchedAt < cached.ttlMs) {
        return cached.url
    }
    return Streams.resolver.resolve(trackKey)?.also {
        val ttl = if (isSoundCloud(trackKey)) SOUNDCLOUD_TTL_MS else YOUTUBE_TTL_MS
        urlCache[trackKey] = CachedUrl(it, System.currentTimeMillis(), ttl)
    }
}
