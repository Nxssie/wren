package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val ytClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
private val ytJson = Json { ignoreUnknownKeys = true }

private const val YT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
private const val YT_CLIENT_VERSION = "2.20240101.00.00"

suspend fun searchYouTube(query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB")
                put("clientVersion", YT_CLIENT_VERSION)
                put("hl", "en")
            }
        }
        put("query", query)
    }.toString()

    val response = ytClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create("https://www.youtube.com/youtubei/v1/search?key=$YT_API_KEY&prettyPrint=false"))
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", YT_CLIENT_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString()
    )

    parseYtResults(response.body(), limit)
}

private fun parseYtResults(body: String, limit: Int): List<SearchResult> {
    val root = runCatching { ytJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()

    val sections = root
        .digYt("contents", "twoColumnSearchResultsRenderer", "primaryContents",
            "sectionListRenderer", "contents")
        ?.jsonArray ?: return emptyList()

    val results = mutableListOf<SearchResult>()

    for (section in sections) {
        if (results.size >= limit) break
        val items = section.jsonObject["itemSectionRenderer"]
            ?.jsonObject?.get("contents")?.jsonArray ?: continue

        for (item in items) {
            if (results.size >= limit) break
            val v = item.jsonObject["videoRenderer"]?.jsonObject ?: continue

            val videoId = v["videoId"]?.jsonPrimitive?.content ?: continue
            val title = v["title"]?.jsonObject?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: continue
            val channel = v["ownerText"]?.jsonObject?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            val duration = v["lengthText"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.content ?: ""
            val thumbUrl = v["thumbnail"]?.jsonObject?.get("thumbnails")?.jsonArray
                ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

            results.add(SearchResult(videoId, title, channel, null, duration, thumbUrl, Source.YOUTUBE))
        }
    }

    return results
}

private fun JsonElement.digYt(vararg keys: String): JsonElement? {
    var cur: JsonElement = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
    return cur
}
