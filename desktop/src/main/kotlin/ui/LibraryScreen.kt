package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import models.ArtistResult
import models.Playlist
import models.PlaylistTrack
import api.resolveStreamUrl
import provider.MusicProvider
import kotlinx.coroutines.launch
import player.FFmpegPlayer
import models.toQueueItem
import util.connectMessage
import util.runCatchingExceptCancellation

private enum class LibraryTab(val code: String, val label: String) {
    SONGS("SNG", "songs"),
    PLAYLISTS("PLS", "playlists"),
    ARTISTS("ART", "artists");

    companion object {
        fun forProvider(provider: MusicProvider): List<LibraryTab> =
            entries.filter { it != ARTISTS || provider.supportsArtists }
    }
}

@Composable
fun LibraryScreen(
    provider: MusicProvider,
    player: FFmpegPlayer,
    onArtistClick: (browseId: String, name: String) -> Unit
) {
    val tabs = remember(provider) { LibraryTab.forProvider(provider) }
    var tab by remember { mutableStateOf(tabs.first()) }

    var songs by remember { mutableStateOf<List<PlaylistTrack>?>(null) }
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var artists by remember { mutableStateOf<List<ArtistResult>?>(null) }
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var tracks by remember { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    // A fetch that failed leaves its list null and shows this instead: an empty list is a fact
    // the user cannot tell apart from a request that never arrived.
    var songsError by remember { mutableStateOf<String?>(null) }
    var playlistsError by remember { mutableStateOf<String?>(null) }
    var artistsError by remember { mutableStateOf<String?>(null) }
    var tracksError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    if (!provider.supportsLibrary) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("// ${provider.platform.label}_library_not_available_yet;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
        }
        return
    }
    if (!provider.isAuthenticated) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("// ${provider.platform.label}_sign_in_required;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
        }
        return
    }

    LaunchedEffect(tab, retry) {
        if (tab == LibraryTab.SONGS && songs == null) {
            loading = true
            val result = runCatchingExceptCancellation { provider.librarySongs() }
            songs = result.getOrNull()
            songsError = result.exceptionOrNull()?.connectMessage()
            loading = false
            songs?.take(8)?.forEach { launch { resolveStreamUrl(it.videoId) } }
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
            val result = runCatchingExceptCancellation { provider.libraryArtists() }
            artists = result.getOrNull()
            artistsError = result.exceptionOrNull()?.connectMessage()
            loading = false
        }
    }

    fun loadPlaylistTracks(playlist: Playlist) {
        scope.launch {
            loading = true
            val result = runCatchingExceptCancellation { provider.playlistTracks(playlist.id) }
            tracks = result.getOrNull().orEmpty()
            tracksError = result.exceptionOrNull()?.connectMessage()
            loading = false
            tracks.take(8).forEach { launch { resolveStreamUrl(it.videoId) } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val openPlaylist = selectedPlaylist
        if (openPlaylist != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = PsPearl200,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1f
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedPlaylist = null; tracks = emptyList() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Text(openPlaylist.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            if (tracksError != null) {
                LibraryError(tracksError!!) { loadPlaylistTracks(openPlaylist) }
            } else if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PsInk900)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(tracks.size) { index -> PlaylistTrackRow(tracks[index], index, tracks, player) }
                }
            }
            return@Column
        }

        LibraryTabs(tabs, tab) { tab = it }

        val error = when (tab) {
            LibraryTab.SONGS -> songsError
            LibraryTab.PLAYLISTS -> playlistsError
            LibraryTab.ARTISTS -> artistsError
        }
        if (error != null) {
            LibraryError(error) { retry++ }
        } else when (tab) {
            LibraryTab.SONGS -> TrackList(songs, loading, player, "// no_liked_songs;")
            LibraryTab.PLAYLISTS -> PlaylistList(playlists, loading) { playlist ->
                selectedPlaylist = playlist
                loadPlaylistTracks(playlist)
            }
            LibraryTab.ARTISTS -> ArtistList(artists, loading, onArtistClick)
        }
    }
}

/** A failed fetch, with the way out: the list stays null, so retrying is the only exit. */
@Composable
private fun LibraryError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = PsSignalDanger, fontSize = 12.sp, fontFamily = FontMono)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRetry) {
                Text("retry;", color = TextPrimary, fontSize = 12.sp, fontFamily = FontMono)
            }
        }
    }
}

@Composable
private fun LibraryTabs(
    tabs: List<LibraryTab>,
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(if (globalDark) PsGraphite700 else PsPearl100)
            .padding(2.dp)
    ) {
        tabs.forEach { item ->
            val active = item == selected
            Row(
                Modifier
                    .weight(1f)
                    .background(if (active) PsWhite else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.code,
                    color = if (active) PsInk900 else PsSteel400,
                    fontFamily = FontMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    item.label,
                    color = if (active) PsInk900 else PsSteel400,
                    fontFamily = FontMono,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp
                )
            }
        }
    }
}

@Composable
private fun TrackList(list: List<PlaylistTrack>?, loading: Boolean, player: FFmpegPlayer, emptyHint: String) {
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsInk900)
        }
    } else if (list.isNullOrEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyHint, color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(list.size) { index -> PlaylistTrackRow(list[index], index, list, player) }
        }
    }
}

@Composable
private fun PlaylistList(list: List<Playlist>?, loading: Boolean, onOpen: (Playlist) -> Unit) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsInk900)
        }
        list.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("// no_playlists_found;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
        }
        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
            items(list) { playlist -> PlaylistRow(playlist) { onOpen(playlist) } }
        }
    }
}

@Composable
private fun ArtistList(list: List<ArtistResult>?, loading: Boolean, onArtistClick: (browseId: String, name: String) -> Unit) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsInk900)
        }
        list.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("// no_followed_artists;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
        }
        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
            items(list) { artist -> ArtistRow(artist, onArtistClick) }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = PsPearl200,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(playlist.thumbnailUrl, Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playlist.owner?.let { "${playlist.itemCount} songs · $it" } ?: "${playlist.itemCount} songs",
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlaylistTrackRow(track: PlaylistTrack, index: Int, tracks: List<PlaylistTrack>, player: FFmpegPlayer) {
    val currentTitle by player.currentTitle
    val active = currentTitle == track.videoId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (active) {
                    drawRect(
                        color = PsIrisCyan,
                        topLeft = Offset(0f, 0f),
                        size = Size(4.dp.toPx(), size.height)
                    )
                    drawRect(
                        color = PsInset,
                        topLeft = Offset(0f, 0f),
                        size = size
                    )
                }
                // Bottom hairline
                drawLine(
                    color = PsPearl200,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            }
            .background(Color.Transparent)
            .clickable {
                player.loadQueue(tracks.map { it.toQueueItem() }, index)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(track.thumbnailUrl, Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.channelTitle, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.duration.isNotEmpty()) {
            Text(track.duration, color = PsSteel400, fontSize = 12.sp)
        }
    }
}
