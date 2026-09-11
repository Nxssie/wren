package provider

import models.ArtistResult
import models.Playlist
import models.PlaylistTrack
import models.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import models.Source

/** A streaming platform the user can browse. Sessions are per-platform, the queue is shared. */
enum class Platform(val code: String, val label: String) {
    YOUTUBE("YT", "youtube"),
    SOUNDCLOUD("SC", "soundcloud");

    companion object {
        fun of(source: Source): Platform = when (source) {
            Source.YT_MUSIC, Source.YOUTUBE -> YOUTUBE
            Source.SOUNDCLOUD -> SOUNDCLOUD
        }
    }
}

/** A playlist-like card (mix, station, curated playlist). Opened through [MusicProvider.collectionTracks]. */
data class DiscoverCollection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null
)

/**
 * One block on the Discover screen. Either a flat track list, a row of collections,
 * or both — the screen renders whatever is non-empty.
 */
data class DiscoverSection(
    val title: String,
    val caption: String? = null,
    val tracks: List<SearchResult> = emptyList(),
    val collections: List<DiscoverCollection> = emptyList()
)

/**
 * Everything a screen needs from a platform. Browsing (search, discover, library) is
 * scoped to one provider at a time; playback stays platform-agnostic through QueueItem.
 *
 * Capabilities differ per platform — screens must check the `supports*` flags instead
 * of assuming, and the unsupported calls return empty results rather than throwing.
 */
interface MusicProvider {
    val platform: Platform
    val isAuthenticated: Boolean

    val supportsArtists: Boolean
    val supportsStations: Boolean
    val supportsLibrary: Boolean

    /** What the Discover tab is called for this platform — it must not overclaim. */
    val discoverLabel: String get() = "discover"
    /** Shown when [discover] returns nothing; says honestly where the content comes from. */
    val discoverEmptyHint: String get() = "nothing_to_discover_yet"

    suspend fun search(query: String, limit: Int = 20): List<SearchResult>
    suspend fun searchArtists(query: String): List<ArtistResult> = emptyList()

    /** Radio seeded by [seed]; the seed itself comes first. Empty when unsupported. */
    suspend fun station(seed: SearchResult): List<SearchResult> = emptyList()

    /** Personalised or curated sections. [forceRefresh] bypasses any weekly/daily cache. */
    suspend fun discover(forceRefresh: Boolean = false): List<DiscoverSection> = emptyList()

    /** Tracks behind a [DiscoverCollection.id] from this provider's discover sections. */
    suspend fun collectionTracks(collectionId: String): List<SearchResult> = emptyList()

    suspend fun playlists(): List<Playlist> = emptyList()
    suspend fun playlistTracks(playlistId: String): List<PlaylistTrack> = emptyList()

    /** The user's liked tracks. Empty when unsupported or unauthenticated. */
    suspend fun librarySongs(): List<PlaylistTrack> = emptyList()
    /** Artists behind the user's library (follows/subscriptions). Empty when unsupported. */
    suspend fun libraryArtists(): List<ArtistResult> = emptyList()

    /**
     * The library as it arrives: the cached copy in one emission when it is fresh, otherwise one
     * emission per page, so the screen fills while the rest is still being walked.
     */
    fun librarySongsFlow(): Flow<List<PlaylistTrack>> = flowOf(emptyList())
    fun libraryArtistsFlow(): Flow<List<ArtistResult>> = flowOf(emptyList())
    fun playlistTracksFlow(playlistId: String): Flow<List<PlaylistTrack>> = flowOf(emptyList())
}

object Providers {
    val all: List<MusicProvider> get() = listOf(YouTubeProvider, SoundCloudProvider)

    fun of(platform: Platform): MusicProvider = when (platform) {
        Platform.YOUTUBE -> YouTubeProvider
        Platform.SOUNDCLOUD -> SoundCloudProvider
    }

    fun of(source: Source): MusicProvider = of(Platform.of(source))
}
