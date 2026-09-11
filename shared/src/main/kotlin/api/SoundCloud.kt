package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import auth.SoundCloudAuth
import kotlinx.serialization.json.*
import models.Playlist
import models.SearchResult
import models.Source
import util.AppDirs
import util.Http
import util.Log
import java.io.File
import java.net.URLEncoder

private val scJson = Json { ignoreUnknownKeys = true }
private const val SC_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
private const val SC_SEARCH_PARAMS = "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo="

// ── Client ID management ──────────────────────────────────────────────────────

private val scConfigFile get() = File(AppDirs.config, "soundcloud.json")

@Volatile private var cachedClientId: String? = null
@Volatile private var clientIdFetchedAt: Long = 0L
private const val CLIENT_ID_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

internal fun extractAssetUrls(html: String): List<String> =
    ASSET_URL_REGEX.findAll(html).map { it.value }.toList()

internal fun extractClientId(js: String): String? =
    CLIENT_ID_REGEX.find(js)?.groupValues?.get(1)

private val ASSET_URL_REGEX = Regex("""https://a-v2\.sndcdn\.com/assets/[^"]+\.js""")
private val CLIENT_ID_REGEX = Regex("""client_id\s*[:=]\s*"([a-zA-Z0-9]{32})""")

suspend fun scClientId(): String {
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
    val resp = Http.get(url, headers = mapOf("User-Agent" to SC_USER_AGENT))
    return if (resp.isSuccessful) resp.body else null
}

private const val SC_BASE = "https://api-v2.soundcloud.com"

/**
 * GET an api-v2 path as JSON. Sends the user's OAuth token when a session exists so
 * personalised endpoints (`/me/...`, "Made for you" selections) resolve for that user;
 * anonymous calls still work for public data.
 */
internal suspend fun scGetJson(path: String): JsonObject? = scGetJsonElement(path)?.let { it as? JsonObject }

internal suspend fun scGetJsonElement(path: String): JsonElement? {
    val clientId = scClientId()
    val separator = if ('?' in path) "&" else "?"
    fun urlFor(id: String) = "$SC_BASE$path${separator}client_id=$id"

    val first = httpGetJson(urlFor(clientId), SoundCloudAuth.accessToken)
    if (first != null && first.status in 200..299) return first.body

    // A refusal is the only sign the page's session went stale: it carries no expiry we can read,
    // so the token is renewed on being turned away rather than on a clock we would have to invent.
    if (first != null && first.status in 401..403 && SoundCloudAuth.refreshNow()) {
        val afterRefresh = httpGetJson(urlFor(clientId), SoundCloudAuth.accessToken)
        if (afterRefresh != null && afterRefresh.status in 200..299) return afterRefresh.body
    }

    // Otherwise the client_id is the stale half — invalidate and retry once.
    Log.w("SoundCloud", "API returned ${first?.status} for $path — re-scraping client_id")
    invalidateClientId()
    val newClientId = runCatching { scClientId() }.getOrNull() ?: return null
    return httpGetJson(urlFor(newClientId), SoundCloudAuth.accessToken)?.takeIf { it.status in 200..299 }?.body
}

private data class JsonResp(val status: Int, val body: JsonElement)

/** SoundCloud clamps a page well below this; the cursor from `next_href` is what advances. */
private const val SC_PAGE_SIZE = 200

/**
 * Walks an api-v2 collection to its end through `next_href`, so a library list is not just its
 * first page — the default page size is what silently truncated likes, playlists and the like.
 *
 * A page that fails partway keeps what was already collected rather than failing the call: the
 * alternative is showing nothing at all, and every item is independent.
 */
/**
 * One page of an api-v2 collection. The cursor is the `next_href` the API hands back, passed
 * through unread: it is an opaque token, not an offset anyone should compute.
 */
internal suspend fun <T> scCollectionPage(
    path: String,
    cursor: String?,
    parse: (JsonObject) -> T?,
): Page<T> {
    val root = scGetJson(cursor ?: "$path?limit=$SC_PAGE_SIZE")
        ?: throw IllegalStateException("soundcloud: $path page failed")
    return Page(
        root["collection"]?.jsonArray.orEmpty().mapNotNull { parse(it.jsonObject) },
        root["next_href"]?.jsonPrimitive?.contentOrNull?.removePrefix(SC_BASE),
    )
}

internal suspend fun <T> scCollection(path: String, parse: (JsonObject) -> T?): List<T> =
    allPages("SoundCloud") { scCollectionPage(path, it, parse) }

