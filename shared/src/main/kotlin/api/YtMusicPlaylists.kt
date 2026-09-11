package api

import auth.GoogleAuth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import models.ArtistResult
import models.Playlist
import models.PlaylistTrack
import util.Http
import util.Log

private val ytApiJson = Json { ignoreUnknownKeys = true }

private fun ytApiGet(url: String, token: String) =
    Http.get(url, headers = mapOf("Authorization" to "Bearer $token"))

/** The Data API's own cap: `maxResults` above this is rejected, not clamped. */
private const val YT_PAGE_SIZE = 50
/** Bounds a runaway token: 100 pages is 5k items, past any realistic library. */
private const val YT_MAX_PAGES = 100

/**
 * Walks a Data API list endpoint to its end through `nextPageToken`. Every page after the
 * first carries the token, and a page is only [YT_PAGE_SIZE] items — which is exactly where
 * the library lists used to stop.
 *
 * Null means the *first* page failed, so callers can keep their own fallback; a failure later
 * on returns what was collected, because a short list beats an empty one.
 */
private suspend fun ytApiPages(url: String, token: String): List<JsonObject>? {
    val items = mutableListOf<JsonObject>()
    var pageToken: String? = null
    var pages = 0
    while (true) {
        val response = ytApiGet(url + (pageToken?.let { "&pageToken=$it" } ?: ""), token)
        val root = runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        if (response.code != 200 || root == null) {
            if (items.isEmpty()) return null
            Log.w("YtMusic", "listing $url stopped early after $pages page(s), code ${response.code}")
            return items
        }
        root["items"]?.jsonArray?.forEach { items += it.jsonObject }
        pageToken = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
        if (pageToken == null) return items
        if (++pages >= YT_MAX_PAGES) {
            Log.w("YtMusic", "stopped listing $url after $YT_MAX_PAGES pages")
            return items
        }
    }
}

private val playlistHeaders = mapOf(
    "Content-Type" to "application/json",
    "X-YouTube-Client-Name" to "67",
    "X-YouTube-Client-Version" to "1.20220918.01.00",
    "Origin" to "https://music.youtube.com",
    "Referer" to "https://music.youtube.com/",
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
)

suspend fun fetchUserPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
    GoogleAuth.ensureValidToken()
    val token = GoogleAuth.accessToken ?: return@withContext emptyList()
    val items = ytApiPages(
        "https://www.googleapis.com/youtube/v3/playlists?part=snippet,contentDetails&mine=true&maxResults=$YT_PAGE_SIZE",
        token,
    ) ?: return@withContext savedPlaylists()
    val playlists = items.mapNotNull { obj ->
        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val snippet = obj["snippet"]?.jsonObject ?: return@mapNotNull null
        val title = snippet["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val itemCount = obj["contentDetails"]?.jsonObject?.get("itemCount")?.jsonPrimitive?.intOrNull ?: 0
        val thumbUrl = snippet["thumbnails"]?.jsonObject
            ?.let { it["maxres"] ?: it["standard"] ?: it["high"] ?: it["medium"] ?: it["default"] }?.jsonObject
            ?.get("url")?.jsonPrimitive?.content ?: ""
        Playlist(id, title, itemCount, thumbUrl)
    }

    // Override with actual YT Music cover art by browsing each playlist (unauthenticated InnerTube)
    val ytMusicThumbs: Map<String, String> = coroutineScope {
        playlists.map { playlist ->
            async { playlist.id to fetchPlaylistCoverFromBrowse(playlist.id) }
        }.awaitAll()
            .mapNotNull { (id, url) -> url?.let { id to it } }
            .toMap()
    }
    val withCovers = if (ytMusicThumbs.isEmpty()) playlists else playlists.map { playlist ->
        ytMusicThumbs[playlist.id]?.let { playlist.copy(thumbnailUrl = it) } ?: playlist
    }

    // The Data API only knows about playlists owned by this channel, so anything saved
    // from other creators has to come from the authenticated Music InnerTube browse.
    val ownedIds = withCovers.map { it.id }.toHashSet()
    withCovers + savedPlaylists().filter { playlist -> playlist.id !in ownedIds }
}

private suspend fun savedPlaylists(): List<Playlist> = runCatching { fetchSavedPlaylists() }
    .getOrDefault(emptyList())

/**
 * Playlists the user saved from other creators. Sent with the Google token as a Bearer
 * against Music's InnerTube, which is the only surface that exposes saved playlists.
 */
suspend fun fetchSavedPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
    GoogleAuth.ensureValidToken()
    val token = GoogleAuth.accessToken ?: return@withContext emptyList()
    val saved = listOf("FEmusic_liked_playlists", "FEmusic_playlists").firstNotNullOfOrNull { browseId ->
        runCatching { browseLibraryPlaylists(browseId, token) }.getOrNull()?.takeIf { it.isNotEmpty() }
    } ?: return@withContext emptyList()
    val owners = fetchPlaylistOwners(saved.map { it.id }, token)
    saved.map { playlist -> owners[playlist.id]?.let { playlist.copy(owner = it) } ?: playlist }
}

