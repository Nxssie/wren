package com.wren.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import models.Shelf
import models.ShelfCard
import models.ShelfCardKind
import models.toQueueItem
import player.PlayerEngine
import provider.MusicProvider
import util.runCatchingExceptCancellation

/** A card that opened another feed rather than a track list. `null` shelves means "still loading". */
private data class OpenShelves(val card: ShelfCard, val shelves: List<Shelf>?)

/**
 * A provider feed rendered as shelves. Home and Explore are the same screen with a different
 * fetch, so they share this one.
 *
 * A track card goes straight into the queue; a mood or genre card opens another feed, so those
 * are a stack and back leaves one level at a time.
 *
 * A `null` shelf list means "not loaded yet", which is not the same as an empty one — the empty
 * state only appears once the fetch has actually come back with nothing.
 */
@Composable
private fun FeedScreen(
    provider: MusicProvider,
    engine: PlayerEngine,
    emptyHint: String,
    load: suspend () -> List<Shelf>,
) {
    var shelves by remember(provider) { mutableStateOf<List<Shelf>?>(null) }
    val opened = remember(provider) { mutableStateListOf<OpenShelves>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(provider) {
        shelves = runCatchingExceptCancellation { load() }.getOrDefault(emptyList())
    }

    fun open(card: ShelfCard) {
        when (card.kind) {
            ShelfCardKind.TRACKS -> scope.launch {
                val tracks = runCatching { provider.collectionTracks(card.id) }.getOrDefault(emptyList())
                if (tracks.isNotEmpty()) engine.loadQueue(tracks.map { it.toQueueItem() }, 0, card.title)
            }
            ShelfCardKind.SHELVES -> {
                val index = opened.size
                opened.add(OpenShelves(card, null))
                scope.launch {
                    val nested = runCatching { provider.collectionShelves(card.id) }.getOrDefault(emptyList())
                    opened[index] = OpenShelves(card, nested)
                }
            }
        }
    }

    // Screens register their own back handlers and win while enabled, so this leaves a nested
    // feed before the app's own handler gets a chance to walk the tab history.
    BackHandler(enabled = opened.isNotEmpty()) { opened.removeAt(opened.lastIndex) }

    val top = opened.lastOrNull()
    Column(Modifier.fillMaxSize()) {
        if (top != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { opened.removeAt(opened.lastIndex) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text(
                    top.card.title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val nested = top?.shelves
        when {
            top != null -> when {
                nested == null -> Loading()
                nested.isEmpty() -> Message("nothing_in_here")
                else -> ShelfList(nested, engine, ::open)
            }
            shelves == null -> Loading()
            shelves.orEmpty().isEmpty() -> Message(emptyHint)
            else -> ShelfList(shelves.orEmpty(), engine, ::open)
        }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
}

@Composable
private fun Message(hint: String) = Box(
    Modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center,
) {
    Text(
        hint.replace('_', ' '),
        color = TextSecondary,
        fontFamily = FontMono,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ShelfList(shelves: List<Shelf>, engine: PlayerEngine, onOpen: (ShelfCard) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        shelves.forEachIndexed { sIdx, shelf ->
            item(key = "header:$sIdx") { SectionHeader(shelf) }
            if (shelf.cards.isNotEmpty()) {
                item(key = "cards:$sIdx") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Keyed by position as well as id: a provider that repeats an item is not
                        // a reason for the list to throw at measure time.
                        itemsIndexed(shelf.cards, key = { index, card -> "card:$sIdx:$index:${card.id}" }) { _, card ->
                            CollectionCard(card) { onOpen(card) }
                        }
                    }
                }
            }
            itemsIndexed(
                shelf.tracks,
                key = { index, item -> "track:$sIdx:$index:${item.videoId}" },
            ) { index, item ->
                TrackRow(
                    trackKey = item.videoId,
                    title = item.title,
                    subtitle = item.subtitleText(),
                    artworkUrl = item.thumbnailUrl.ifBlank { null },
                    onClick = { engine.loadQueue(shelf.tracks.map { it.toQueueItem() }, index, shelf.title) },
                )
            }
        }
    }
}

@Composable
fun HomeScreen(provider: MusicProvider, engine: PlayerEngine) =
    FeedScreen(provider, engine, provider.homeEmptyHint) { provider.home() }

@Composable
fun ExploreScreen(provider: MusicProvider, engine: PlayerEngine) =
    FeedScreen(provider, engine, provider.exploreEmptyHint) { provider.explore() }

@Composable
private fun SectionHeader(shelf: Shelf) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(shelf.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        shelf.caption?.let {
            Text(it.replace('_', ' '), color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CollectionCard(card: ShelfCard, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick)) {
        Artwork(card.artworkUrl, Modifier.size(140.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            card.title,
            color = TextPrimary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        card.subtitle?.let {
            Text(it, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
