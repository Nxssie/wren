package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import models.SearchResult
import models.Source
import util.Log
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val scClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(8))
    .build()

private val scJson = Json { ignoreUnknownKeys = true }
private const val SC_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
private const val SC_SEARCH_PARAMS = "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo="

// ── Client ID management ──────────────────────────────────────────────────────

private val configDir = File(System.getProperty("user.home"), ".config/wren")
private val scConfigFile = File(configDir, "soundcloud.json")

@Volatile private var cachedClientId: String? = null
@Volatile private var clientIdFetchedAt: Long = 0L
private const val CLIENT_ID_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

internal fun extractAssetUrls(html: String): List<String> =
    ASSET_URL_REGEX.findAll(html).map { it.value }.toList()

internal fun extractClientId(js: String): String? =
    CLIENT_ID_REGEX.find(js)?.groupValues?.get(1)

private val ASSET_URL_REGEX = Regex("""https://a-v2\.sndcdn\.com/assets/[^"]+\.js""")
private val CLIENT_ID_REGEX = Regex("""client_id\s*[:=]\s*"([a-zA-Z0-9]{32})""")

internal suspend fun scClientId(): String {
    cachedClientId?.let { if (System.currentTimeMillis() - clientIdFetchedAt < CLIENT_ID_TTL_MS) return it }
    return withContext(Dispatchers.IO) {
        // Config override
        val override = loadClientIdConfig()
        if (override != null) {
            cachedClientId = override
            clientIdFetchedAt = System.currentTimeMillis()
            return@withContext override
        }
        // Scrape from SoundCloud HTML → JS assets
        val scraped = scrapeClientId()
        if (scraped != null) {
            cachedClientId = scraped
            clientIdFetchedAt = System.currentTimeMillis()
        }
        scraped ?: error("Failed to obtain SoundCloud client_id")
    }
}

private fun loadClientIdConfig(): String? = runCatching {
    if (!scConfigFile.exists()) return null
    val root = scJson.parseToJsonElement(scConfigFile.readText()).jsonObject
    root["client_id"]?.jsonPrimitive?.content
}.getOrNull()

private fun scrapeClientId(): String? = runCatching {
    val html = httpGet("https://soundcloud.com/") ?: return@runCatching null
    val assetUrls = extractAssetUrls(html)
    // Iterate in reverse — the client_id is typically in the last JS asset
    for (url in assetUrls.asReversed()) {
        val js = httpGet(url) ?: continue
        extractClientId(js)?.let { return@runCatching it }
    }
    null
}.onFailure { Log.e("SoundCloud", "client_id scrape failed", it) }.getOrNull()

/** Invalidate cache and re-scrape. Called once after a 401/403 from api-v2. */
internal fun invalidateClientId() {
    cachedClientId = null
    clientIdFetchedAt = 0L
}

// ── HTTP helpers ──────────────────────────────────────────────────────────────

private fun httpGet(url: String): String? {
    val req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", SC_USER_AGENT)
        .GET()
        .timeout(Duration.ofSeconds(10))
        .build()
    val resp = scClient.send(req, HttpResponse.BodyHandlers.ofString())
    return if (resp.statusCode() in 200..299) resp.body() else null
}

private const val SC_BASE = "https://api-v2.soundcloud.com"

internal suspend fun scGetJson(path: String): JsonObject? {
    val clientId = scClientId()
    val separator = if ('?' in path) "&" else "?"
    val fullUrl = "$SC_BASE$path${separator}client_id=$clientId"
    val resp = httpGetJson(fullUrl)
    if (resp == null || resp.status in 401..403) {
        // Possibly stale client_id — invalidate and retry once
        Log.w("SoundCloud", "API returned ${resp?.status} for $path — re-scraping client_id")
        invalidateClientId()
        val newClientId = runCatching { scClientId() }.getOrNull() ?: return null
        val retryUrl = "$SC_BASE$path${separator}client_id=$newClientId"
        return httpGetJson(retryUrl)?.body?.jsonObject
    }
    return resp.body.jsonObject
}

