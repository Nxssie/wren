package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import models.SearchResult
import models.Shelf
import models.ShelfCard
import models.ShelfCardKind
import models.Source
import util.Http
import util.Log

private val browseJson = Json { ignoreUnknownKeys = true }

private const val BROWSE_CLIENT_VERSION = "1.20220918.01.00"

/** A collection walk that keeps finding nothing new stops well before this; the cap is a guard. */
private const val MAX_COLLECTION_PAGES = 20

private val browseHeaders = mapOf(
    "Content-Type" to "application/json",
    "X-YouTube-Client-Name" to "67",
    "X-YouTube-Client-Version" to BROWSE_CLIENT_VERSION,
    "Origin" to "https://music.youtube.com",
    "Referer" to "https://music.youtube.com/",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
)

/**
 * YouTube Music's own browse pages. `FEmusic_home` and `FEmusic_explore` are the app's Home and
 * Explore tabs, and an album or playlist browse id walks the tracks behind a shelf card.
 *
 * The OAuth token is deliberately not sent — InnerTube rejects tokens from clients it does not
 * recognise — so these feeds come back geographic rather than account-personalised.
 */
private fun browse(browseId: String? = null, params: String? = null, continuation: String? = null): JsonObject? {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", BROWSE_CLIENT_VERSION)
                put("hl", "en")
            }
        }
        if (continuation != null) {
            put("continuation", continuation)
        } else {
            put("browseId", browseId.orEmpty())
            params?.let { put("params", it) }
        }
    }.toString()

    val res = Http.post(
        "https://music.youtube.com/youtubei/v1/browse?key=${ApiKeyManager.ytMusicKey}&prettyPrint=false",
        body,
        headers = browseHeaders,
    )
    if (!res.isSuccessful) {
        Log.w("YtMusicBrowse", "browse returned ${res.code} for ${browseId ?: "continuation"}")
        return null
    }
    return runCatching { browseJson.parseToJsonElement(res.body).jsonObject }.getOrNull()
}

suspend fun ytMusicHome(): List<Shelf> = withContext(Dispatchers.IO) { shelves("FEmusic_home") }

suspend fun ytMusicExplore(): List<Shelf> = withContext(Dispatchers.IO) { shelves("FEmusic_explore") }

/** A mood or genre page: the same shelf shape as a feed, one level down. */
suspend fun ytMusicCollectionShelves(collectionId: String): List<Shelf> = withContext(Dispatchers.IO) {
    val (browseId, params) = splitCardId(collectionId)
    shelves(browseId, params)
}

/** The shelves of a browse page, in order, dropping any that turned out to hold nothing. */
private fun shelves(browseId: String, params: String? = null): List<Shelf> {
    val root = browse(browseId = browseId, params = params) ?: return emptyList()
    return parseShelves(root)
}

