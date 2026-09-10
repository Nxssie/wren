package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import util.Http
import util.Log
import java.net.URLEncoder

/**
 * Resolves streams over plain HTTP, for platforms that cannot spawn yt-dlp (Android).
 *
 * YouTube goes through InnerTube's `player` endpoint with the clients that still hand out
 * plain URLs (no signature cipher, no proof-of-origin token): VISIONOS first, the same
 * default yt-dlp uses, then ANDROID_VR and IOS. Every call carries a visitor id fetched
 * from the YouTube homepage — without it the request is answered with "sign in to confirm
 * you're not a bot" for a good share of music videos. SoundCloud uses the public
 * progressive transcoding of a track.
 */
class HttpStreamResolver : StreamResolver {

    override suspend fun resolve(trackKey: String): String? =
        if (isSoundCloud(trackKey)) soundCloudProgressive(trackKey) else youTubeAudio(trackKey)

    /**
     * Tries each InnerTube client in order and keeps the first one that hands back a plain
     * audio URL. A client that works from one network can be told "sign in to confirm you're
     * not a bot" from another (typical on mobile carriers), so a single client is not enough.
     */
    private suspend fun youTubeAudio(videoId: String): String? = withContext(Dispatchers.IO) {
        for (client in YOUTUBE_CLIENTS) {
            val url = runCatching { playerRequest(client, videoId) }
                .onFailure { Log.e("HttpStreamResolver", "${client.name} request failed for $videoId", it) }
                .getOrNull()
            if (url != null) return@withContext url
        }
        Log.w("HttpStreamResolver", "no client could resolve $videoId")
        null
    }

    private fun playerRequest(client: InnerTubeClient, videoId: String): String? {
        val visitorData = VisitorData.current()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("userAgent", client.userAgent)
                    if (visitorData != null) put("visitorData", visitorData)
                    client.fields.forEach { (key, value) ->
                        when (value) {
                            is Int -> put(key, value)
                            else -> put(key, value.toString())
                        }
                    }
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()

        val response = Http.post(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            body,
            headers = buildMap {
                put("X-YouTube-Client-Name", client.id.toString())
                put("X-YouTube-Client-Version", client.version)
                put("User-Agent", client.userAgent)
                if (visitorData != null) put("X-Goog-Visitor-Id", visitorData)
            },
        )
        if (!response.isSuccessful) {
            Log.w("HttpStreamResolver", "${client.name} player returned ${response.code} for $videoId")
            return null
        }
        val streamUrl = parsePlayerResponse(response.body)
        if (streamUrl == null) {
            Log.w("HttpStreamResolver", "${client.name}: no audio format for $videoId — ${playabilitySummary(response.body)}")
            return null
        }
        if (!isFetchable(streamUrl, client)) return null
        StreamRequestHeaders.remember(streamUrl, client.userAgent)
        Log.d("HttpStreamResolver", "${client.name} resolved $videoId (bound to ip=${boundIp(streamUrl)})")
        return streamUrl
    }

