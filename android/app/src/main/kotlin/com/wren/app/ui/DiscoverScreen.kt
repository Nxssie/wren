package com.wren.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import models.toQueueItem
import player.PlayerEngine
import provider.DiscoverCollection
import provider.DiscoverSection
import provider.MusicProvider

@Composable
fun DiscoverScreen(provider: MusicProvider, engine: PlayerEngine) {
    var sections by remember(provider) { mutableStateOf<List<DiscoverSection>>(emptyList()) }
    var loading by remember(provider) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(provider) {
        loading = true
        sections = runCatching { provider.discover() }.getOrDefault(emptyList())
        loading = false
    }

    fun openCollection(id: String) {
        scope.launch {
            val tracks = runCatching { provider.collectionTracks(id) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) engine.loadQueue(tracks.map { it.toQueueItem() }, 0)
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
        }
        sections.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                provider.discoverEmptyHint.replace('_', ' '),
                color = TextSecondary,
                fontFamily = FontMono,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            sections.forEach { section ->
                item(key = "header:${section.title}") { SectionHeader(section) }
                if (section.collections.isNotEmpty()) {
                    item(key = "collections:${section.title}") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(section.collections, key = { it.id }) { collection ->
                                CollectionCard(collection) { openCollection(collection.id) }
                            }
                        }
                    }
                }
                itemsIndexed(
                    section.tracks,
                    key = { index, item -> "${section.title}:${item.source}:${item.videoId}:$index" },
                ) { index, item ->
                    TrackRow(
                        title = item.title,
                        subtitle = item.subtitleText(),
                        artworkUrl = item.thumbnailUrl.ifBlank { null },
                        onClick = { engine.loadQueue(section.tracks.map { it.toQueueItem() }, index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: DiscoverSection) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(section.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        section.caption?.let {
            Text(it.replace('_', ' '), color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CollectionCard(collection: DiscoverCollection, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick)) {
        Artwork(collection.artworkUrl, Modifier.size(140.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            collection.title,
            color = TextPrimary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        collection.subtitle?.let {
            Text(it, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
