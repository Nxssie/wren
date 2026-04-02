package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.Playlist
import api.PlaylistTrack
import api.fetchPlaylistTracks
import api.fetchUserPlaylists
import api.resolveStreamUrl
import auth.AuthManager
import kotlinx.coroutines.launch
import player.MpvPlayer
import player.QueueItem

@Composable
fun LibraryScreen(player: MpvPlayer) {
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var tracks by remember { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (!AuthManager.isAuthenticated) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Inicia sesión para ver tu biblioteca", color = TextSecondary, fontSize = 15.sp)
        }
        return
    }

    LaunchedEffect(Unit) {
        loading = true
        playlists = fetchUserPlaylists()
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        if (selectedPlaylist != null) {
            Row(
                Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedPlaylist = null; tracks = emptyList() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = TextPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Text(selectedPlaylist!!.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(tracks.size) { index -> PlaylistTrackRow(tracks[index], index, tracks, player) }
                }
            }
        } else {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            } else if (playlists?.isEmpty() == true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No se encontraron playlists", color = TextSecondary, fontSize = 15.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(playlists ?: emptyList()) { playlist ->
                        PlaylistRow(playlist) {
                            selectedPlaylist = playlist
                            scope.launch {
                                loading = true
                                tracks = fetchPlaylistTracks(playlist.id)
                                loading = false
                                tracks.take(8).forEach { launch { resolveStreamUrl(it.videoId) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(playlist.thumbnailUrl, Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${playlist.itemCount} canciones", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlaylistTrackRow(track: PlaylistTrack, index: Int, tracks: List<PlaylistTrack>, player: MpvPlayer) {
    val currentTitle by player.currentTitle
    val active = currentTitle == track.videoId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Surface else Color.Transparent)
            .clickable {
                player.loadQueue(tracks.map { QueueItem(it.url, it.videoId, it.title) }, index)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(track.thumbnailUrl, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = if (active) Accent else TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.channelTitle, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.duration.isNotEmpty()) {
            Text(track.duration, color = TextSecondary, fontSize = 12.sp)
        }
    }
}