    /**
     * googlevideo hands out URLs that later 403 and the player only finds out after we have
     * committed to the track. Without a proof-of-origin token some clients get a "teaser"
     * URL that serves the first megabyte and rejects everything after it, so the probe
     * asks for one byte *past* that boundary: 2xx means the file is really ours, 416 means
     * the file is shorter than the boundary (also fine), anything else falls through to the
     * next client.
     */
    private fun isFetchable(url: String, client: InnerTubeClient): Boolean {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", client.userAgent)
            .header("Range", "bytes=$TEASER_BOUNDARY-$TEASER_BOUNDARY")
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                val ok = response.code in 200..299 || response.code == 416
                if (!ok) Log.w("HttpStreamResolver", "${client.name}: stream URL probe returned ${response.code} (ip=${boundIp(url)})")
                ok
            }
        }.onFailure { Log.w("HttpStreamResolver", "${client.name}: stream URL probe failed: ${it.message}") }
            .getOrDefault(false)
    }

    private fun boundIp(url: String): String =
        Regex("[?&]ip=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "?"

    private suspend fun soundCloudProgressive(permalink: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val clientId = scClientId()
            val resolved = Http.get(
                "https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(permalink, "UTF-8")}&client_id=$clientId",
                headers = mapOf("User-Agent" to SC_UA),
            )
            if (!resolved.isSuccessful) return@runCatching null
            val track = SC_JSON.parseToJsonElement(resolved.body).jsonObject
            val transcodings = track["media"]?.jsonObject?.get("transcodings")?.jsonArray ?: return@runCatching null
            val transcodeUrl = transcodings.firstNotNullOfOrNull { t ->
                val obj = t.jsonObject
                if (obj["format"]?.jsonObject?.get("protocol")?.jsonPrimitive?.content != "progressive") return@firstNotNullOfOrNull null
                obj["url"]?.jsonPrimitive?.content
            } ?: return@runCatching null

            val stream = Http.get("$transcodeUrl?client_id=$clientId", headers = mapOf("User-Agent" to SC_UA))
            if (!stream.isSuccessful) return@runCatching null
            SC_JSON.parseToJsonElement(stream.body).jsonObject["url"]?.jsonPrimitive?.content
        }.onFailure { Log.e("HttpStreamResolver", "SoundCloud stream failed for $permalink", it) }.getOrNull()
    }

    private data class InnerTubeClient(
        val name: String,
        val id: Int,
        val version: String,
        val userAgent: String,
        val fields: Map<String, Any>,
    )

    private companion object {
        /** Teaser URLs stop serving at 1 MiB; see [isFetchable]. */
        const val TEASER_BOUNDARY = 1_048_576L

        /**
         * Ordered by how often each one returns cipher-free, full-length URLs; the first
         * hit wins. Versions and user agents track yt-dlp's `_base.py` — bump them together.
         */
        val YOUTUBE_CLIENTS = listOf(
            InnerTubeClient(
                name = "VISIONOS", id = 101, version = "1.02",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                fields = mapOf(
                    "deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1",
                    "osName" to "visionOS", "osVersion" to "26.5.23O471",
                ),
            ),
            InnerTubeClient(
                name = "ANDROID_VR", id = 28, version = "1.65.10",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                fields = mapOf(
                    "deviceMake" to "Oculus", "deviceModel" to "Quest 3",
                    "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L",
                ),
            ),
            InnerTubeClient(
                name = "IOS", id = 5, version = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                fields = mapOf(
                    "deviceMake" to "Apple", "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone", "osVersion" to "18.3.2.22D82",
                ),
            ),
        )
        const val SC_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        val SC_JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * YouTube's anonymous visitor id. InnerTube treats a `player` call without one as a bot for
 * many music videos ("Sign in to confirm you're not a bot"), so it is scraped from the
 * homepage `ytcfg` once and reused for a while; a failed fetch just means anonymous calls.
 */
internal object VisitorData {
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private val pattern = Regex(""""VISITOR_DATA":"([^"]+)"""")
    private const val BROWSER_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    @Volatile private var cached: String? = null
    @Volatile private var fetchedAt = 0L

    fun current(): String? {
        cached?.takeIf { System.currentTimeMillis() - fetchedAt < TTL_MS }?.let { return it }
        return synchronized(this) {
            cached?.takeIf { System.currentTimeMillis() - fetchedAt < TTL_MS } ?: fetch()?.also {
                cached = it
                fetchedAt = System.currentTimeMillis()
            }
        }
    }

    private fun fetch(): String? = runCatching {
        val response = Http.get("https://www.youtube.com/", headers = mapOf("User-Agent" to BROWSER_UA))
        if (!response.isSuccessful) return@runCatching null
        pattern.find(response.body)?.groupValues?.get(1)
    }.onFailure { Log.w("VisitorData", "fetch failed: ${it.message}") }.getOrNull()
        .also { if (it == null) Log.w("VisitorData", "no visitor id — player calls go anonymous") }
}

/**
 * googlevideo checks that the media request looks like the client that asked for the URL:
 * an iOS-issued URL fetched with an Android user agent is a 403 on some networks. The
 * resolver records the user agent per URL so the player can replay it.
 */
object StreamRequestHeaders {
    private const val MAX_ENTRIES = 256
    private val userAgents = object : LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_ENTRIES
    }

    @Synchronized
    fun remember(url: String, userAgent: String) { userAgents[url] = userAgent }

    @Synchronized
    fun userAgentFor(url: String): String? = userAgents[url]
}

/**
 * Picks the best audio-only format: `audio/mp4` first (m4a plays everywhere), highest
 * bitrate, and only formats that came back with a plain URL.
 */
internal fun parsePlayerResponse(body: String): String? {
    val root = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return null
    val streaming = root["streamingData"]?.jsonObject ?: return null

    fun bestUrl(formats: JsonArray?, matches: (String) -> Boolean): String? =
        formats
            ?.mapNotNull { it as? JsonObject }
            ?.filter { obj ->
                val mime = obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: return@filter false
                obj["url"] != null && matches(mime)
            }
            ?.maxByOrNull { it["bitrate"]?.jsonPrimitive?.longOrNull ?: 0L }
            ?.get("url")?.jsonPrimitive?.content

    val adaptive = streaming["adaptiveFormats"] as? JsonArray
    return bestUrl(adaptive) { it.startsWith("audio/mp4") }
        ?: bestUrl(adaptive) { it.startsWith("audio/") }
        ?: bestUrl(streaming["formats"] as? JsonArray) { it.startsWith("audio/") || it.contains("mp4a") }
}

/** Short, log-safe reason taken from `playabilityStatus` when no format could be used. */
internal fun playabilitySummary(body: String): String {
    val status = runCatching {
        Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject["playabilityStatus"]?.jsonObject
    }.getOrNull() ?: return "unparseable response"
    val state = status["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    val reason = status["reason"]?.jsonPrimitive?.contentOrNull
    return "playability=$state reason=$reason bytes=${body.length}"
}
