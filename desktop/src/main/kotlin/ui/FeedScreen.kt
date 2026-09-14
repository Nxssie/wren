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
import models.Shelf
import models.ShelfCard
import models.ShelfCardKind
import models.toQueueItem
import player.FFmpegPlayer
import provider.MusicProvider

/**
 * What a card opened. A `null` list means "still loading", which is not the same as an empty one:
 * the empty state only appears once the fetch has actually come back with nothing.
 */
private sealed interface Open {
    val card: ShelfCard
    data class Tracks(override val card: ShelfCard, val tracks: List<SearchResult>?) : Open
    data class Shelves(override val card: ShelfCard, val shelves: List<Shelf>?) : Open
}

/**
 * A provider feed rendered as shelves. Home and Explore differ only in what they fetch and what
 * they say when there is nothing, so they share this screen.
 *
 * Cards can open tracks (an album or playlist) or another feed (a mood or genre page), so the
 * opened cards are a stack: a mood page is a list of playlists, and those open tracks in turn.
 */
@Composable
private fun FeedScreen(
    provider: MusicProvider,
    player: FFmpegPlayer,
    title: String,
    emptyHint: String,
    load: suspend (Boolean) -> List<Shelf>,
) {
    var shelves by remember { mutableStateOf<List<Shelf>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val opened = remember { mutableStateListOf<Open>() }
    val scope = rememberCoroutineScope()

    fun reload(force: Boolean) {
        if (loading) return
        loading = true
        error = false
        scope.launch {
            runCatching { shelves = load(force) }.onFailure { error = true }
            loading = false
        }
    }

    fun open(card: ShelfCard) {
        val index = opened.size
        scope.launch {
            when (card.kind) {
                ShelfCardKind.TRACKS -> {
                    opened.add(Open.Tracks(card, null))
                    val tracks = runCatching { provider.collectionTracks(card.id) }.getOrDefault(emptyList())
                    opened[index] = Open.Tracks(card, tracks)
                    tracks.take(6).forEach { launch { resolveStreamUrl(it.videoId) } }
                }
                ShelfCardKind.SHELVES -> {
                    opened.add(Open.Shelves(card, null))
                    val shelfList = runCatching { provider.collectionShelves(card.id) }.getOrDefault(emptyList())
                    opened[index] = Open.Shelves(card, shelfList)
                }
            }
        }
    }

    LaunchedEffect(Unit) { if (shelves == null) reload(force = false) }

    val top = opened.lastOrNull()
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (top != null) {
                IconButton(onClick = { opened.removeAt(opened.lastIndex) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(top.card.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    top.card.subtitle?.let {
                        Text("// $it;", color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (top is Open.Tracks && top.tracks?.isNotEmpty() == true) {
                    Text(
                        "play_all;",
                        color = TextPrimary, fontFamily = FontMono, fontSize = 11.sp,
                        modifier = Modifier
                            .background(PsInset)
                            .clickable { player.loadQueue(top.tracks.map { it.toQueueItem() }, 0) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                Text("${provider.platform.label} $title;", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { reload(force = true) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (top) {
            null -> when {
                shelves == null && (loading || !error) -> Centred { CircularProgressIndicator(color = PsInk900) }
                shelves == null && error -> Centred {
                    Text("// ${provider.platform.label}_unavailable;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
                }
                shelves.isNullOrEmpty() -> Centred {
                    Text("// $emptyHint;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono,
                        modifier = Modifier.padding(horizontal = 24.dp))
                }
                else -> ShelfList(shelves.orEmpty(), player, ::open)
            }
            is Open.Shelves -> when {
                top.shelves == null -> Centred { CircularProgressIndicator(color = PsInk900) }
                top.shelves.isEmpty() -> Centred {
                    Text("// nothing_in_here;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
                }
                else -> ShelfList(top.shelves, player, ::open)
            }
            is Open.Tracks -> when {
                top.tracks == null -> Centred { CircularProgressIndicator(color = PsInk900) }
                top.tracks.isEmpty() -> Centred {
                    Text("// no_playable_tracks;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
                }
                else -> LazyColumn {
                    items(top.tracks.size, key = { "${top.tracks[it].videoId}:$it" }) { index ->
                        TrackRow(top.tracks[index], index, top.tracks, player, onArtistClick = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun Centred(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
private fun ShelfList(shelves: List<Shelf>, player: FFmpegPlayer, onOpen: (ShelfCard) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        shelves.forEachIndexed { sIdx, shelf ->
            item(key = "header-$sIdx") {
                Column(Modifier.padding(top = if (sIdx == 0) 0.dp else 18.dp, bottom = 6.dp)) {
                    Text("${shelf.title};", color = PsSteel400, fontFamily = FontMono, fontSize = 10.sp, letterSpacing = 1.7.sp)
                    shelf.caption?.let { Text("// $it;", color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp) }
                }
            }
            if (shelf.cards.isNotEmpty()) {
                item(key = "cards-$sIdx") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Keyed by position as well as id: a provider that repeats an item is not
                        // a reason for the list to throw at measure time.
                        items(shelf.cards.size, key = { "card:$sIdx:$it:${shelf.cards[it].id}" }) { i ->
                            CollectionCard(shelf.cards[i]) { onOpen(shelf.cards[i]) }
                        }
                    }
                }
            }
            if (shelf.tracks.isNotEmpty()) {
                items(shelf.tracks.size, key = { "track:$sIdx:$it:${shelf.tracks[it].videoId}" }) { index ->
                    TrackRow(shelf.tracks[index], index, shelf.tracks, player, onArtistClick = null)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(provider: MusicProvider, player: FFmpegPlayer) =
    FeedScreen(provider, player, "home", provider.homeEmptyHint) { force -> provider.home(forceRefresh = force) }

@Composable
fun ExploreScreen(provider: MusicProvider, player: FFmpegPlayer) =
    FeedScreen(provider, player, "explore", provider.exploreEmptyHint) { provider.explore() }

@Composable
private fun CollectionCard(card: ShelfCard, onClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        Thumbnail(card.artworkUrl ?: "", Modifier.size(140.dp))
        Spacer(Modifier.height(6.dp))
        Text(card.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        card.subtitle?.let {
            Text(it, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
