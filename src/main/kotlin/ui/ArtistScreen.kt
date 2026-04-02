package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.AlbumCard
import api.ArtistData
import api.fetchAlbumTracks
import api.fetchArtistPage
import api.resolveStreamUrl
import kotlinx.coroutines.launch
import player.MpvPlayer

@Composable
fun ArtistScreen(
    browseId: String,
    player: MpvPlayer,
    onBack: () -> Unit,
    onArtistClick: (browseId: String, name: String) -> Unit
) {
    var artistData by remember { mutableStateOf<ArtistData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedAlbum by remember { mutableStateOf<AlbumCard?>(null) }
    var albumTracks by remember { mutableStateOf<List<api.SearchResult>>(emptyList()) }
    var albumLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(browseId) {
        loading = true
        artistData = fetchArtistPage(browseId)
        loading = false
        artistData?.topSongs?.take(8)?.forEach { scope.launch { resolveStreamUrl(it.videoId) } }
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar — changes based on whether we're in album view
        Row(
            Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (selectedAlbum != null) {
                    selectedAlbum = null
                    albumTracks = emptyList()
                } else {
                    onBack()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = TextPrimary)
            }
            Spacer(Modifier.width(4.dp))
            val label = selectedAlbum?.title ?: artistData?.name
            if (label != null) {
                Text(label, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
            artistData == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No se pudo cargar el artista", color = TextSecondary, fontSize = 15.sp)
            }
            selectedAlbum != null -> {
                // Album track list
                if (albumLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        // Album header
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Thumbnail(
                                    selectedAlbum!!.thumbnailUrl ?: "",
                                    Modifier.size(80.dp).clip(RoundedCornerShape(6.dp))
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(
                                        selectedAlbum!!.title,
                                        color = TextPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (selectedAlbum!!.subtitle.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(selectedAlbum!!.subtitle, color = TextSecondary, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        if (albumTracks.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No hay canciones disponibles", color = TextSecondary, fontSize = 14.sp)
                                }
                            }
                        } else {
                            items(albumTracks.size) { index ->
                                Box(Modifier.padding(horizontal = 16.dp)) {
                                    TrackRow(albumTracks[index], index, albumTracks, player, onArtistClick = null)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                // Artist overview
                val data = artistData!!
                LazyColumn(Modifier.fillMaxSize()) {
                    // Artist header
                    item {
                        Box(Modifier.fillMaxWidth().height(220.dp)) {
                            if (data.thumbnailUrl != null) {
                                Thumbnail(data.thumbnailUrl, Modifier.fillMaxSize())
                            } else {
                                Box(Modifier.fillMaxSize().background(Surface))
                            }
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(listOf(Color.Transparent, Background))
                                ),
                                contentAlignment = Alignment.BottomStart
                            ) {
                                Text(
                                    data.name,
                                    color = TextPrimary,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }

                    // Top songs
                    if (data.topSongs.isNotEmpty()) {
                        item {
                            SectionHeader("Canciones populares")
                        }
                        items(data.topSongs.size) { index ->
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                TrackRow(data.topSongs[index], index, data.topSongs, player, onArtistClick = null)
                            }
                        }
                    }

                    // Release sections (Albums, Singles, EPs…)
                    for ((sectionTitle, cards) in data.releaseSections) {
                        item {
                            SectionHeader(sectionTitle)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(cards.size) { i ->
                                    AlbumCardItem(cards[i]) {
                                        selectedAlbum = cards[i]
                                        scope.launch {
                                            albumLoading = true
                                            albumTracks = fetchAlbumTracks(cards[i])
                                            albumLoading = false
                                            albumTracks.take(8).forEach {
                                                launch { resolveStreamUrl(it.videoId) }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        color = TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp)
    )
}

@Composable
private fun AlbumCardItem(album: AlbumCard, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp)
    ) {
        Thumbnail(album.thumbnailUrl ?: "", Modifier.size(140.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(6.dp))
        Text(
            album.title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (album.subtitle.isNotEmpty()) {
            Text(
                album.subtitle,
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
