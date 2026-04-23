package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import api.ArtistResult
import api.Source
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.SearchResult
import api.YoutubeMusic
import api.resolveStreamUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import player.FFmpegPlayer
import player.QueueItem
import java.net.URL

enum class SortOrder(val label: String) {
    POPULARITY("Popularity"),
    RELEVANCE("Relevance"),
    YT_MUSIC_FIRST("YT Music first"),
    YOUTUBE_FIRST("YouTube first"),
    DURATION("Duration")
}

private fun List<SearchResult>.sorted(order: SortOrder): List<SearchResult> = when (order) {
    SortOrder.POPULARITY    -> sortedByDescending { it.viewCount ?: -1L }
    SortOrder.RELEVANCE     -> this
    SortOrder.YT_MUSIC_FIRST -> sortedBy { if (it.source == Source.YT_MUSIC) 0 else 1 }
    SortOrder.YOUTUBE_FIRST -> sortedBy { if (it.source == Source.YOUTUBE) 0 else 1 }
    SortOrder.DURATION      -> sortedBy { parseDurationToSeconds(it.duration) }
}

private fun parseDurationToSeconds(duration: String): Int {
    val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> 0
    }
}

@Composable
fun SearchScreen(player: FFmpegPlayer, onArtistClick: (browseId: String, name: String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var rawResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var artistResults by remember { mutableStateOf<List<ArtistResult>>(emptyList()) }
    var showAllArtists by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf(SortOrder.POPULARITY) }
    var loading by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val results = remember(rawResults, sortOrder) { rawResults.sorted(sortOrder) }

    fun doSearch() {
        if (query.isBlank()) return
        scope.launch {
            loading = true
            showAllArtists = false
            coroutineScope {
                val songs = async { YoutubeMusic.search(query) }
                val artists = async { YoutubeMusic.searchArtists(query) }
                rawResults = songs.await()
                artistResults = artists.await()
            }
            loading = false
            results.take(8).forEach { launch { resolveStreamUrl(it.videoId) } }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("_search_modules;", color = PsSteel400, fontFamily = FontMono, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.weight(1f).onKeyEvent { e ->
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyUp) { doSearch(); true } else false
                },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = TextPrimary,
                    cursorColor = TextPrimary,
                    focusedBorderColor = TextPrimary,
                    unfocusedBorderColor = PsPearl200,
                    backgroundColor = Surface,
                    placeholderColor = PsSteel400
                )
            )
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = { doSearch() },
                modifier = Modifier.size(50.dp).background(PsInk900)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = PsWhite)
            }
        }

        if (rawResults.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort by", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Box {
                    Row(
                        modifier = Modifier
                            .background(PsInset)
                            .clickable { sortExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sortOrder.label, color = TextPrimary, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = PsSteel500,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                        modifier = Modifier.background(Surface)
                    ) {
                        SortOrder.entries.forEach { option ->
                            DropdownMenuItem(onClick = { sortOrder = option; sortExpanded = false }) {
                                Text(
                                    option.label,
                                    color = if (option == sortOrder) TextPrimary else TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PsInk900)
            }
            results.isEmpty() && artistResults.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("// no_results_yet;", color = PsSteel400, fontSize = 14.sp, fontFamily = FontMono)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (artistResults.isNotEmpty()) {
                    item {
                        Text(
                            "artists;",
                            color = PsSteel400,
                            fontFamily = FontMono,
                            fontSize = 10.sp,
                            letterSpacing = 1.7.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                    val visibleArtists = if (showAllArtists) artistResults else artistResults.take(3)
                    items(visibleArtists.size) { index ->
                        ArtistRow(visibleArtists[index], onArtistClick)
                    }
                    if (artistResults.size > 3) {
                        item {
                            Text(
                                if (showAllArtists) "// show_less;" else "// see_all_${artistResults.size}_artists;",
                                color = PsSteel500,
                                fontFamily = FontMono,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable { showAllArtists = !showAllArtists }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                    item {
                        Divider(
                            color = PsPearl200,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }
                items(results.size) { index ->
                    TrackRow(results[index], index, results, player, onArtistClick)
                }
            }
        }
    }
}

@Composable
fun TrackRow(
    result: SearchResult,
    index: Int,
    results: List<SearchResult>,
    player: FFmpegPlayer,
    onArtistClick: ((browseId: String, name: String) -> Unit)?
) {
    val currentTitle by player.currentTitle
    val active = currentTitle == result.videoId
    val canNavigateArtist = onArtistClick != null && result.artistId != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (active) {
                    // Active left 2dp cyan rect
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
                player.loadQueue(results.map { QueueItem(it.url, it.videoId, it.title, it.artist) }, index)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(result.thumbnailUrl, Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                result.artist,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (canNavigateArtist) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onArtistClick!!(result.artistId!!, result.artist) } else Modifier
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(result.duration, color = PsSteel400, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = if (result.source == Source.YT_MUSIC) Icons.Default.MusicNote else Icons.Default.VideoLibrary,
            contentDescription = if (result.source == Source.YT_MUSIC) "YouTube Music" else "YouTube",
            tint = PsSteel400,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
fun ArtistRow(artist: ArtistResult, onArtistClick: (browseId: String, name: String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Bottom hairline
                drawLine(
                    color = PsPearl200,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            }
            .clickable { onArtistClick(artist.browseId, artist.name) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(
            artist.thumbnailUrl ?: "",
            Modifier.size(48.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artist.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = artist.subscriberCount?.let { formatSubscribers(it) }
            val subtitleText = if (sub != null) "$sub monthly listeners" else artist.subtitle
            if (subtitleText.isNotEmpty()) {
                Text(
                    subtitleText,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.Default.MusicNote,
            contentDescription = "Artist",
            tint = PsSteel400,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
fun Thumbnail(url: String, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = URL(url).toURI().toURL().readBytes()
                    bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            }
        }
    }

    Box(modifier.background(PsPearl100)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

private fun formatSubscribers(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0).trimEnd('0').trimEnd('.')
    count >= 1_000     -> "%.1fK".format(count / 1_000.0).trimEnd('0').trimEnd('.')
    else               -> count.toString()
}
