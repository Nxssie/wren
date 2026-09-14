package provider

import api.Page
import api.SoundCloud
import api.SoundCloudDiscovery
import api.pagedFlow
import auth.SoundCloudAuth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import models.Playlist
import models.PlaylistTrack
import models.SearchResult
import models.Shelf
import models.ShelfCard
import models.Source
import util.Log
import util.TtlCache
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SoundCloudProvider : MusicProvider {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** Long enough to cover switching tabs, short enough that a like made elsewhere shows up. */
private const val LIBRARY_TTL_MS = 10 * 60 * 1000L

private const val TAG = "SoundCloud"

    override val platform = Platform.SOUNDCLOUD
    override val isAuthenticated: Boolean get() = SoundCloudAuth.isAuthenticated

    override val supportsArtists = false
    override val supportsStations = true
    override val supportsLibrary = true

    override val exploreEmptyHint = "sign_in_or_play_something_to_seed_explore"

    private const val LIKES_ID = "likes"

    // Same reasoning as the YouTube provider: these walk every page of a cursor and the screens
    // are rebuilt on every tab change.
    private val songs = TtlCache<String, List<PlaylistTrack>>(LIBRARY_TTL_MS)
    private val playlists = TtlCache<String, List<Playlist>>(LIBRARY_TTL_MS)
    private val playlistTracks = TtlCache<String, List<PlaylistTrack>>(LIBRARY_TTL_MS)

    /** Signing out and into another account must never serve the previous one's library. */
    private fun account(): String = SoundCloudAuth.userId?.toString() ?: "anonymous"

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        SoundCloud.searchTracks(query, limit)

    override suspend fun station(seed: SearchResult): List<SearchResult> = SoundCloud.stationFor(seed)

    /**
     * Wren's locally generated weekly list first, then SoundCloud's own "Made for you" mixes —
     * the platform's answer to a home feed.
     */
    override suspend fun home(forceRefresh: Boolean): List<Shelf> {
        val weekly = runCatching {
            if (forceRefresh) SoundCloudDiscovery.refresh() else SoundCloudDiscovery.current()
        }
            .onFailure { Log.w(TAG, "weekly discovery failed", it) }
            .getOrNull()
        val weeklySection = weekly?.let {
            val generated = Instant.ofEpochMilli(it.generatedAt).atZone(ZoneId.systemDefault()).format(dateFormatter)
            val basis = it.basisGenres.joinToString(", ")
            Shelf(
                title = "weekly discovery",
                caption = buildString {
                    append("generated $generated")
                    if (basis.isNotEmpty()) append(" · based_on: $basis")
                    append(" · "); append(it.tracks.size); append(" tracks — open_the_list")
                },
                cards = listOfNotNull(
                    it.tracks.takeIf(List<SearchResult>::isNotEmpty)?.let { tracks ->
                        ShelfCard(
                            id = "weekly-discovery",
                            title = "weekly discovery",
                            subtitle = "generated $generated",
                            artworkUrl = tracks.firstNotNullOfOrNull { t -> t.thumbnailUrl.ifBlank { null } },
                            tracks = tracks
                        )
                    }
                )
            )
        }
        return listOfNotNull(weeklySection) + selections { it.isPersonal() }
    }

    /**
     * The same selections minus the mixes built for this account: SoundCloud's curated and
     * trending shelves, which is what Explore is for.
     */
    override suspend fun explore(): List<Shelf> = selections { !it.isPersonal() }

    private suspend fun selections(keep: (SoundCloud.Selection) -> Boolean): List<Shelf> =
        runCatching { SoundCloud.mixedSelections() }
            .onFailure { Log.w(TAG, "mixed-selections failed", it) }
            .getOrDefault(emptyList())
            .filter(keep)
            .distinctBy { it.urn }
            .map { sel ->
                Shelf(
                    title = sel.title.lowercase(),
                    // A selection can list the same system playlist twice; showing the card twice
                    // tells the user nothing and used to repeat a lazy-list key.
                    cards = sel.items.distinctBy { it.id }.take(12)
                        .map { ShelfCard(it.id, it.title, it.subtitle, it.artworkUrl) }
                )
            }

    /** SoundCloud marks the mixes it built for this account in the urn. */
    private fun SoundCloud.Selection.isPersonal(): Boolean =
        urn.contains("personali") || urn.contains("made-for")

    override suspend fun collectionTracks(collectionId: String): List<SearchResult> =
        SoundCloud.collectionTracks(collectionId)

    // ── Library: likes + own playlists + playlists saved from other users ───

    override suspend fun playlists(): List<Playlist> = playlists.getOrLoad(account()) { fetchPlaylists() }

    private suspend fun fetchPlaylists(): List<Playlist> {
        val userId = SoundCloudAuth.userId ?: return emptyList()
        val likes = Playlist(id = LIKES_ID, title = "Liked tracks", itemCount = 0, thumbnailUrl = SoundCloudAuth.avatarUrl ?: "")
        return coroutineScope {
            val own = async { runCatching { SoundCloud.userPlaylists(userId) }.getOrDefault(emptyList()) }
            val saved = async { runCatching { SoundCloud.userSavedPlaylists(userId) }.getOrDefault(emptyList()) }
            val owned = own.await()
            val savedElsewhere = saved.await().filter { candidate -> owned.none { it.id == candidate.id } }
            listOf(likes) + owned + savedElsewhere
        }
    }

    override suspend fun playlistTracks(playlistId: String): List<PlaylistTrack> =
        playlistTracks.getOrLoad("${'$'}{account()}|$playlistId") { fetchPlaylistTracks(playlistId) }

    private suspend fun fetchPlaylistTracks(playlistId: String): List<PlaylistTrack> {
        val userId = SoundCloudAuth.userId ?: return emptyList()
        val tracks = if (playlistId == LIKES_ID) SoundCloud.userLikes(userId) else SoundCloud.collectionTracks(playlistId)
        return tracks.map { it.toPlaylistTrack() }
    }

    override fun librarySongsFlow(): Flow<List<PlaylistTrack>> =
        pagedFlow(TAG, songs, account()) { cursor ->
            val userId = SoundCloudAuth.userId ?: return@pagedFlow Page(emptyList())
            SoundCloud.userLikesPage(userId, cursor).map { it.toPlaylistTrack() }
        }

    override fun playlistTracksFlow(playlistId: String): Flow<List<PlaylistTrack>> {
        if (playlistId != LIKES_ID) {
            // A collection arrives in one payload — its stubs are hydrated together — so there is
            // nothing to stream mid-way; it still goes through the cache.
            return flow {
                emit(playlistTracks.getOrLoad(playlistKey(playlistId)) { fetchPlaylistTracks(playlistId) })
            }
        }
        return pagedFlow(TAG, playlistTracks, playlistKey(playlistId)) { cursor ->
            val userId = SoundCloudAuth.userId ?: return@pagedFlow Page(emptyList())
            SoundCloud.userLikesPage(userId, cursor).map { it.toPlaylistTrack() }
        }
    }

    private fun playlistKey(playlistId: String): String = "${account()}|$playlistId"

    override suspend fun librarySongs(): List<PlaylistTrack> =
        songs.getOrLoad(account()) { SoundCloud.userLikes(SoundCloudAuth.userId ?: return@getOrLoad emptyList()).map { it.toPlaylistTrack() } }
}

private fun SearchResult.toPlaylistTrack() = PlaylistTrack(
    videoId = videoId,
    title = title,
    channelTitle = artist,
    thumbnailUrl = thumbnailUrl,
    duration = duration,
    source = Source.SOUNDCLOUD
)
