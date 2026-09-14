package com.wren.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import models.SearchResult
import models.Shelf
import models.ShelfCard
import models.ShelfCardKind
import models.toQueueItem
import player.PlayerEngine
import provider.MusicProvider
import util.runCatchingExceptCancellation

/** A card's destination. `null` lists mean "still loading", never empty — mirroring the desktop screen. */
private sealed interface Open {
    val card: ShelfCard
    data class Tracks(override val card: ShelfCard, val tracks: List<SearchResult>?) : Open
    data class Shelves(override val card: ShelfCard, val shelves: List<Shelf>?) : Open
}

/**
 * A provider feed rendered as shelves. Home and Explore are the same screen with a different
 * fetch, so they share this one.
 *
 * A card opens a navigable track list (playback is only ever started by an explicit tap there);
 * a mood or genre card opens another feed, so those are a stack and back leaves one level at a time.
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
    val opened = remember(provider) { mutableStateListOf<Open>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(provider) {
        shelves = runCatchingExceptCancellation { load() }.getOrDefault(emptyList())
    }

    fun open(card: ShelfCard) {
        when (card.kind) {
            ShelfCardKind.TRACKS -> {
                // Opening a list must never start playback on its own: it lands on the track
                // list, and only an explicit tap there (a track or play_all) starts playing.
                val index = opened.size
                opened.add(Open.Tracks(card, null))
                scope.launch {
                    val tracks = card.tracks ?: runCatchingExceptCancellation {
                        provider.collectionTracks(card.id)
                    }.getOrDefault(emptyList())
                    opened[index] = Open.Tracks(card, tracks)
                }
            }
            ShelfCardKind.SHELVES -> {
                val index = opened.size
                opened.add(Open.Shelves(card, null))
                scope.launch {
                    val nested = runCatchingExceptCancellation { provider.collectionShelves(card.id) }.getOrDefault(emptyList())
                    opened[index] = Open.Shelves(card, nested)
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
                // The title flexes and the action keeps its width: a long title otherwise takes
                // the whole row, leaving `play_all` a sliver in which it wraps one letter per line.
                Text(
                    top.card.title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (top is Open.Tracks && top.tracks?.isNotEmpty() == true) {
                    Text(
                        "play_all",
                        color = TextPrimary,
                        fontFamily = FontMono,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { engine.loadQueue(top.tracks.map { it.toQueueItem() }, 0, top.card.title) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        when {
            top is Open.Tracks -> when (val tracks = top.tracks) {
                null -> Loading()
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (tracks.isEmpty()) {
                        item { Message("no_playable_tracks") }
                    } else {
                        itemsIndexed(tracks, key = { index, item -> "open:${top.card.id}:$index:${item.videoId}" }) { index, item ->
                            TrackRow(
                                trackKey = item.videoId,
                                title = item.title,
                                subtitle = item.subtitleText(),
                                artworkUrl = item.thumbnailUrl.ifBlank { null },
                                onClick = { engine.loadQueue(tracks.map { it.toQueueItem() }, index, top.card.title) },
                            )
                        }
                    }
                }
            }
            top is Open.Shelves -> when {
                top.shelves == null -> Loading()
                top.shelves.orEmpty().isEmpty() -> Message("nothing_in_here")
                else -> ShelfList(top.shelves.orEmpty(), engine, ::open)
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
            if (shelf.tracks.isNotEmpty()) {
                // A shelf carrying a track list renders as one navigable entry — never inline
                // play rows: playing starts only from an explicit tap inside the opened list.
                item(key = "list:$sIdx") {
                    val tracks = shelf.tracks
                    ListCard(
                        title = shelf.title,
                        subtitle = shelf.caption ?: "${tracks.size} tracks",
                        count = tracks.size,
                        artworkUrl = tracks.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onClick = {
                            onOpen(
                                ShelfCard(
                                    id = "shelf-$sIdx",
                                    title = shelf.title,
                                    subtitle = shelf.caption,
                                    tracks = tracks
                                )
                            )
                        }
                    )
                }
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
private fun ListCard(
    title: String,
    subtitle: String,
    count: Int,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PsSteel400.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(artworkUrl, Modifier.size(56.dp))
        Column {
            Text(title, color = TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count tracks", color = PsSteel400, fontFamily = FontMono, fontSize = 10.sp)
        }
    }
}

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
