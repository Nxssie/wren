package api

import auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class Playlist(
    val id: String,
    val title: String,
    val itemCount: Int,
    val thumbnailUrl: String
)

data class PlaylistTrack(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String,
    val duration: String = ""
) {
    val url get() = "https://music.youtube.com/watch?v=$videoId"
}

private val ytApiClient = HttpClient.newHttpClient()
private val ytApiJson = Json { ignoreUnknownKeys = true }

suspend fun fetchUserPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
    AuthManager.ensureValidToken()
    val token = AuthManager.accessToken ?: return@withContext emptyList()
    val response = ytApiClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/youtube/v3/playlists?part=snippet,contentDetails&mine=true&maxResults=50"))
            .header("Authorization", "Bearer $token")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString()
    )
    if (response.statusCode() != 200) return@withContext emptyList()
    val root = runCatching { ytApiJson.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        ?: return@withContext emptyList()
    root["items"]?.jsonArray?.mapNotNull { item ->
        val obj = item.jsonObject
        val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val snippet = obj["snippet"]?.jsonObject ?: return@mapNotNull null
        val title = snippet["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val itemCount = obj["contentDetails"]?.jsonObject?.get("itemCount")?.jsonPrimitive?.intOrNull ?: 0
        val thumbUrl = snippet["thumbnails"]?.jsonObject
            ?.let { it["medium"] ?: it["default"] }?.jsonObject
            ?.get("url")?.jsonPrimitive?.content ?: ""
        Playlist(id, title, itemCount, thumbUrl)
    } ?: emptyList()
}

suspend fun fetchPlaylistTracks(playlistId: String): List<PlaylistTrack> = withContext(Dispatchers.IO) {
    val token = AuthManager.accessToken ?: return@withContext emptyList()
    val response = ytApiClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&playlistId=$playlistId&maxResults=50"))
            .header("Authorization", "Bearer $token")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString()
    )
    val root = runCatching { ytApiJson.parseToJsonElement(response.body()).jsonObject }.getOrNull()
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
            ?.let { it["medium"] ?: it["default"] }?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        PlaylistTrack(videoId, title, channelTitle, thumbUrl)
    } ?: emptyList()

    if (tracks.isEmpty()) return@withContext tracks
    val ids = tracks.joinToString(",") { it.videoId }
    val durResponse = ytApiClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id=$ids"))
            .header("Authorization", "Bearer $token")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString()
    )
    val durRoot = runCatching { ytApiJson.parseToJsonElement(durResponse.body()).jsonObject }.getOrNull()
    val durMap = durRoot?.get("items")?.jsonArray?.associate { item ->
        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: ""
        val iso = item.jsonObject["contentDetails"]?.jsonObject?.get("duration")?.jsonPrimitive?.content ?: ""
        id to parseIsoDuration(iso)
    } ?: emptyMap()

    tracks.map { it.copy(duration = durMap[it.videoId] ?: "") }
}

suspend fun fetchSubscriberCounts(channelIds: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
    if (channelIds.isEmpty()) return@withContext emptyMap()
    val token = AuthManager.accessToken ?: return@withContext emptyMap()
    val ids = channelIds.joinToString(",")
    val response = ytApiClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/youtube/v3/channels?part=statistics&id=$ids"))
            .header("Authorization", "Bearer $token")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString()
    )
    val root = runCatching { ytApiJson.parseToJsonElement(response.body()).jsonObject }.getOrNull()
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
    val token = AuthManager.accessToken ?: return@withContext emptyMap()
    val ids = videoIds.joinToString(",")
    val response = ytApiClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/youtube/v3/videos?part=statistics&id=$ids"))
            .header("Authorization", "Bearer $token")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString()
    )
    val root = runCatching { ytApiJson.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        ?: return@withContext emptyMap()
    root["items"]?.jsonArray?.associate { item ->
        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: ""
        val count = item.jsonObject["statistics"]?.jsonObject
            ?.get("viewCount")?.jsonPrimitive?.longOrNull ?: 0L
        id to count
    } ?: emptyMap()
}

private fun parseIsoDuration(iso: String): String {
    val h = Regex("(\\d+)H").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val m = Regex("(\\d+)M").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val s = Regex("(\\d+)S").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