/** InnerTube cards carry no channel name, so the owning channel comes from the Data API. */
private suspend fun fetchPlaylistOwners(playlistIds: List<String>, token: String): Map<String, String> {
    if (playlistIds.isEmpty()) return emptyMap()
    return playlistIds.chunked(YT_PAGE_SIZE).flatMap { chunk ->
        val response = ytApiGet(
            "https://www.googleapis.com/youtube/v3/playlists?part=snippet&id=${chunk.joinToString(",")}",
            token,
        )
        if (response.code != 200) return@flatMap emptyList()
        runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?.get("items")?.jsonArray.orEmpty()
            .map { it.jsonObject }
    }.mapNotNull { item ->
        val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val owner = item["snippet"]?.jsonObject?.get("channelTitle")?.jsonPrimitive?.contentOrNull
        owner?.let { id to it }
    }.toMap()
}

private suspend fun browseLibraryPlaylists(browseId: String, token: String): List<Playlist> {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20220918.01.00")
                put("hl", "en")
            }
        }
        put("browseId", browseId)
    }.toString()

    val response = Http.post(
        "https://music.youtube.com/youtubei/v1/browse?key=${ApiKeyManager.ytMusicKey}&prettyPrint=false",
        body,
        headers = playlistHeaders + mapOf("Authorization" to "Bearer $token"),
    )
    if (response.code != 200) return emptyList()
    val root = runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: return emptyList()

    val tabs = (root["contents"]?.jsonObject
        ?.let { it["singleColumnBrowseResultsRenderer"] ?: it["twoColumnBrowseResultsRenderer"] }
        ?.jsonObject?.get("tabs")?.jsonArray).orEmpty()

    val sections = tabs.flatMap { tab ->
        (tab.jsonObject["tabRenderer"]?.jsonObject?.get("content")?.jsonObject)
            ?.let { it["sectionListRenderer"] ?: it }
            ?.jsonObject?.get("contents")?.jsonArray
            ?: emptyList()
    }

    val renderers = sections.flatMap { section ->
        (section.jsonObject["shelfRenderer"]?.jsonObject?.get("content")?.jsonObject
            ?.let { it["gridRenderer"] ?: it["musicPlaylistShelfRenderer"] }
            ?: section.jsonObject["gridRenderer"]
            ?: section.jsonObject["musicPlaylistShelfRenderer"])
            ?.jsonObject?.get("items")?.jsonArray ?: emptyList()
    }

    return renderers.mapNotNull { item ->
        item.jsonObject["musicTwoRowItemRenderer"]?.jsonObject?.let(::parseLibraryPlaylistItem)
            ?: item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject?.let(::parseLibraryPlaylistRow)
    }
}

