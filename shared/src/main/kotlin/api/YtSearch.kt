package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import models.SearchResult
import models.Source
import util.Http

private val ytJson = Json { ignoreUnknownKeys = true }

// YT_API_KEY removed — use ApiKeyManager.youtubeKey instead
private const val YT_CLIENT_VERSION = "2.20240101.00.00"

private val ytHeaders = mapOf(
    "Content-Type" to "application/json",
    "X-YouTube-Client-Name" to "1",
    "X-YouTube-Client-Version" to YT_CLIENT_VERSION,
    "Origin" to "https://www.youtube.com",
    "Referer" to "https://www.youtube.com/",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
)

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

    val response = Http.post(
        "https://www.youtube.com/youtubei/v1/search?key=${ApiKeyManager.youtubeKey}&prettyPrint=false",
        body,
        headers = ytHeaders,
    )

    parseYtResults(response.body, limit)
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
