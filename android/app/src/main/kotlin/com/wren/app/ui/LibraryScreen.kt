package com.wren.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.SoundCloudLikes
import api.resolveStreamUrl
import provider.Platform
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import models.ArtistResult
import models.Playlist
import models.PlaylistTrack
import models.toQueueItem
import player.PlayerEngine
import provider.MusicProvider
import util.connectMessage
import util.runCatchingExceptCancellation

private enum class LibraryTab(val label: String) {
    SONGS("songs"),
    PLAYLISTS("playlists"),
    ARTISTS("artists"),
}

/**
 * The user's own collection for the active platform: liked songs, playlists and the
 * artists behind them. SoundCloud has no artist browsing, so that tab only shows up
 * for YouTube ([MusicProvider.supportsArtists]).
 */
@Composable
fun LibraryScreen(
    provider: MusicProvider,
    engine: PlayerEngine,
    onArtistSearch: (String) -> Unit,
) {
    val tabs = remember(provider) {
        LibraryTab.entries.filter { it != LibraryTab.ARTISTS || provider.supportsArtists }
    }
    var tab by remember(provider) { mutableStateOf(tabs.first()) }
    var songs by remember(provider) { mutableStateOf<List<PlaylistTrack>?>(null) }
    var playlists by remember(provider) { mutableStateOf<List<Playlist>?>(null) }
    var artists by remember(provider) { mutableStateOf<List<ArtistResult>?>(null) }
    var selectedPlaylist by remember(provider) { mutableStateOf<Playlist?>(null) }
    var tracks by remember(provider) { mutableStateOf<List<PlaylistTrack>?>(null) }
    var loading by remember(provider) { mutableStateOf(false) }
    // A fetch that failed leaves its list null and shows this instead: an empty list is a fact the
    // user cannot tell apart from a request that never arrived.
    var songsError by remember(provider) { mutableStateOf<String?>(null) }
    var playlistsError by remember(provider) { mutableStateOf<String?>(null) }
    var artistsError by remember(provider) { mutableStateOf<String?>(null) }
    var tracksError by remember(provider) { mutableStateOf<String?>(null) }
    var retry by remember(provider) { mutableStateOf(0) }
    var tracksJob by remember(provider) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val queue by engine.queue.collectAsState()
    val queueIndex by engine.queueIndex.collectAsState()
    val liked by SoundCloudLikes.liked.collectAsState()
    val canLike = provider.platform == Platform.SOUNDCLOUD && provider.isAuthenticated
    val onLike: ((PlaylistTrack) -> Unit)? = if (canLike) {
        { track -> scope.launch { SoundCloudLikes.toggle(track.url) } }
    } else null
    val currentId = queue.getOrNull(queueIndex)?.videoId

    if (!provider.isAuthenticated) {
        LibraryNotice("sign in to see your ${provider.platform.label} library")
        return
    }
    if (!provider.supportsLibrary) {
        LibraryNotice("${provider.platform.label} library not available yet")
        return
    }

    LaunchedEffect(provider, tab, retry) {
        if (tab == LibraryTab.SONGS && songs == null) {
            loading = true
            var prefetched = false
            provider.librarySongsFlow()
                .catch { failure -> songsError = failure.connectMessage(); loading = false }
                .collect { page ->
                    songs = page
                    loading = false
                    // Streaming URLs for what is on screen, once: later pages keep their own.
                    if (!prefetched) {
                        prefetched = true
                        page.take(8).forEach { launch { resolveStreamUrl(it.videoId) } }
                    }
                }
        }
        if (tab == LibraryTab.PLAYLISTS && playlists == null) {
            loading = true
            val result = runCatchingExceptCancellation { provider.playlists() }
            playlists = result.getOrNull()
            playlistsError = result.exceptionOrNull()?.connectMessage()
            loading = false
        }
        if (tab == LibraryTab.ARTISTS && artists == null) {
            loading = true
            provider.libraryArtistsFlow()
                .catch { failure -> artistsError = failure.connectMessage(); loading = false }
                .collect { page -> artists = page; loading = false }
        }
    }

    fun loadPlaylistTracks(playlist: Playlist) {
        tracksJob?.cancel()
        tracksJob = scope.launch {
            loading = true
            provider.playlistTracksFlow(playlist.id)
                .catch { failure -> tracksError = failure.connectMessage(); loading = false }
                .collect { page -> tracks = page; loading = false }
        }
    }

    // Back leaves an open playlist before it leaves the tab.
    BackHandler(enabled = selectedPlaylist != null) { selectedPlaylist = null; tracks = null }

    Column(Modifier.fillMaxSize()) {
        val open = selectedPlaylist
        if (open != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedPlaylist = null; tracks = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text(
                    open.title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tracksError != null) {
                LibraryError(tracksError!!) { loadPlaylistTracks(open) }
            } else {
                PlaylistTrackList(tracks, loading, engine, currentId, "this playlist is empty", queueTitle = open.title, liked = liked, onLike = onLike)
            }
        } else {
            LibraryTabSelector(tabs, tab) { tab = it }
            val error = when (tab) {
                LibraryTab.SONGS -> songsError
                LibraryTab.PLAYLISTS -> playlistsError
                LibraryTab.ARTISTS -> artistsError
            }
            if (error != null) {
                LibraryError(error) { retry++ }
            } else when (tab) {
                LibraryTab.SONGS -> PlaylistTrackList(songs, loading, engine, currentId, "no liked songs yet", queueTitle = "liked songs", liked = liked, onLike = onLike)
                LibraryTab.PLAYLISTS -> PlaylistList(playlists, loading) { playlist ->
                    selectedPlaylist = playlist
                    loadPlaylistTracks(playlist)
                }
                LibraryTab.ARTISTS -> ArtistList(artists, loading, onArtistSearch)
            }
        }
    }
}