private fun httpGetJson(url: String, token: String? = null): JsonResp? = runCatching {
    val headers = buildMap {
        put("User-Agent", SC_USER_AGENT)
        if (token != null) put("Authorization", "OAuth $token")
    }
    val resp = Http.get(url, headers)
    val body = if (resp.body.isNotBlank()) scJson.parseToJsonElement(resp.body) else buildJsonObject {}
    JsonResp(resp.code, body)
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

    /**
     * Radio seeded by a track. Prefers SoundCloud's own station (what the web player
     * plays for "Start station"); falls back to related tracks when it is empty.
     */
    suspend fun stationFor(seed: SearchResult): List<SearchResult> = withContext(Dispatchers.IO) {
        val id = seed.soundcloudId ?: return@withContext listOf(seed)
        val station = runCatching { stationTracks(id, 50) }.getOrDefault(emptyList())
        val candidates = if (station.isNotEmpty()) station else runCatching { relatedTracks(id, 25) }.getOrDefault(emptyList())
        val rest = candidates
            .filter { it.soundcloudId != id && it.videoId != seed.videoId }
            .distinctBy { it.soundcloudId }
        listOf(seed) + rest
    }

    private suspend fun stationTracks(trackId: Long, limit: Int): List<SearchResult> {
        val root = scGetJson("/stations/soundcloud:track-stations:$trackId/tracks?limit=$limit") ?: return emptyList()
        return parseTrackCollection(root["collection"]?.jsonArray)
    }

    // ── Discover: SoundCloud's own selections ("Made for you", curated, trending) ──

    data class Selection(val urn: String, val title: String, val items: List<Collection>)

    /** A playlist-like item in a selection. [id] is what [collectionTracks] takes back. */
    data class Collection(
        val id: String,
        val title: String,
        val subtitle: String?,
        val artworkUrl: String?,
        val trackCount: Int
    )

    suspend fun mixedSelections(limit: Int = 12): List<Selection> = withContext(Dispatchers.IO) {
        val root = scGetJson("/mixed-selections?limit=$limit") ?: return@withContext emptyList()
        root["collection"]?.jsonArray?.mapNotNull { sel ->
            val obj = sel.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val urn = obj["urn"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val items = obj["items"]?.jsonObject?.get("collection")?.jsonArray
                ?.mapNotNull { parseCollection(it.jsonObject) } ?: emptyList()
            if (items.isEmpty()) null else Selection(urn, title, items)
        } ?: emptyList()
    }

    /**
     * Tracks of a collection returned by [mixedSelections] or [userPlaylists].
     * Playlist payloads embed only the first few full tracks; the rest are id stubs
     * that must be hydrated through `/tracks?ids=`.
     */
    suspend fun collectionTracks(collectionId: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val (kind, id) = collectionId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val path = when (kind) {
            "playlist" -> "/playlists/$id?representation=full"
            "system" -> "/system-playlists/$id?representation=full"
            else -> return@withContext emptyList()
        }
        val root = scGetJson(path) ?: return@withContext emptyList()
        hydrate(root["tracks"]?.jsonArray)
    }

    // ── Library ──────────────────────────────────────────────────────────────

    suspend fun userLikes(userId: Long): List<SearchResult> =
        allPages("SoundCloud") { userLikesPage(userId, it) }

    /** One page of likes, for the screens that fill as it arrives. */
    suspend fun userLikesPage(userId: Long, cursor: String? = null): Page<SearchResult> = withContext(Dispatchers.IO) {
        scCollectionPage("/users/$userId/track_likes", cursor) { item ->
            item["track"]?.jsonObject?.let(::parseScTrack)
        }
    }

    suspend fun userPlaylists(userId: Long): List<Playlist> = withContext(Dispatchers.IO) {
        scCollection("/users/$userId/playlists_without_albums") { item ->
            parseCollection(item)?.let {
                Playlist(id = it.id, title = it.title, itemCount = it.trackCount, thumbnailUrl = it.artworkUrl ?: "")
            }
        }
    }

    /**
     * Playlists from other users that [userId] saved (liked). The response nests the full
     * playlist under `playlist`, unlike [userPlaylists] which returns them directly.
     */
    suspend fun userSavedPlaylists(userId: Long): List<Playlist> = withContext(Dispatchers.IO) {
        scCollection("/users/$userId/playlist_likes") { item ->
            val obj = item["playlist"]?.jsonObject ?: item
            val collection = parseCollection(obj) ?: return@scCollection null
            val owner = obj["user"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull
            collection to owner
        }
            .distinctBy { it.first.id }
            .map { (collection, owner) ->
                Playlist(
                    id = collection.id,
                    title = if (owner != null) "${collection.title} · by $owner" else collection.title,
                    itemCount = collection.trackCount,
                    thumbnailUrl = collection.artworkUrl ?: "",
                )
            }
    }

    /**
     * Every liked track as permalink → id, following `next_href` so the set is complete
     * (the UI keys likes by permalink because that is what QueueItem carries).
     */
    suspend fun userLikeIds(userId: Long): Map<String, Long> = withContext(Dispatchers.IO) {
        scCollection("/users/$userId/track_likes") { item ->
            val track = item["track"]?.jsonObject ?: return@scCollection null
            val id = track["id"]?.jsonPrimitive?.longOrNull ?: return@scCollection null
            val permalink = track["permalink_url"]?.jsonPrimitive?.contentOrNull ?: return@scCollection null
            permalink to id
        }.toMap()
    }

    /** Numeric id behind a track permalink, for items that only carry the URL. */
    suspend fun resolveTrackId(permalink: String): Long? = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(permalink, "UTF-8")
        scGetJson("/resolve?url=$encoded")?.get("id")?.jsonPrimitive?.longOrNull
    }

    /** Like or unlike [trackId] for the signed-in user. False when there is no session or the call failed. */
    suspend fun setLiked(trackId: Long, liked: Boolean): Boolean = withContext(Dispatchers.IO) {
        val userId = SoundCloudAuth.userId ?: return@withContext false
        val clientId = scClientId()

        suspend fun attempt(): Http.Response? = runCatching {
            Http.request(
                method = if (liked) "PUT" else "DELETE",
                url = "$SC_BASE/users/$userId/track_likes/$trackId?client_id=$clientId",
                headers = mapOf(
                    "User-Agent" to SC_USER_AGENT,
                    "Authorization" to "OAuth ${SoundCloudAuth.accessToken.orEmpty()}",
                ),
            )
        }.onFailure { Log.e("SoundCloud", "like request failed for $trackId", it) }.getOrNull()

        var resp = attempt()
        // Same as any other call: a refusal is how the session says its token went stale.
        if (resp != null && resp.code in 401..403 && SoundCloudAuth.refreshNow()) resp = attempt()

        val ok = resp != null && resp.code in 200..299
        if (!ok) Log.w("SoundCloud", "like ${if (liked) "PUT" else "DELETE"} $trackId -> ${resp?.code}")
        ok
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun parseTrackCollection(arr: JsonArray?): List<SearchResult> =
        arr?.mapNotNull { parseScTrack(it.jsonObject) } ?: emptyList()

    /** Full track objects pass through; id-only stubs are fetched in batches, order preserved. */
    private suspend fun hydrate(tracks: JsonArray?): List<SearchResult> {
        if (tracks == null) return emptyList()
        val full = tracks.mapNotNull { t -> t.jsonObject.takeIf { "title" in it }?.let(::parseScTrack) }
            .associateBy { it.soundcloudId }
        val stubIds = tracks.mapNotNull { t -> t.jsonObject.takeIf { "title" !in it }?.get("id")?.jsonPrimitive?.longOrNull }
        val fetched = stubIds.chunked(50).flatMap { chunk ->
            val arr = scGetJsonElement("/tracks?ids=${chunk.joinToString(",")}") as? JsonArray
            parseTrackCollection(arr)
        }.associateBy { it.soundcloudId }
        return tracks.mapNotNull { t ->
            val id = t.jsonObject["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            full[id] ?: fetched[id]
        }
    }

    internal fun parseCollection(obj: JsonObject): Collection? {
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = when (kind) {
            "playlist" -> "playlist:" + (obj["id"]?.jsonPrimitive?.longOrNull ?: return null)
            "system-playlist" -> "system:" + (obj["urn"]?.jsonPrimitive?.contentOrNull ?: return null)
            else -> return null
        }
        val artwork = (obj["calculated_artwork_url"] ?: obj["artwork_url"])?.jsonPrimitive?.contentOrNull
            ?.replace("-large.", "-t500x500.")
        val count = obj["track_count"]?.jsonPrimitive?.intOrNull ?: obj["tracks"]?.jsonArray?.size ?: 0
        val subtitle = obj["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: obj["user"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull
        return Collection(id, title, subtitle, artwork, count)
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