private data class JsonResp(val status: Int, val body: JsonElement)

private fun httpGetJson(url: String): JsonResp? = runCatching {
    val req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", SC_USER_AGENT)
        .GET()
        .timeout(Duration.ofSeconds(10))
        .build()
    val resp = scClient.send(req, HttpResponse.BodyHandlers.ofString())
    val body = if (resp.body().isNotBlank()) scJson.parseToJsonElement(resp.body()) else buildJsonObject {}
    JsonResp(resp.statusCode(), body)
}.getOrNull()

// ── Public API ────────────────────────────────────────────────────────────────

object SoundCloud {
    suspend fun searchTracks(query: String, limit: Int = 20): List<SearchResult> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val root = scGetJson("/search/tracks?q=$encoded&limit=$limit&offset=0") ?: return@withContext emptyList()
        val collection = root["collection"]?.jsonArray ?: return@withContext emptyList()
        collection.mapNotNull { parseScTrack(it.jsonObject) }
    }

    suspend fun relatedTracks(soundcloudId: Long, limit: Int = 25): List<SearchResult> = withContext(Dispatchers.IO) {
        val root = scGetJson("/tracks/$soundcloudId/related?limit=$limit&offset=0") ?: return@withContext emptyList()
        val collection = root["collection"]?.jsonArray ?: return@withContext emptyList()
        collection.mapNotNull { parseScTrack(it.jsonObject) }
    }

    suspend fun trendingWeekly(limit: Int = 30): List<SearchResult> = withContext(Dispatchers.IO) {
        val root = scGetJson(
            "/charts?kind=trending&period=weekly&genre=soundcloud:genres:all-music&limit=$limit"
        ) ?: return@withContext emptyList()
        val collection = root["collection"]?.jsonArray ?: return@withContext emptyList()
        collection.mapNotNull { item ->
            val track = item.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
            parseScTrack(track)
        }
    }

    suspend fun stationFor(seed: SearchResult): List<SearchResult> = withContext(Dispatchers.IO) {
        val id = seed.soundcloudId ?: return@withContext listOf(seed)
        val related = runCatching { relatedTracks(id, 25) }.getOrDefault(emptyList())
        val seedIds = setOf(seed.soundcloudId)
        val relatedUnique = related
            .filter { it.soundcloudId !in seedIds && it.videoId != seed.videoId }
            .distinctBy { it.soundcloudId }
        listOf(seed) + relatedUnique
    }
}

// ── Track parsing (internal for testing) ──────────────────────────────────────

internal fun parseScTrack(obj: JsonObject): SearchResult? {
    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return null
    val permalinkUrl = obj["permalink_url"]?.jsonPrimitive?.content ?: return null
    val streamable = obj["streamable"]?.jsonPrimitive?.booleanOrNull ?: false
    val policy = obj["policy"]?.jsonPrimitive?.contentOrNull
    if (!streamable || policy == "SNIPPET" || policy == "BLOCK") return null

    val title = obj["title"]?.jsonPrimitive?.content ?: return null
    val user = obj["user"]?.jsonObject
    val artist = user?.get("username")?.jsonPrimitive?.content ?: "Unknown"

    val durationMs = obj["full_duration"]?.jsonPrimitive?.longOrNull
        ?: obj["duration"]?.jsonPrimitive?.longOrNull
        ?: 0L
    val duration = formatMs(durationMs)

    val artworkUrl = obj["artwork_url"]?.jsonPrimitive?.contentOrNull
        ?: user?.get("avatar_url")?.jsonPrimitive?.contentOrNull
        ?: ""
    val thumbnailUrl = artworkUrl.replace("-large.", "-t500x500.")

    val playbackCount = obj["playback_count"]?.jsonPrimitive?.longOrNull
    val genre = obj["genre"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    return SearchResult(
        videoId = permalinkUrl,
        title = title,
        artist = artist,
        artistId = null,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        source = Source.SOUNDCLOUD,
        viewCount = playbackCount,
        soundcloudId = id,
        genre = genre
    )
}

internal fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