/** A failed fetch, with the way out: the list stays null, so retrying is the only exit. */
@Composable
private fun LibraryError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                color = PsSignalDanger,
                fontFamily = FontMono,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRetry) {
                Text("retry;", color = TextPrimary, fontFamily = FontMono, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LibraryNotice(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = TextSecondary,
            fontFamily = FontMono,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LibraryTabSelector(
    tabs: List<LibraryTab>,
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
) {
    Segmented(
        options = tabs,
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) { candidate, active -> SegmentLabel(candidate.label, active) }
}

@Composable
private fun PlaylistTrackList(
    list: List<PlaylistTrack>?,
    loading: Boolean,
    engine: PlayerEngine,
    currentId: String?,
    emptyHint: String,
    queueTitle: String,
    liked: Set<String>,
    onLike: ((PlaylistTrack) -> Unit)?,
) {
    when {
        // A null list has not arrived yet: showing the empty hint there tells the user their
        // library is empty while it is still being fetched.
        loading || list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
        }
        list.isEmpty() -> LibraryNotice(emptyHint)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list, key = { index, item -> "${item.source}:${item.videoId}:$index" }) { index, item ->
                TrackRow(
                    title = item.title,
                    subtitle = listOf(item.channelTitle, item.duration)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    artworkUrl = item.thumbnailUrl.ifBlank { null },
                    highlight = item.videoId == currentId,
                    onClick = { engine.loadQueue(list.map { it.toQueueItem() }, index, queueTitle) },
                    liked = if (onLike != null) item.url in liked else null,
                    onLike = onLike?.let { like -> { like(item) } },
                )
            }
        }
    }
}

@Composable
private fun PlaylistList(list: List<Playlist>?, loading: Boolean, onOpen: (Playlist) -> Unit) {
    when {
        loading || list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
        }
        list.isEmpty() -> LibraryNotice("no playlists found")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { playlist ->
                TrackRow(
                    title = playlist.title,
                    subtitle = playlist.owner?.let { "${playlist.itemCount} songs · $it" }
                        ?: "${playlist.itemCount} songs",
                    artworkUrl = playlist.thumbnailUrl.ifBlank { null },
                    onClick = { onOpen(playlist) },
                )
            }
        }
    }
}

@Composable
private fun ArtistList(
    list: List<ArtistResult>?,
    loading: Boolean,
    onArtistSearch: (String) -> Unit,
) {
    when {
        loading || list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
        }
        list.isEmpty() -> LibraryNotice("no followed artists")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.browseId }) { artist ->
                TrackRow(
                    title = artist.name,
                    subtitle = artist.subtitle,
                    artworkUrl = artist.thumbnailUrl,
                    onClick = { onArtistSearch(artist.name) },
                    onAction = { onArtistSearch(artist.name) },
                    actionIcon = Icons.Default.Search,
                    actionDescription = "Search this artist",
                )
            }
        }
    }
}
