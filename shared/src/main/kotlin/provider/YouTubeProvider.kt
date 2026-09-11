package provider

import api.ListeningHistory
import api.YoutubeMusic
import api.fetchLikedSongs
import api.fetchPlaylistTracks
import api.fetchSubscribedChannels
import api.fetchUserPlaylists
import api.youtubeRadio
import auth.GoogleAuth
import models.Source
import models.ArtistResult
import models.Playlist
import models.PlaylistTrack
import models.SearchResult
import util.TtlCache

/** Long enough to cover switching tabs, short enough that a like made elsewhere shows up. */
private const val LIBRARY_TTL_MS = 10 * 60 * 1000L

/**
 * YouTube + YouTube Music behind one provider: both are the same catalog and the same
 * Google session, so they stay merged in search results (see [YoutubeMusic.search]).
 */
object YouTubeProvider : MusicProvider {
    override val platform = Platform.YOUTUBE
    override val isAuthenticated: Boolean get() = GoogleAuth.isAuthenticated

    // Every one of these walks the whole list page by page and then enriches it, and the screens
    // that show them are rebuilt on every tab change — so they are cached per account instead of
    // re-fetched on each visit. See [TtlCache].
    private val songs = TtlCache<String, List<PlaylistTrack>>(LIBRARY_TTL_MS)
    private val playlists = TtlCache<String, List<Playlist>>(LIBRARY_TTL_MS)
    private val artists = TtlCache<String, List<ArtistResult>>(LIBRARY_TTL_MS)
    private val playlistTracks = TtlCache<String, List<PlaylistTrack>>(LIBRARY_TTL_MS)

    /** Signing out and into another account must never serve the previous one's library. */
    private fun account(): String = GoogleAuth.accountName ?: "anonymous"

    override val supportsArtists = true
    override val supportsStations = true    // per-track radio via the RDAMVM mix
    override val supportsLibrary = true

    // YouTube exposes no discovery feed through the Data API; this tab is Wren's own
    // construct (radios seeded by local plays), so it is named for what it really is.
    override val discoverLabel = "radios"
    override val discoverEmptyHint = "youtube_has_no_discovery_feed — play_something_here_to_seed_radios"

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        YoutubeMusic.search(query, limit)

    override suspend fun searchArtists(query: String): List<ArtistResult> =
        YoutubeMusic.searchArtists(query)

    override suspend fun station(seed: SearchResult): List<SearchResult> {
        val radio = youtubeRadio(seed.videoId)
        return if (radio.isEmpty()) listOf(seed) else listOf(seed) + radio.filter { it.videoId != seed.videoId }
    }

    /**
     * The Data API has no personalised feed, so Discover is built from what the user
     * actually played here: one radio per recent distinct track, opened on demand.
     */
    override suspend fun discover(forceRefresh: Boolean): List<DiscoverSection> {
        val recent = ListeningHistory.recent(40, setOf(Source.YT_MUSIC, Source.YOUTUBE))
            .distinctBy { it.trackId }
            .take(10)
        if (recent.isEmpty()) return emptyList()
        val radios = recent.map {
            DiscoverCollection(
                id = it.trackId,
                title = it.title,
                subtitle = it.artist,
                artworkUrl = it.artworkUrl ?: "https://i.ytimg.com/vi/${it.trackId}/hqdefault.jpg"
            )
        }
        return listOf(DiscoverSection(
            title = "radios from recent plays",
            caption = "built by wren from your plays here — youtube offers no discovery feed",
            collections = radios
        ))
    }

    override suspend fun collectionTracks(collectionId: String): List<SearchResult> = youtubeRadio(collectionId)

    override suspend fun playlists(): List<Playlist> =
        playlists.getOrLoad(account()) { fetchUserPlaylists() }

    override suspend fun playlistTracks(playlistId: String): List<PlaylistTrack> =
        playlistTracks.getOrLoad("${'$'}{account()}|$playlistId") { fetchPlaylistTracks(playlistId) }

    override suspend fun librarySongs(): List<PlaylistTrack> =
        songs.getOrLoad(account()) { fetchLikedSongs() }

    override suspend fun libraryArtists(): List<ArtistResult> =
        artists.getOrLoad(account()) { fetchSubscribedChannels() }
}
