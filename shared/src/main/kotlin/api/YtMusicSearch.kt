package api

import auth.GoogleAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import models.ArtistResult
import models.SearchResult
import models.Source
import util.Http

private val json = Json { ignoreUnknownKeys = true }

// API_KEY removed — use ApiKeyManager.ytMusicKey instead
private const val CLIENT_VERSION = "1.20220918.01.00"

private val musicHeaders = mapOf(
    "Content-Type" to "application/json",
    "X-YouTube-Client-Name" to "67",
    "X-YouTube-Client-Version" to CLIENT_VERSION,
    "Origin" to "https://music.youtube.com",
    "Referer" to "https://music.youtube.com/",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
)

private fun musicSearchUrl() =
    "https://music.youtube.com/youtubei/v1/search?key=${ApiKeyManager.ytMusicKey}&prettyPrint=false"
private const val SONGS_PARAMS   = "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo="
private const val ARTISTS_PARAMS = "Eg-KAQwIBRABGAAgASgAMABqChAEEAMQCRAFEAo="

suspend fun searchYouTubeMusic(query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", "en")
            }
        }
        put("query", query)
        put("params", SONGS_PARAMS)
    }.toString()

    // OAuth token not sent — InnerTube rejects tokens from unrecognized clients
    val response = Http.post(musicSearchUrl(), body, headers = musicHeaders)
    parseResults(response.body, limit)
}

private fun parseResults(body: String, limit: Int): List<SearchResult> {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()

    val tabs = root.dig("contents", "tabbedSearchResultsRenderer", "tabs")?.jsonArray ?: return emptyList()
    val sections = tabs.firstOrNull()
        ?.dig("tabRenderer", "content", "sectionListRenderer", "contents")
        ?.jsonArray ?: return emptyList()

    val results = mutableListOf<SearchResult>()

    for (section in sections) {
        if (results.size >= limit) break
        val shelf = section.jsonObject["musicShelfRenderer"]?.jsonObject ?: continue
        for (item in shelf["contents"]?.jsonArray ?: continue) {
            if (results.size >= limit) break
            val r = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: continue

            val videoId = r.dig("playlistItemData", "videoId")?.jsonPrimitive?.content ?: continue
            val title = r.digRuns("flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer") ?: continue

            val artist = r.digRuns("flexColumns", 1, "musicResponsiveListItemFlexColumnRenderer") ?: "Unknown"
            val artistId = r["flexColumns"]?.jsonArray?.getOrNull(1)
                ?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                ?.dig("text", "runs")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.dig("navigationEndpoint", "browseEndpoint", "browseId")
                ?.jsonPrimitive?.content
            val duration = r["flexColumns"]?.jsonArray?.getOrNull(1)
                ?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                ?.dig("text", "runs")?.jsonArray
                ?.lastOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?.takeIf { ':' in it } ?: ""
            val thumbUrl = r.dig("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                ?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

            results.add(SearchResult(videoId, title, artist, artistId, duration, thumbUrl, Source.YT_MUSIC))
        }
    }

    return results
}

suspend fun searchYouTubeMusicArtists(query: String): List<ArtistResult> = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", "en")
            }
        }
        put("query", query)
        put("params", ARTISTS_PARAMS)
    }.toString()

    parseArtistResults(Http.post(musicSearchUrl(), body, headers = musicHeaders).body)
}

suspend fun searchYouTubeMusicArtistsFromGeneral(query: String): List<ArtistResult> = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", CLIENT_VERSION)
                put("hl", "en")
            }
        }
        put("query", query)
    }.toString()

    parseArtistsFromGeneralSearch(Http.post(musicSearchUrl(), body, headers = musicHeaders).body)
}

