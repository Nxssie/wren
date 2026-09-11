package provider

import models.ArtistResult
import models.Playlist
import models.PlaylistTrack
import models.SearchResult
import models.Shelf
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

/**
 * Everything a screen needs from a platform. Browsing (home, search, explore, library) is
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

    /** Shown when [home] returns nothing; says honestly where the content comes from. */
    val homeEmptyHint: String get() = "nothing_to_show_yet"
    /** Shown when [explore] returns nothing; says honestly where the content comes from. */
    val exploreEmptyHint: String get() = "nothing_to_explore_yet"

    suspend fun search(query: String, limit: Int = 20): List<SearchResult>
    suspend fun searchArtists(query: String): List<ArtistResult> = emptyList()

    /** Radio seeded by [seed]; the seed itself comes first. Empty when unsupported. */
    suspend fun station(seed: SearchResult): List<SearchResult> = emptyList()

    /** What the platform puts in front of the user. [forceRefresh] bypasses any weekly/daily cache. */
    suspend fun home(forceRefresh: Boolean = false): List<Shelf> = emptyList()

    /** Curated, trending and browsable shelves. Empty when unsupported. */
    suspend fun explore(): List<Shelf> = emptyList()

    /** Tracks behind a [ShelfCard] whose kind is [ShelfCardKind.TRACKS]. */
    suspend fun collectionTracks(collectionId: String): List<SearchResult> = emptyList()

    /** Shelves behind a [ShelfCard] whose kind is [ShelfCardKind.SHELVES] — a mood or genre page. */
    suspend fun collectionShelves(collectionId: String): List<Shelf> = emptyList()

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
