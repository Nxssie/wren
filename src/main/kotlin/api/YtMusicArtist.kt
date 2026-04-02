package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class AlbumCard(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?
)

data class ArtistData(
    val name: String,
    val thumbnailUrl: String?,
    val topSongs: List<SearchResult>,
    val releaseSections: List<Pair<String, List<AlbumCard>>>
)

private val artistHttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

private const val ARTIST_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
private const val ARTIST_CLIENT_VERSION = "1.20220918.01.00"

private fun browseRequest(browseId: String): HttpRequest {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", ARTIST_CLIENT_VERSION)
                put("hl", "en")
            }
        }
        put("browseId", browseId)
    }.toString()

    return HttpRequest.newBuilder()
        .uri(URI.create("https://music.youtube.com/youtubei/v1/browse?key=$ARTIST_API_KEY&prettyPrint=false"))
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "67")
        .header("X-YouTube-Client-Version", ARTIST_CLIENT_VERSION)
        .header("Origin", "https://music.youtube.com")
        .header("Referer", "https://music.youtube.com/")
        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
}

suspend fun fetchArtistPage(browseId: String): ArtistData? = withContext(Dispatchers.IO) {
    val body = artistHttpClient.send(browseRequest(browseId), HttpResponse.BodyHandlers.ofString()).body()
    parseArtistPage(body)
}

suspend fun fetchAlbumTracks(album: AlbumCard): List<SearchResult> = withContext(Dispatchers.IO) {
    val body = artistHttpClient.send(browseRequest(album.browseId), HttpResponse.BodyHandlers.ofString()).body()
    parseAlbumTracks(body, album)
}

// ── Parsers ──────────────────────────────────────────────────────────────────

private fun parseArtistPage(body: String): ArtistData? {
    val root = runCatching {
        Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
    }.getOrNull() ?: return null

    val header = root["header"]?.jsonObject
    val activeHeader = header?.get("musicImmersiveHeaderRenderer")?.jsonObject
        ?: header?.get("musicVisualHeaderRenderer")?.jsonObject
        ?: return null

    val name = activeHeader["title"]?.jsonObject?.get("runs")?.jsonArray
        ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return null

    val thumbnailUrl = activeHeader.digA("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
        ?: activeHeader.digA("foregroundThumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
            ?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content

    val sections = root.digA("contents", "singleColumnBrowseResultsRenderer", "tabs")
        ?.jsonArray?.firstOrNull()
        ?.digA("tabRenderer", "content", "sectionListRenderer", "contents")
        ?.jsonArray ?: return ArtistData(name, thumbnailUrl, emptyList(), emptyList())

    val topSongs = mutableListOf<SearchResult>()
    val releaseSections = mutableListOf<Pair<String, List<AlbumCard>>>()

    for (section in sections) {
        val obj = section.jsonObject

        // Songs shelf
        obj["musicShelfRenderer"]?.jsonObject?.let { shelf ->
            for (item in shelf["contents"]?.jsonArray ?: return@let) {
                val r = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: continue
                val videoId = r.digA("playlistItemData", "videoId")?.jsonPrimitive?.content ?: continue
                val title = r.digRunsA("flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer") ?: continue
                val duration = r["fixedColumns"]?.jsonArray?.firstOrNull()
                    ?.digA("musicResponsiveListItemFixedColumnRenderer", "text", "runs")
                    ?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                val thumbUrl = r.digA("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
                    ?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                topSongs.add(SearchResult(videoId, title, name, null, duration, thumbUrl, Source.YT_MUSIC))
            }
        }

        // Release carousels (Albums, Singles, EPs…)
        obj["musicCarouselShelfRenderer"]?.jsonObject?.let { carousel ->
            val sectionTitle = carousel.digA(
                "header", "musicCarouselShelfBasicHeaderRenderer", "title", "runs"
            )?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return@let

            // Skip non-release sections (Videos, Featured on, etc.)
            val isRelease = sectionTitle.contains("album", ignoreCase = true)
                || sectionTitle.contains("single", ignoreCase = true)
                || sectionTitle.contains("ep", ignoreCase = true)
                || sectionTitle.contains("sencillo", ignoreCase = true)
            if (!isRelease) return@let

            val cards = mutableListOf<AlbumCard>()
            for (item in carousel["contents"]?.jsonArray ?: return@let) {
                val card = item.jsonObject["musicTwoRowItemRenderer"]?.jsonObject ?: continue
                val cardBrowseId = card.digA("navigationEndpoint", "browseEndpoint", "browseId")
                    ?.jsonPrimitive?.content ?: continue
                val cardTitle = card.digA("title", "runs")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: continue
                val subtitle = card["subtitle"]?.jsonObject?.get("runs")?.jsonArray
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" } ?: ""
                val thumbUrl = card.digA(
                    "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"
                )?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                cards.add(AlbumCard(cardBrowseId, cardTitle, subtitle, thumbUrl))
            }
            if (cards.isNotEmpty()) releaseSections.add(sectionTitle to cards)
        }
    }

    return ArtistData(name, thumbnailUrl, topSongs, releaseSections)
}

private fun parseAlbumTracks(body: String, album: AlbumCard): List<SearchResult> {
    val root = runCatching {
        Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
    }.getOrNull() ?: return emptyList()

    // Collect every musicResponsiveListItemRenderer anywhere in the tree
    val renderers = mutableListOf<JsonObject>()
    root.collectRenderers("musicResponsiveListItemRenderer", renderers)

    val tracks = mutableListOf<SearchResult>()
    for (r in renderers) {
        val videoId = r.digA("playlistItemData", "videoId")?.jsonPrimitive?.content
            ?: r.digA(
                "overlay", "musicItemThumbnailOverlayRenderer", "content",
                "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId"
            )?.jsonPrimitive?.content
            ?: r.digA(
                "flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer",
                "text", "runs", 0, "navigationEndpoint", "watchEndpoint", "videoId"
            )?.jsonPrimitive?.content
            ?: continue

        val title = r.digRunsA("flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer") ?: continue
        val duration = r["fixedColumns"]?.jsonArray?.firstOrNull()
            ?.digA("musicResponsiveListItemFixedColumnRenderer", "text", "runs")
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        val thumbUrl = r.digA("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
            ?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: album.thumbnailUrl
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

        tracks.add(SearchResult(videoId, title, album.title, null, duration, thumbUrl, Source.YT_MUSIC))
    }
    return tracks
}

/** Recursively collects all objects stored under [key] anywhere in the tree. */
private fun JsonElement.collectRenderers(key: String, out: MutableList<JsonObject>) {
    when (this) {
        is JsonObject -> {
            val hit = this[key]
            if (hit is JsonObject) out.add(hit)
            for (v in values) v.collectRenderers(key, out)
        }
        is JsonArray -> forEach { it.collectRenderers(key, out) }
        else -> {}
    }
}

// ── JSON helpers ─────────────────────────────────────────────────────────────

private fun JsonElement.digA(vararg keys: Any): JsonElement? {
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

private fun JsonObject.digRunsA(arrayKey: String, index: Int, rendererKey: String): String? =
    this[arrayKey]?.jsonArray?.getOrNull(index)
        ?.jsonObject?.get(rendererKey)?.jsonObject
        ?.digA("text", "runs")?.jsonArray
        ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
