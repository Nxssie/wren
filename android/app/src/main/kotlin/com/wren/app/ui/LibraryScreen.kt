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
import api.resolveStreamUrl
import kotlinx.coroutines.launch
import models.ArtistResult
import models.Playlist
import models.PlaylistTrack
import models.toQueueItem
import player.PlayerEngine
import provider.MusicProvider

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
    var tracks by remember(provider) { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    var loading by remember(provider) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val queue by engine.queue.collectAsState()
    val queueIndex by engine.queueIndex.collectAsState()
    val currentId = queue.getOrNull(queueIndex)?.videoId

    if (!provider.isAuthenticated) {
        LibraryNotice("sign in to see your ${provider.platform.label} library")
        return
    }
    if (!provider.supportsLibrary) {
        LibraryNotice("${provider.platform.label} library not available yet")
        return
    }

    LaunchedEffect(provider, tab) {
        if (tab == LibraryTab.SONGS && songs == null) {
            loading = true
            songs = runCatching { provider.librarySongs() }.getOrDefault(emptyList())
            loading = false
            songs?.take(8)?.forEach { launch { resolveStreamUrl(it.videoId) } }
        }
        if (tab == LibraryTab.PLAYLISTS && playlists == null) {
            loading = true
            playlists = runCatching { provider.playlists() }.getOrDefault(emptyList())
            loading = false
        }
        if (tab == LibraryTab.ARTISTS && artists == null) {
            loading = true
            artists = runCatching { provider.libraryArtists() }.getOrDefault(emptyList())
            loading = false
        }
    }

    // Back leaves an open playlist before it leaves the tab.
    BackHandler(enabled = selectedPlaylist != null) { selectedPlaylist = null; tracks = emptyList() }

    Column(Modifier.fillMaxSize()) {
        val open = selectedPlaylist
        if (open != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedPlaylist = null; tracks = emptyList() }) {
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
            PlaylistTrackList(tracks, loading, engine, currentId, "this playlist is empty", queueTitle = open.title)
        } else {
            LibraryTabSelector(tabs, tab) { tab = it }
            when (tab) {
                LibraryTab.SONGS -> PlaylistTrackList(songs, loading, engine, currentId, "no liked songs yet", queueTitle = "liked songs")
                LibraryTab.PLAYLISTS -> PlaylistList(playlists, loading) { playlist ->
                    selectedPlaylist = playlist
                    scope.launch {
                        loading = true
                        tracks = runCatching { provider.playlistTracks(playlist.id) }.getOrDefault(emptyList())
                        loading = false
                    }
                }
                LibraryTab.ARTISTS -> ArtistList(artists, loading, onArtistSearch)
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
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
        }
        list.isNullOrEmpty() -> LibraryNotice(emptyHint)
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
                )
            }
        }
    }
}

@Composable
private fun PlaylistList(list: List<Playlist>?, loading: Boolean, onOpen: (Playlist) -> Unit) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
        }
        list.isNullOrEmpty() -> LibraryNotice("no playlists found")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { playlist ->
                TrackRow(
                    title = playlist.title,
                    subtitle = "${playlist.itemCount} songs",
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
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
        }
        list.isNullOrEmpty() -> LibraryNotice("no followed artists")
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