private fun parseLibraryPlaylistItem(item: JsonObject): Playlist? {
    val id = item.dig(
        "navigationEndpoint", "watchPlaylistEndpoint", "playlistId",
    )?.jsonPrimitive?.contentOrNull
        ?: item.dig("navigationEndpoint", "browseEndpoint", "browseId")
            ?.jsonPrimitive?.contentOrNull?.removePrefix("VL")
        ?: return null
    if (!id.startsWith("PL") && !id.startsWith("OL")) return null
    val title = item.dig("title", "runs")?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return null
    val runs = item.dig("subtitle", "runs")?.jsonArray.orEmpty()
    val thumb = item.dig("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?.jsonArray?.pickThumbnailUrl() ?: ""
    return Playlist(id, title, runs.playlistItemCount(), thumb)
}

private fun parseLibraryPlaylistRow(item: JsonObject): Playlist? {
    val id = item.dig("navigationEndpoint", "watchPlaylistEndpoint", "playlistId")
        ?.jsonPrimitive?.contentOrNull
        ?: item.dig("navigationEndpoint", "browseEndpoint", "browseId")
            ?.jsonPrimitive?.contentOrNull?.removePrefix("VL")
        ?: return null
    if (!id.startsWith("PL") && !id.startsWith("OL")) return null
    val columns = item["flexColumns"]?.jsonArray.orEmpty().mapNotNull { column ->
        column.jsonObject["musicResponsiveListItemFlexColumnRenderer"]?.jsonObject
            ?.dig("text", "runs")?.jsonArray
    }
    val title = columns.firstOrNull()?.firstOrNull()
        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return null
    val runs = columns.getOrNull(1).orEmpty()
    val thumb = item.dig("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
        ?.jsonArray?.pickThumbnailUrl() ?: ""
    return Playlist(id, title, runs.playlistItemCount(), thumb)
}

private fun List<JsonElement>.playlistItemCount(): Int =
    mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        .firstOrNull { it.contains("song") || it.contains("video") }
        ?.filter { it.isDigit() }?.toIntOrNull() ?: 0

private fun JsonArray.pickThumbnailUrl(): String? =
    maxByOrNull { it.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 0 }
        ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

private fun JsonElement.dig(vararg keys: String): JsonElement? =
    keys.fold(this as JsonElement?) { acc, key -> (acc as? JsonObject)?.get(key) }

private suspend fun fetchPlaylistCoverFromBrowse(playlistId: String): String? = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20220918.01.00")
                put("hl", "en")
            }
        }
        put("browseId", "VL$playlistId")
    }.toString()

    val response = runCatching {
        Http.post(
            "https://music.youtube.com/youtubei/v1/browse?key=${ApiKeyManager.ytMusicKey}&prettyPrint=false",
            body,
            headers = playlistHeaders,
        )
    }.getOrNull() ?: return@withContext null

    if (response.code != 200) return@withContext null

    val root = runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: return@withContext null

    // The playlist cover lives in the header renderer — handle all known header types
    val header = root["header"]?.jsonObject ?: return@withContext null

    fun JsonElement.thumbnailsArray(): JsonArray? =
        (this as? JsonObject)?.get("thumbnails")?.jsonArray

    val thumbs =
        // musicDetailHeaderRenderer (standard playlists)
        header["musicDetailHeaderRenderer"]?.jsonObject
            ?.get("thumbnail")?.jsonObject
            ?.let { t ->
                t["croppedSquareThumbnailRenderer"]?.jsonObject?.get("thumbnail")?.thumbnailsArray()
                    ?: t["musicThumbnailRenderer"]?.jsonObject?.get("thumbnail")?.thumbnailsArray()
            }
        // musicEditablePlaylistDetailHeaderRenderer (user's own playlists)
        ?: header["musicEditablePlaylistDetailHeaderRenderer"]?.jsonObject
            ?.get("header")?.jsonObject
            ?.get("musicDetailHeaderRenderer")?.jsonObject
            ?.get("thumbnail")?.jsonObject
            ?.let { t ->
                t["croppedSquareThumbnailRenderer"]?.jsonObject?.get("thumbnail")?.thumbnailsArray()
                    ?: t["musicThumbnailRenderer"]?.jsonObject?.get("thumbnail")?.thumbnailsArray()
            }
        // musicImmersiveHeaderRenderer (some curated playlists)
        ?: header["musicImmersiveHeaderRenderer"]?.jsonObject
            ?.get("thumbnail")?.jsonObject
            ?.get("musicThumbnailRenderer")?.jsonObject
            ?.get("thumbnail")?.thumbnailsArray()

    thumbs?.maxByOrNull { it.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 0 }
        ?.jsonObject?.get("url")?.jsonPrimitive?.content
}

/** The authenticated user's liked videos — the "LL" system playlist, shared by YouTube and YouTube Music. */
suspend fun fetchLikedSongs(): List<PlaylistTrack> = fetchPlaylistTracks("LL", musicOnly = true)

/** Channels the authenticated user is subscribed to, shaped as artists for the library screen. */
/**
 * The user's subscriptions, narrowed to the ones YouTube Music knows as artists. The
 * subscriptions feed mixes music with every other channel the user follows, and the Data
 * API has no artist flag, so each channel is checked against [isMusicArtist] (bounded
 * concurrency: this fans out to one browse call per subscription).
 */
suspend fun fetchSubscribedChannels(): List<ArtistResult> = coroutineScope {
    val channels = fetchAllSubscriptions()
    val gate = Semaphore(6)
    channels
        .map { channel -> async { channel.takeIf { gate.withPermit { isMusicArtist(it.browseId) } } } }
        .awaitAll()
        .filterNotNull()
        .map { it.copy(subtitle = "artist") }
}

