package provider

import api.ListeningHistory
import api.PlayRecord
import api.YoutubeMusic
import api.Page
import api.allPages
import api.fetchLikedSongs
import api.fetchPlaylistTracks
import api.likedSongsPage
import api.musicArtists
import api.newReleasesFrom
import api.pagedFlow
import api.playCountsOf
import api.playlistTracksPage
import api.quickPicksFrom
import api.rankFollowedArtists
import api.subscriptionsPage
import api.fetchUserPlaylists
import api.youtubeRadio
import api.ytMusicCollectionShelves
import api.ytMusicCollectionTracks
import api.ytMusicExplore
import api.ytMusicHome
import auth.GoogleAuth
import models.Source
import models.ArtistResult
import models.Playlist
import models.PlaylistTrack
import models.SearchResult
import models.Shelf
import models.ShelfCard
import util.Log
import util.TtlCache
import kotlinx.coroutines.flow.Flow

/** Long enough to cover switching tabs, short enough that a like made elsewhere shows up. */
private const val LIBRARY_TTL_MS = 10 * 60 * 1000L

/** Home is assembled from the library and a handful of radios; a visit should not re-walk it. */
private const val HOME_TTL_MS = 15 * 60 * 1000L

/** How far back the release ranking and Quick picks look for a signal. */
private const val HISTORY_LOOKBACK = 200

/** Only tracks played through this provider seed a YouTube radio. */
private val YT_SOURCES = setOf(Source.YT_MUSIC, Source.YOUTUBE)

