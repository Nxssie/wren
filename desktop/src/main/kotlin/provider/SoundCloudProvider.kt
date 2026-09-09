package provider

import api.SoundCloud
import api.SoundCloudDiscovery
import auth.SoundCloudAuth
import models.Playlist
import models.PlaylistTrack
import models.SearchResult
import models.Source
import util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SoundCloudProvider : MusicProvider {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override val platform = Platform.SOUNDCLOUD
    override val isAuthenticated: Boolean get() = SoundCloudAuth.isAuthenticated

    override val supportsArtists = false
    override val supportsStations = true
    override val supportsLibrary = true

    private const val LIKES_ID = "likes"

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

    // ── Library: likes + own playlists, needs the session's user id ─────────

    override suspend fun playlists(): List<Playlist> {
        val userId = SoundCloudAuth.userId ?: return emptyList()
        val likes = Playlist(id = LIKES_ID, title = "Liked tracks", itemCount = 0, thumbnailUrl = SoundCloudAuth.avatarUrl ?: "")
        val own = runCatching { SoundCloud.userPlaylists(userId) }.getOrDefault(emptyList())
        return listOf(likes) + own
    }

    override suspend fun playlistTracks(playlistId: String): List<PlaylistTrack> {
        val userId = SoundCloudAuth.userId ?: return emptyList()
        val tracks = if (playlistId == LIKES_ID) SoundCloud.userLikes(userId) else SoundCloud.collectionTracks(playlistId)
        return tracks.map {
            PlaylistTrack(
                videoId = it.videoId,
                title = it.title,
                channelTitle = it.artist,
                thumbnailUrl = it.thumbnailUrl,
                duration = it.duration,
                source = Source.SOUNDCLOUD
            )
        }
    }
}
