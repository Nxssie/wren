package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import models.AlbumCard
import models.ArtistData
import models.SearchResult
import models.Source
import util.Http

// ARTIST_API_KEY removed — use ApiKeyManager.ytMusicKey instead
private const val ARTIST_CLIENT_VERSION = "1.20220918.01.00"

private val artistHeaders = mapOf(
    "Content-Type" to "application/json",
    "X-YouTube-Client-Name" to "67",
    "X-YouTube-Client-Version" to ARTIST_CLIENT_VERSION,
    "Origin" to "https://music.youtube.com",
    "Referer" to "https://music.youtube.com/",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
)

private fun browse(browseId: String): String {
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

    return Http.post(
        "https://music.youtube.com/youtubei/v1/browse?key=${ApiKeyManager.ytMusicKey}&prettyPrint=false",
        body,
        headers = artistHeaders,
    ).body
}

suspend fun fetchArtistPage(browseId: String): ArtistData? = withContext(Dispatchers.IO) {
    parseArtistPage(browse(browseId))
}

/**
 * Whether a UC channel id is a YouTube Music artist. Artists get the immersive header on
 * music.youtube.com; ordinary channels (vloggers, labels' upload channels, podcasts) get
 * the plain visual header, which is what lets the Library skip them.
 */
suspend fun isMusicArtist(browseId: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(browse(browseId)).jsonObject
        root["header"]?.jsonObject?.containsKey("musicImmersiveHeaderRenderer") == true
    }.getOrDefault(false)
}

suspend fun fetchAlbumTracks(album: AlbumCard): List<SearchResult> = withContext(Dispatchers.IO) {
    parseAlbumTracks(browse(album.browseId), album)
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
