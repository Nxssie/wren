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
 * YouTube goes through InnerTube's `player` endpoint with the ANDROID_VR client: its
 * formats carry ready-to-use URLs (no signature cipher, no PO token), unlike WEB.
 * SoundCloud uses the public progressive transcoding of a track.
 */
class HttpStreamResolver : StreamResolver {

    override suspend fun resolve(trackKey: String): String? =
        if (isSoundCloud(trackKey)) soundCloudProgressive(trackKey) else youTubeAudio(trackKey)

    private suspend fun youTubeAudio(videoId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", VR_CLIENT_NAME)
                        put("clientVersion", VR_CLIENT_VERSION)
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("androidSdkVersion", 32)
                        put("osName", "Android")
                        put("osVersion", "12")
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
                headers = mapOf(
                    "X-YouTube-Client-Name" to VR_CLIENT_ID.toString(),
                    "X-YouTube-Client-Version" to VR_CLIENT_VERSION,
                    "User-Agent" to "com.google.android.apps.youtube.vr.oculus/$VR_CLIENT_VERSION (Linux; U; Android 12; GB) gzip",
                ),
            )
            if (!response.isSuccessful) {
                Log.w("HttpStreamResolver", "player returned ${response.code} for $videoId")
                return@runCatching null
            }
            val streamUrl = parsePlayerResponse(response.body)
            if (streamUrl == null) {
                Log.w("HttpStreamResolver", "no audio format for $videoId — ${playabilitySummary(response.body)}")
            }
            streamUrl
        }.onFailure { Log.e("HttpStreamResolver", "player request failed for $videoId", it) }.getOrNull()
    }

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

    private companion object {
        const val VR_CLIENT_NAME = "ANDROID_VR"
        const val VR_CLIENT_VERSION = "1.62.27"
        const val VR_CLIENT_ID = 28
        const val SC_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        val SC_JSON = Json { ignoreUnknownKeys = true }
    }
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
