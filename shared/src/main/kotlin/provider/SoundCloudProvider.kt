package provider

import api.SoundCloud
import api.SoundCloudDiscovery
import auth.SoundCloudAuth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import models.Playlist
import models.PlaylistTrack
import models.SearchResult
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

    override val platform = Platform.SOUNDCLOUD
    override val isAuthenticated: Boolean get() = SoundCloudAuth.isAuthenticated

    override val supportsArtists = false
    override val supportsStations = true
    override val supportsLibrary = true

    override val discoverEmptyHint = "sign_in_or_play_something_to_seed_discover"

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
     * SoundCloud's own selections first (personalised "Made for you" mixes when a session
     * exists, curated and trending otherwise), then Wren's locally generated weekly list.
     */
    override suspend fun discover(forceRefresh: Boolean): List<DiscoverSection> {
        val selections = runCatching { SoundCloud.mixedSelections() }
            .onFailure { Log.w("SoundCloudProvider", "mixed-selections failed", it) }
            .getOrDefault(emptyList())
            .sortedBy { if (it.urn.contains("personali") || it.urn.contains("made-for")) 0 else 1 }
            .map { sel ->
                DiscoverSection(
                    title = sel.title.lowercase(),
                    collections = sel.items.take(12).map {
                        DiscoverCollection(it.id, it.title, it.subtitle, it.artworkUrl)
                    }
                )
            }

        val weekly = runCatching { if (forceRefresh) SoundCloudDiscovery.refresh() else SoundCloudDiscovery.current() }
            .getOrNull()
        val weeklySection = weekly?.let {
            val generated = Instant.ofEpochMilli(it.generatedAt).atZone(ZoneId.systemDefault()).format(dateFormatter)
            val basis = it.basisGenres.joinToString(", ")
            DiscoverSection(
                title = "weekly discovery",
                caption = buildString { append("generated $generated"); if (basis.isNotEmpty()) append(" · based_on: $basis") },
                tracks = it.tracks
            )
        }
        return listOfNotNull(weeklySection) + selections
    }

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