private suspend fun fetchAllSubscriptions(): List<ArtistResult> = withContext(Dispatchers.IO) {
    GoogleAuth.ensureValidToken()
    val token = GoogleAuth.accessToken ?: return@withContext emptyList()
    ytApiPages(
        "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=$YT_PAGE_SIZE",
        token,
    ).orEmpty().mapNotNull { obj ->
        val snippet = obj["snippet"]?.jsonObject ?: return@mapNotNull null
        val browseId = snippet["resourceId"]?.jsonObject?.get("channelId")?.jsonPrimitive?.content
            ?: return@mapNotNull null
        val name = snippet["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val thumbUrl = snippet["thumbnails"]?.jsonObject
            ?.let { it["high"] ?: it["medium"] ?: it["default"] }?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
        ArtistResult(browseId, name, thumbUrl, "channel")
    }
}

/**
 * Liked items include plain YouTube videos, so [musicOnly] batches them once more against
 * videos.list and keeps only what YouTube itself marks as Music (category 10) or which is
 * auto-generated by an artist topic channel.
 */
suspend fun fetchPlaylistTracks(playlistId: String, musicOnly: Boolean = false): List<PlaylistTrack> = withContext(Dispatchers.IO) {
    val token = GoogleAuth.accessToken ?: return@withContext emptyList()
    val items = ytApiPages(
        "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&playlistId=$playlistId&maxResults=$YT_PAGE_SIZE",
        token,
    ).orEmpty()
    val tracks = items.mapNotNull { obj ->
        val snippet = obj["snippet"]?.jsonObject ?: return@mapNotNull null
        val videoId = snippet["resourceId"]?.jsonObject?.get("videoId")?.jsonPrimitive?.content
            ?: return@mapNotNull null
        val title = snippet["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
        if (title == "Deleted video" || title == "Private video") return@mapNotNull null
        val rawChannelTitle = snippet["videoOwnerChannelTitle"]?.jsonPrimitive?.content ?: ""
        val channelTitle = rawChannelTitle.removeSuffix(" - Topic")
        val thumbUrl = snippet["thumbnails"]?.jsonObject
            ?.let { it["maxres"] ?: it["standard"] ?: it["high"] ?: it["medium"] ?: it["default"] }?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        PlaylistTrack(videoId, title, channelTitle, thumbUrl)
    }

    if (tracks.isEmpty()) return@withContext tracks
    // videos.list takes 50 ids at a time, so a full liked-songs list needs several round trips;
    // they run a few at a time because a serial walk of them is what makes the library crawl.
    val byId = coroutineScope {
        val gate = Semaphore(4)
        tracks.map { it.videoId }.distinct().chunked(YT_PAGE_SIZE).map { chunk ->
            async {
                gate.withPermit {
                    val response = ytApiGet(
                        "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=${chunk.joinToString(",")}",
                        token,
                    )
                    runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
                        ?.get("items")?.jsonArray.orEmpty()
                        .map { it.jsonObject }
                }
            }
        }.awaitAll().flatten()
    }.associateBy { it["id"]?.jsonPrimitive?.content ?: "" }

    if (byId.isEmpty() && musicOnly) return@withContext emptyList()  // cannot verify, cannot show

    if (musicOnly) {
        val topicOwners = items.associate { obj ->
            val snippet = obj["snippet"]?.jsonObject
            val id = snippet?.get("resourceId")?.jsonObject?.get("videoId")?.jsonPrimitive?.content ?: ""
            id to ((snippet?.get("videoOwnerChannelTitle")?.jsonPrimitive?.content ?: "").endsWith(" - Topic"))
        }
        tracks.filter { track ->
            val isTopic = topicOwners[track.videoId] == true
            isTopic || (byId[track.videoId]?.get("snippet")?.jsonObject
                ?.get("categoryId")?.jsonPrimitive?.content == "10")
        }
    } else {
        tracks
    }.map { track ->
        val iso = byId[track.videoId]?.get("contentDetails")?.jsonObject
            ?.get("duration")?.jsonPrimitive?.content ?: ""
        track.copy(duration = parseIsoDuration(iso))
    }
}

suspend fun fetchSubscriberCounts(channelIds: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
    if (channelIds.isEmpty()) return@withContext emptyMap()
    val token = GoogleAuth.accessToken ?: return@withContext emptyMap()
    val ids = channelIds.joinToString(",")
    val response = ytApiGet(
        "https://www.googleapis.com/youtube/v3/channels?part=statistics&id=$ids",
        token,
    )
    val root = runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: return@withContext emptyMap()
    root["items"]?.jsonArray?.associate { item ->
        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: ""
        val count = item.jsonObject["statistics"]?.jsonObject
            ?.get("subscriberCount")?.jsonPrimitive?.longOrNull ?: 0L
        id to count
    } ?: emptyMap()
}

suspend fun fetchViewCounts(videoIds: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
    if (videoIds.isEmpty()) return@withContext emptyMap()
    val token = GoogleAuth.accessToken ?: return@withContext emptyMap()
    val ids = videoIds.joinToString(",")
    val response = ytApiGet(
        "https://www.googleapis.com/youtube/v3/videos?part=statistics&id=$ids",
        token,
    )
    val root = runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: return@withContext emptyMap()
    root["items"]?.jsonArray?.associate { item ->
        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: ""
        val count = item.jsonObject["statistics"]?.jsonObject
            ?.get("viewCount")?.jsonPrimitive?.longOrNull ?: 0L
        id to count
    } ?: emptyMap()
}

internal fun parseIsoDuration(iso: String): String {
    val h = Regex("(\\d+)H").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val m = Regex("(\\d+)M").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val s = Regex("(\\d+)S").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
