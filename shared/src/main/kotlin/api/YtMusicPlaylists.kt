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

private val ytApiJson = Json { ignoreUnknownKeys = true }

private fun ytApiGet(url: String, token: String) =
    Http.get(url, headers = mapOf("Authorization" to "Bearer $token"))

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
    val response = ytApiGet(
        "https://www.googleapis.com/youtube/v3/playlists?part=snippet,contentDetails&mine=true&maxResults=50",
        token,
    )
    if (response.code != 200) return@withContext emptyList()
    val root = runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: return@withContext emptyList()
    val playlists = root["items"]?.jsonArray?.mapNotNull { item ->
        val obj = item.jsonObject
        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val snippet = obj["snippet"]?.jsonObject ?: return@mapNotNull null
        val title = snippet["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val itemCount = obj["contentDetails"]?.jsonObject?.get("itemCount")?.jsonPrimitive?.intOrNull ?: 0
        val thumbUrl = snippet["thumbnails"]?.jsonObject
            ?.let { it["maxres"] ?: it["standard"] ?: it["high"] ?: it["medium"] ?: it["default"] }?.jsonObject
            ?.get("url")?.jsonPrimitive?.content ?: ""
        Playlist(id, title, itemCount, thumbUrl)
    } ?: emptyList()

    // Override with actual YT Music cover art by browsing each playlist (unauthenticated InnerTube)
    val ytMusicThumbs: Map<String, String> = coroutineScope {
        playlists.map { playlist ->
            async { playlist.id to fetchPlaylistCoverFromBrowse(playlist.id) }
        }.awaitAll()
            .mapNotNull { (id, url) -> url?.let { id to it } }
            .toMap()
    }
    if (ytMusicThumbs.isEmpty()) return@withContext playlists
    playlists.map { playlist ->
        ytMusicThumbs[playlist.id]?.let { playlist.copy(thumbnailUrl = it) } ?: playlist
    }
}

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
    val response = ytApiGet(
        "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50",
        token,
    )
    if (response.code != 200) return@withContext emptyList()
    val root = runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: return@withContext emptyList()
    root["items"]?.jsonArray?.mapNotNull { item ->
        val snippet = item.jsonObject["snippet"]?.jsonObject ?: return@mapNotNull null
        val browseId = snippet["resourceId"]?.jsonObject?.get("channelId")?.jsonPrimitive?.content
            ?: return@mapNotNull null
        val name = snippet["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val thumbUrl = snippet["thumbnails"]?.jsonObject
            ?.let { it["high"] ?: it["medium"] ?: it["default"] }?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
        ArtistResult(browseId, name, thumbUrl, "channel")
    } ?: emptyList()
}

/**
 * Liked items include plain YouTube videos, so [musicOnly] batches them once more against
 * videos.list and keeps only what YouTube itself marks as Music (category 10) or which is
 * auto-generated by an artist topic channel.
 */
suspend fun fetchPlaylistTracks(playlistId: String, musicOnly: Boolean = false): List<PlaylistTrack> = withContext(Dispatchers.IO) {
    val token = GoogleAuth.accessToken ?: return@withContext emptyList()
    val response = ytApiGet(
        "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&playlistId=$playlistId&maxResults=50",
        token,
    )
    val root = runCatching { ytApiJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: return@withContext emptyList()
    val tracks = root["items"]?.jsonArray?.mapNotNull { item ->
        val snippet = item.jsonObject["snippet"]?.jsonObject ?: return@mapNotNull null
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
    } ?: emptyList()

    if (tracks.isEmpty()) return@withContext tracks
    val ids = tracks.joinToString(",") { it.videoId }
    val videoResponse = ytApiGet(
        "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=$ids",
        token,
    )
    val videoRoot = runCatching { ytApiJson.parseToJsonElement(videoResponse.body).jsonObject }.getOrNull()
    val videoItems = videoRoot?.get("items")?.jsonArray
    if (videoItems == null && musicOnly) return@withContext emptyList()  // cannot verify, cannot show

    val byId = videoItems.orEmpty().associate { item ->
        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: ""
        id to item.jsonObject
    }
    if (musicOnly) {
        val ownerByVideoId = root["items"]?.jsonArray?.associate { item ->
            val snippet = item.jsonObject["snippet"]?.jsonObject
            val id = snippet?.get("resourceId")?.jsonObject?.get("videoId")?.jsonPrimitive?.content ?: ""
            id to ((snippet?.get("videoOwnerChannelTitle")?.jsonPrimitive?.content ?: "").endsWith(" - Topic"))
        } ?: emptyMap()
        tracks.filter { track ->
            val isTopic = ownerByVideoId[track.videoId] == true
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
