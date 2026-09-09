package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.GeneratedDiscovery
import api.SoundCloudDiscovery
import kotlinx.coroutines.launch
import player.FFmpegPlayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@Composable
fun DiscoverScreen(player: FFmpegPlayer) {
    var discovery by remember { mutableStateOf<GeneratedDiscovery?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (discovery == null && !loading) {
            loading = true
            error = false
            runCatching { discovery = SoundCloudDiscovery.current() }
                .onFailure { error = true }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("weekly discovery;", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                if (loading) return@IconButton
                loading = true
                error = false
                scope.launch {
                    runCatching { discovery = SoundCloudDiscovery.refresh() }
                        .onFailure { error = true }
                    loading = false
                }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
            }
        }

        // Metadata
        discovery?.let { d ->
            val generatedDate = Instant.ofEpochMilli(d.generatedAt)
                .atZone(ZoneId.systemDefault())
                .format(dateFormatter)
            Text("// generated $generatedDate;", color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp)
            val basisText = d.basisGenres.joinToString(", ")
            if (basisText.isNotEmpty()) {
                Text("// based_on: $basisText;", color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading && discovery == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PsInk900)
            }
            error && discovery == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("// soundcloud_unavailable;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
            }
            else -> {
                val tracks = discovery?.tracks ?: emptyList()
                if (tracks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("// cold_start_creating_playlist;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        items(tracks.size) { index ->
                            TrackRow(tracks[index], index, tracks, player, onArtistClick = null)
                        }
                    }
                }
            }
        }
    }
}