private const val TAG = "YtMusic"

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
    // Shared by the library's artist list and Home's release shelf, so both agree on who is followed
    // without a second walk of subscriptions.list.
    private val subscriptions = TtlCache<String, List<ArtistResult>>(LIBRARY_TTL_MS)
    private val home = TtlCache<String, List<Shelf>>(HOME_TTL_MS)

    /** Signing out and into another account must never serve the previous one's library. */
    private fun account(): String = GoogleAuth.accountName ?: "anonymous"

    override val supportsArtists = true
    override val supportsStations = true    // per-track radio via the RDAMVM mix
    override val supportsLibrary = true

    override val homeEmptyHint = "nothing_personal_yet — follow_artists_and_play_something_here"

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        YoutubeMusic.search(query, limit)

    override suspend fun searchArtists(query: String): List<ArtistResult> =
        YoutubeMusic.searchArtists(query)

    override suspend fun station(seed: SearchResult): List<SearchResult> {
        val radio = youtubeRadio(seed.videoId)
        return if (radio.isEmpty()) listOf(seed) else listOf(seed) + radio.filter { it.videoId != seed.videoId }
    }

    /**
     * Home is about the account, never a general feed. The InnerTube `FEmusic_home` call cannot carry
     * the Google token — it rejects tokens from clients it does not recognise, which is why its shelves
     * came back geographic — so a signed-in Home is assembled here from what only the account knows:
     * the artists it follows, the tracks it has played, and the library it saved. Editorial shelves are
     * left to guests, who have no follows to build from in the first place.
     */
    override suspend fun home(forceRefresh: Boolean): List<Shelf> {
        if (!isAuthenticated) return editorialHome()
        val key = account()
        if (forceRefresh) home.invalidate(key)
        return home.getOrLoad(key) { personalHome() }
    }

    private suspend fun editorialHome(): List<Shelf> =
        runCatching { ytMusicHome() }
            .onFailure { Log.w(TAG, "FEmusic_home failed", it) }
            .getOrDefault(emptyList())

    override suspend fun explore(): List<Shelf> =
        runCatching { ytMusicExplore() }
            .onFailure { Log.w(TAG, "FEmusic_explore failed", it) }
            .getOrDefault(emptyList())

    private suspend fun personalHome(): List<Shelf> {
        val played = ListeningHistory.recent(HISTORY_LOOKBACK, YT_SOURCES)
        val follows = runCatching { followedChannels() }
            .onFailure { Log.w(TAG, "subscriptions failed", it) }
            .getOrDefault(emptyList())
        val picks = runCatching { quickPicks(played) }
            .onFailure { Log.w(TAG, "quick picks failed", it) }
            .getOrDefault(emptyList())
        val releases = runCatching { newReleasesFrom(rankFollowedArtists(follows, playCountsOf(played))) }
            .onFailure { Log.w(TAG, "new releases failed", it) }
            .getOrDefault(emptyList())

        return listOfNotNull(
            picks.takeIf { it.isNotEmpty() }?.let {
                Shelf(
                    title = "quick picks",
                    caption = "radios wren built from what you play here",
                    tracks = it,
                )
            },
            releases.takeIf { it.isNotEmpty() }?.let {
                Shelf(
                    title = "new releases from your artists",
                    caption = "the artists this account follows",
                    cards = it,
                )
            },
            radiosFromRecentPlays(),
        )
    }

    /** Radios seeded by the last distinct tracks played here, plus the library when it is cached. */
    private suspend fun quickPicks(played: List<PlayRecord>): List<SearchResult> {
        val recent = played.distinctBy { it.trackId }.map { it.trackId }
        // The liked library is a better seed on a fresh account, but only when it is already cached:
        // Home must not trigger the full library walk just to fill a shelf the user can also get later.
        val liked = songs.peek(account()).orEmpty().map { it.videoId }
        // quickPicksFrom caps the seed count itself, so the recent plays always win the slots.
        return quickPicksFrom(recent + liked)
    }

    /**
     * The Data API has no personalised feed, so this half of Home is built from what the user
     * actually played here: one radio per recent distinct track, opened on demand.
     */
    private fun radiosFromRecentPlays(): Shelf? {
        val recent = ListeningHistory.recent(40, YT_SOURCES)
            .distinctBy { it.trackId }
            .take(10)
        if (recent.isEmpty()) return null
        val radios = recent.map {
            ShelfCard(
                id = it.trackId,
                title = it.title,
                subtitle = it.artist,
                artworkUrl = it.artworkUrl ?: "https://i.ytimg.com/vi/${it.trackId}/hqdefault.jpg"
            )
        }
        return Shelf(
            title = "radios from recent plays",
            caption = "built by wren from your plays here — youtube offers no discovery feed",
            cards = radios
        )
    }

    /** A card from Home or Explore is an album or playlist; anything else is a radio seed. */
    override suspend fun collectionTracks(collectionId: String): List<SearchResult> =
        if (isCollectionId(collectionId)) ytMusicCollectionTracks(collectionId)
        else youtubeRadio(collectionId)

    /** Mood and genre buttons, which open a page of playlists rather than a track list. */
    override suspend fun collectionShelves(collectionId: String): List<Shelf> =
        ytMusicCollectionShelves(collectionId)

    private fun isCollectionId(id: String): Boolean =
        id.contains('|') || id.startsWith("VL") || id.startsWith("MPREb") || id.startsWith("FEmusic")

    override suspend fun playlists(): List<Playlist> =
        playlists.getOrLoad(account()) { fetchUserPlaylists() }

    override suspend fun playlistTracks(playlistId: String): List<PlaylistTrack> =
        playlistTracks.getOrLoad(playlistKey(playlistId)) { fetchPlaylistTracks(playlistId) }

    override suspend fun librarySongs(): List<PlaylistTrack> =
        songs.getOrLoad(account()) { fetchLikedSongs() }

    override suspend fun libraryArtists(): List<ArtistResult> =
        artists.getOrLoad(account()) { musicArtists(followedChannels()) }

    /** Every channel this account follows; shared with Home, so both see the same list. */
    private suspend fun followedChannels(): List<ArtistResult> =
        subscriptions.getOrLoad(account()) { allPages(TAG) { subscriptionsPage(it) } }

    /**
     * The streaming counterparts of the listings above: one emission per page, so a screen fills
     * while the rest is still being walked. These have to be overridden — the interface default is
     * an empty list rather than a fetch, so a provider that does not implement them serves an
     * empty library and logs nothing while doing it.
     */
    override fun librarySongsFlow(): Flow<List<PlaylistTrack>> =
        pagedFlow(TAG, songs, account()) { cursor -> likedSongsPage(cursor) }

    override fun libraryArtistsFlow(): Flow<List<ArtistResult>> =
        pagedFlow(TAG, artists, account()) { cursor ->
            val page = subscriptionsPage(cursor)
            Page(musicArtists(page.items), page.next)
        }

    override fun playlistTracksFlow(playlistId: String): Flow<List<PlaylistTrack>> =
        pagedFlow(TAG, playlistTracks, playlistKey(playlistId)) { cursor ->
            playlistTracksPage(playlistId, cursor)
        }

    /** The account belongs in the key: a playlist id alone survives a sign-in to another account. */
    private fun playlistKey(playlistId: String): String = "${account()}|$playlistId"
}