private fun parseArtistsFromGeneralSearch(body: String): List<ArtistResult> {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
    val tabs = root.dig("contents", "tabbedSearchResultsRenderer", "tabs")?.jsonArray ?: return emptyList()
    val sections = tabs.firstOrNull()
        ?.dig("tabRenderer", "content", "sectionListRenderer", "contents")
        ?.jsonArray ?: return emptyList()

    val results = mutableListOf<ArtistResult>()
    for (section in sections) {
        // Top result card — often the most prominent artist
        section.jsonObject["musicCardShelfRenderer"]?.jsonObject?.let { card ->
            val browseId = card.dig("title", "runs")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.dig("navigationEndpoint", "browseEndpoint", "browseId")
                ?.jsonPrimitive?.content ?: return@let
            if (!browseId.startsWith("UC")) return@let
            val name = card.dig("title", "runs")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return@let
            val subtitleRuns = card["subtitle"]?.jsonObject?.get("runs")?.jsonArray
            val subtitle = subtitleRuns?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" } ?: ""
            val listeners = subtitleRuns?.getOrNull(2)?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?.let { parseListenerCount(it) }
            val thumbUrl = card.dig("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                ?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            if (results.none { it.browseId == browseId })
                results.add(ArtistResult(browseId, name, thumbUrl, subtitle, listeners))
        }
        // Inline artist entries inside shelves
        section.jsonObject["musicShelfRenderer"]?.jsonObject?.let { shelf ->
            for (item in shelf["contents"]?.jsonArray ?: return@let) {
                val r = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: continue
                val browseId = r.dig("navigationEndpoint", "browseEndpoint", "browseId")
                    ?.jsonPrimitive?.content ?: continue
                if (!browseId.startsWith("UC")) continue
                val name = r.digRuns("flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer") ?: continue
                val subtitleRuns = r["flexColumns"]?.jsonArray?.getOrNull(1)
                    ?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                    ?.dig("text", "runs")?.jsonArray
                val subtitle = subtitleRuns?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" } ?: ""
                val listeners = subtitleRuns?.getOrNull(2)?.jsonObject?.get("text")?.jsonPrimitive?.content
                    ?.let { parseListenerCount(it) }
                val thumbUrl = r.dig("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                    ?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                if (results.none { it.browseId == browseId })
                    results.add(ArtistResult(browseId, name, thumbUrl, subtitle, listeners))
            }
        }
    }
    return results
}

private fun parseArtistResults(body: String): List<ArtistResult> {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
    val tabs = root.dig("contents", "tabbedSearchResultsRenderer", "tabs")?.jsonArray ?: return emptyList()
    val sections = tabs.firstOrNull()
        ?.dig("tabRenderer", "content", "sectionListRenderer", "contents")
        ?.jsonArray ?: return emptyList()

    val results = mutableListOf<ArtistResult>()
    for (section in sections) {
        val shelf = section.jsonObject["musicShelfRenderer"]?.jsonObject ?: continue
        for (item in shelf["contents"]?.jsonArray ?: continue) {
            val r = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: continue
            val browseId = r.dig("navigationEndpoint", "browseEndpoint", "browseId")
                ?.jsonPrimitive?.content ?: continue
            val name = r.digRuns("flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer") ?: continue
            val subtitleRuns = r["flexColumns"]?.jsonArray?.getOrNull(1)
                ?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                ?.dig("text", "runs")?.jsonArray
            val subtitle = subtitleRuns?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" } ?: ""
            val listeners = subtitleRuns?.getOrNull(2)?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?.let { parseListenerCount(it) }
            val thumbUrl = r.dig("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                ?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            results.add(ArtistResult(browseId, name, thumbUrl, subtitle, listeners))
        }
    }
    return results
}

// Parses YTMusic listener/subscriber strings like "750 K usuarios mensuales",
// "4,03 M usuarios mensuales", "945 suscriptores" → Long (or null if unrecognised).
internal fun parseListenerCount(text: String): Long? {
    val normalized = text.trim().replace(",", ".")
    val mMatch = Regex("""^([\d.]+)\s*[Mm]""").find(normalized)
    if (mMatch != null) return (mMatch.groupValues[1].toDoubleOrNull()?.times(1_000_000))?.toLong()
    val kMatch = Regex("""^([\d.]+)\s*[Kk]""").find(normalized)
    if (kMatch != null) return (kMatch.groupValues[1].toDoubleOrNull()?.times(1_000))?.toLong()
    val plain = Regex("""^(\d+)""").find(normalized)
    return plain?.groupValues?.get(1)?.toLongOrNull()
}

private fun JsonElement.dig(vararg keys: String): JsonElement? {
    var cur: JsonElement = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
    return cur
}

private fun JsonElement.dig(vararg keys: Any): JsonElement? {
    var cur: JsonElement = this
    for (k in keys) {
        cur = when (k) {
            is String -> (cur as? JsonObject)?.get(k) ?: return null
            is Int -> (cur as? JsonArray)?.getOrNull(k) ?: return null
            else -> return null
        }
    }
    return cur
}

private fun JsonObject.digRuns(arrayKey: String, index: Int, rendererKey: String): String? =
    this[arrayKey]?.jsonArray?.getOrNull(index)
        ?.jsonObject?.get(rendererKey)?.jsonObject
        ?.dig("text", "runs")?.jsonArray
        ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
