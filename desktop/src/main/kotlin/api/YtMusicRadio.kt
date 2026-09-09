package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import models.SearchResult
import models.Source
import util.Log
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val radioClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
private val radioJson = Json { ignoreUnknownKeys = true }
private const val RADIO_CLIENT_VERSION = "1.20240101.01.00"

/**
 * YouTube Music per-track radio: the `next` endpoint with the `RDAMVM<videoId>` mix
 * playlist returns the same queue the web player builds for "Start radio".
 * Works unauthenticated; the seed is the first item.
 */
suspend fun youtubeRadio(videoId: String, limit: Int = 50): List<SearchResult> = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", RADIO_CLIENT_VERSION)
                put("hl", "en")
            }
        }
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    }.toString()

    val req = HttpRequest.newBuilder(URI.create("https://music.youtube.com/youtubei/v1/next?prettyPrint=false"))
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "67")
        .header("X-YouTube-Client-Version", RADIO_CLIENT_VERSION)
        .header("Origin", "https://music.youtube.com")
        .header("Referer", "https://music.youtube.com/")
        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
        .timeout(Duration.ofSeconds(12))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    runCatching {
        val resp = radioClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            Log.w("YtMusicRadio", "next returned ${resp.statusCode()} for $videoId")
            return@runCatching emptyList()
        }
        parseRadio(resp.body(), limit)
    }.onFailure { Log.w("YtMusicRadio", "radio failed for $videoId", it) }.getOrDefault(emptyList())
}

internal fun parseRadio(body: String, limit: Int): List<SearchResult> {
    val root = runCatching { radioJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
    val out = mutableListOf<SearchResult>()
    collectRenderers(root, out, limit)
    return out.distinctBy { it.videoId }
}

/** Walks the response for every `playlistPanelVideoRenderer`, wherever the layout nests it. */
private fun collectRenderers(el: JsonElement, out: MutableList<SearchResult>, limit: Int) {
    if (out.size >= limit) return
    when (el) {
        is JsonObject -> {
            el["playlistPanelVideoRenderer"]?.jsonObject?.let { r -> parseRadioItem(r)?.let(out::add) }
            el.values.forEach { collectRenderers(it, out, limit) }
        }
        is JsonArray -> el.forEach { collectRenderers(it, out, limit) }
        else -> {}
    }
}

private fun parseRadioItem(r: JsonObject): SearchResult? {
    val videoId = r["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
    val title = r["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return null
    val byline = r["longBylineText"]?.jsonObject?.get("runs")?.jsonArray
    val artistRun = byline?.firstOrNull()?.jsonObject
    val artist = artistRun?.get("text")?.jsonPrimitive?.contentOrNull ?: "Unknown"
    val artistId = artistRun?.get("navigationEndpoint")?.jsonObject
        ?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.contentOrNull
    val duration = r["lengthText"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
    val views = byline?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        ?.firstOrNull { it.endsWith(" views") }?.let(::parseCompactCount)
    val thumb = r["thumbnail"]?.jsonObject?.get("thumbnails")?.jsonArray
        ?.maxByOrNull { it.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 0 }
        ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    return SearchResult(videoId, title, artist, artistId, duration, thumb, Source.YT_MUSIC, viewCount = views)
}

/** "1.8B views" → 1_800_000_000; "205M views" → 205_000_000. */
internal fun parseCompactCount(text: String): Long? {
    val m = Regex("""([\d.,]+)\s*([KMB])?""").find(text) ?: return null
    val num = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
    val mult = when (m.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
    return (num * mult).toLong()
}