/** Split from [shelves] so a response shape can be tested without the network. */
internal fun parseShelves(root: JsonObject): List<Shelf> {
    val sections = root
        .at("contents", "singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents")
        ?.jsonArray ?: return emptyList()

    return sections.mapNotNull { section ->
        val carousel = section.jsonObject["musicCarouselShelfRenderer"]?.jsonObject ?: return@mapNotNull null
        val header = carousel.at("header", "musicCarouselShelfBasicHeaderRenderer")
        val title = header.runs("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val caption = header.runs("subtitle")?.takeIf { it.isNotBlank() }

        val tracks = mutableListOf<SearchResult>()
        val cards = mutableListOf<ShelfCard>()
        carousel["contents"]?.jsonArray?.forEach { item ->
            val obj = item.jsonObject
            obj["musicResponsiveListItemRenderer"]?.jsonObject?.let { track(it)?.let(tracks::add) }
            obj["musicTwoRowItemRenderer"]?.jsonObject?.let { card(it)?.let(cards::add) }
            obj["musicNavigationButtonRenderer"]?.jsonObject?.let { button(it)?.let(cards::add) }
        }
        if (tracks.isEmpty() && cards.isEmpty()) null
        else Shelf(title = title.lowercase(), caption = caption, tracks = tracks, cards = cards)
    }
}

/**
 * Every track behind an album or playlist browse id, following continuations so a long playlist
 * is not silently cut short. A collection that fits one page still hands back a continuation for
 * the page's remaining sections; following it adds no tracks, which is how the walk ends.
 */
suspend fun ytMusicCollectionTracks(collectionId: String, limit: Int = 500): List<SearchResult> =
    withContext(Dispatchers.IO) {
        val (browseId, params) = splitCardId(collectionId)
        var root = browse(browseId = browseId, params = params) ?: return@withContext emptyList()

        val tracks = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        var pages = 1
        while (true) {
            val before = tracks.size
            parseCollectionTracks(root).forEach { if (seen.add(it.videoId)) tracks += it }
            if (tracks.size >= limit || pages >= MAX_COLLECTION_PAGES) break
            if (tracks.size == before) break
            val token = continuationTokenOf(root) ?: break
            root = browse(continuation = token) ?: break
            pages++
        }
        tracks.take(limit)
    }

/** A card id travels as `browseId|params` when the endpoint needs params, and bare otherwise. */
private fun splitCardId(collectionId: String): Pair<String, String?> =
    collectionId.split("|", limit = 2).let { it[0] to it.getOrNull(1) }

/** The tracks on one page of a collection; the caller follows the continuation for the rest. */
internal fun parseCollectionTracks(root: JsonObject): List<SearchResult> =
    root.responsiveItems().mapNotNull(::track)

/** The continuation that carries the rest of a collection, when the response offers one. */
internal fun continuationTokenOf(root: JsonObject): String? = root.continuationToken()

private fun track(r: JsonObject): SearchResult? {
    val videoId = r.at("playlistItemData", "videoId")?.jsonPrimitive?.contentOrNull ?: return null
    val title = r.flexColumn(0) ?: return null
    val artistRuns = r.flexColumnRuns(1)
    val artist = artistRuns?.firstOrNull()?.jsonObject?.at("text")?.jsonPrimitive?.contentOrNull ?: "Unknown"
    val artistId = artistRuns?.firstOrNull()?.jsonObject
        ?.at("navigationEndpoint", "browseEndpoint", "browseId")?.jsonPrimitive?.contentOrNull
    val duration = artistRuns?.lastOrNull()?.jsonObject?.at("text")?.jsonPrimitive?.contentOrNull
        ?.takeIf { ':' in it } ?: ""
    val artworkUrl = r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?.jsonArray?.lastOrNull()?.jsonObject?.at("url")?.jsonPrimitive?.contentOrNull
        ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    return SearchResult(videoId, title, artist, artistId, duration, artworkUrl, Source.YT_MUSIC)
}

private fun card(r: JsonObject): ShelfCard? {
    val target = r.at("navigationEndpoint", "browseEndpoint") ?: r.at("clickCommand", "browseEndpoint")
    val id = target?.browseTarget() ?: return null
    val title = r.runs("title")?.takeIf { it.isNotBlank() } ?: return null
    val artworkUrl = r.at("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?.jsonArray?.lastOrNull()?.jsonObject?.at("url")?.jsonPrimitive?.contentOrNull

    return ShelfCard(id, title, r.runs("subtitle")?.takeIf { it.isNotBlank() }, artworkUrl)
}

/**
 * Moods and genres are buttons rather than cards, and carry only a label — and they open a page of
 * playlists, not tracks, which is what [ShelfCardKind.SHELVES] is for.
 */
private fun button(r: JsonObject): ShelfCard? {
    val target = r.at("clickCommand", "browseEndpoint") ?: return null
    val id = target.browseTarget() ?: return null
    val title = r.runs("buttonText")?.takeIf { it.isNotBlank() } ?: return null

    return ShelfCard(id, title, kind = ShelfCardKind.SHELVES)
}

/** A target needs its `params` kept alongside the id, so both travel as one opaque handle. */
private fun JsonElement.browseTarget(): String? {
    val browseId = at("browseId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val params = at("params")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    return if (params == null) browseId else "$browseId|$params"
}

private fun JsonObject.flexColumn(index: Int): String? =
    flexColumnRuns(index)?.firstOrNull()?.jsonObject?.at("text")?.jsonPrimitive?.contentOrNull

private fun JsonObject.flexColumnRuns(index: Int): JsonArray? =
    at("flexColumns", index, "musicResponsiveListItemFlexColumnRenderer", "text", "runs")?.jsonArray

/** The joined text of a `runs` array anywhere under this node, the shape every label uses. */
private fun JsonElement?.runs(key: String): String? = this
    ?.at(key, "runs")?.jsonArray?.joinToString("") { it.jsonObject.at("text")?.jsonPrimitive?.contentOrNull.orEmpty() }

/** Walks an object/array path, so one helper serves every response shape this file reads. */
private fun JsonElement.at(vararg keys: Any): JsonElement? {
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

/** Every `musicResponsiveListItemRenderer` in a response, wherever the shape buried it. */
private fun JsonElement.responsiveItems(): List<JsonObject> {
    val found = mutableListOf<JsonObject>()
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> node.forEach { (key, value) ->
                if (key == "musicResponsiveListItemRenderer") (value as? JsonObject)?.let { found += it }
                walk(value)
            }
            is JsonArray -> node.forEach { walk(it) }
            else -> Unit
        }
    }
    walk(this)
    return found
}

private fun JsonElement.continuationToken(): String? {
    fun walk(node: JsonElement): String? {
        when (node) {
            is JsonObject -> node.forEach { (key, value) ->
                if (key == "continuation" && value is JsonPrimitive && value.isString) return value.content
                walk(value)?.let { return it }
            }
            is JsonArray -> node.forEach { walk(it)?.let { token -> return token } }
            else -> Unit
        }
        return null
    }
    return walk(this)
}
