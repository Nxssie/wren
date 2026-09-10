package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.resolveStreamUrl
import kotlinx.coroutines.launch
import models.SearchResult
import models.toQueueItem
import player.FFmpegPlayer
import provider.DiscoverCollection
import provider.DiscoverSection
import provider.MusicProvider

/**
 * Platform-scoped discovery. Sections come from the provider; a section may hold a flat
 * track list, a row of collections (mixes, radios, curated playlists), or both.
 * Tapping a collection opens it inline with its own back button.
 */
@Composable
fun DiscoverScreen(provider: MusicProvider, player: FFmpegPlayer) {
    var sections by remember { mutableStateOf<List<DiscoverSection>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var openCollection by remember { mutableStateOf<DiscoverCollection?>(null) }
    var collectionTracks by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var collectionLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load(force: Boolean) {
        if (loading) return
        loading = true
        error = false
        scope.launch {
            runCatching { sections = provider.discover(forceRefresh = force) }
                .onFailure { error = true }
            loading = false
        }
    }

    fun open(collection: DiscoverCollection) {
        openCollection = collection
        collectionTracks = emptyList()
        collectionLoading = true
        scope.launch {
            collectionTracks = runCatching { provider.collectionTracks(collection.id) }.getOrDefault(emptyList())
            collectionLoading = false
            collectionTracks.take(6).forEach { launch { resolveStreamUrl(it.videoId) } }
        }
    }

    LaunchedEffect(Unit) { if (sections == null) load(force = false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        val opened = openCollection
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (opened != null) {
                IconButton(onClick = { openCollection = null }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(opened.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    opened.subtitle?.let { Text("// $it;", color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (collectionTracks.isNotEmpty()) {
                    Text(
                        "play_all;",
                        color = TextPrimary, fontFamily = FontMono, fontSize = 11.sp,
                        modifier = Modifier
                            .background(PsInset)
                            .clickable { player.loadQueue(collectionTracks.map { it.toQueueItem() }, 0) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                Text("${provider.platform.label} ${provider.discoverLabel};", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { load(force = true) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (opened != null) {
            when {
                collectionLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PsInk900)
                }
                collectionTracks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("// no_playable_tracks;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
                }
                else -> LazyColumn {
                    items(collectionTracks.size, key = { collectionTracks[it].videoId }) { index ->
                        TrackRow(collectionTracks[index], index, collectionTracks, player, onArtistClick = null)
                    }
                }
            }
            return@Column
        }

        val current = sections
        when {
            loading && current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PsInk900)
            }
            error && current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("// ${provider.platform.label}_unavailable;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
            }
            current.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("// ${provider.discoverEmptyHint};", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                current.forEachIndexed { sIdx, section ->
                    item(key = "header-$sIdx") {
                        Column(Modifier.padding(top = if (sIdx == 0) 0.dp else 18.dp, bottom = 6.dp)) {
                            Text("${section.title};", color = PsSteel400, fontFamily = FontMono, fontSize = 10.sp, letterSpacing = 1.7.sp)
                            section.caption?.let { Text("// $it;", color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp) }
                        }
                    }
                    if (section.collections.isNotEmpty()) {
                        item(key = "collections-$sIdx") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(section.collections.size, key = { section.collections[it].id }) { i ->
                                    CollectionCard(section.collections[i]) { open(section.collections[i]) }
                                }
                            }
                        }
                    }
                    if (section.tracks.isNotEmpty()) {
                        items(section.tracks.size, key = { "$sIdx-${section.tracks[it].videoId}" }) { index ->
                            TrackRow(section.tracks[index], index, section.tracks, player, onArtistClick = null)
                        }
                    } else if (section.collections.isEmpty()) {
                        item(key = "empty-$sIdx") {
                            Text("// cold_start_creating_playlist;", color = PsSteel400, fontSize = 12.sp, fontFamily = FontMono,
                                modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(collection: DiscoverCollection, onClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        Thumbnail(collection.artworkUrl ?: "", Modifier.size(140.dp))
        Spacer(Modifier.height(6.dp))
        Text(collection.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        collection.subtitle?.let {
            Text(it, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
