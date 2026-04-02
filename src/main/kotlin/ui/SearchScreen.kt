package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import player.MpvPlayer
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
fun SearchScreen(player: MpvPlayer, onArtistClick: (browseId: String, name: String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var rawResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var sortOrder by remember { mutableStateOf(SortOrder.POPULARITY) }
    var loading by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val results = remember(rawResults, sortOrder) { rawResults.sorted(sortOrder) }

    fun doSearch() {
        if (query.isBlank()) return
        scope.launch {
            loading = true
            rawResults = YoutubeMusic.search(query)
            loading = false
            results.take(8).forEach { launch { resolveStreamUrl(it.videoId) } }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search songs, artists...", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.weight(1f).onKeyEvent { e ->
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyUp) { doSearch(); true } else false
                },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = TextPrimary,
                    cursorColor = Accent,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color(0xFF333333),
                    backgroundColor = Surface
                )
            )
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = { doSearch() },
                modifier = Modifier.size(50.dp).background(Accent, CircleShape)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
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
                            .clip(RoundedCornerShape(4.dp))
                            .background(Surface)
                            .clickable { sortExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sortOrder.label, color = TextPrimary, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null,
                            tint = TextSecondary, modifier = Modifier.size(16.dp))
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
                                    color = if (option == sortOrder) Accent else TextPrimary,
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
                CircularProgressIndicator(color = Accent)
            }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Search for music to get started", color = TextSecondary, fontSize = 15.sp)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    player: MpvPlayer,
    onArtistClick: ((browseId: String, name: String) -> Unit)?
) {
    val currentTitle by player.currentTitle
    val active = currentTitle == result.videoId
    val canNavigateArtist = onArtistClick != null && result.artistId != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Surface else Color.Transparent)
            .clickable {
                player.loadQueue(results.map { QueueItem(it.url, it.videoId, it.title) }, index)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(result.thumbnailUrl, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.title,
                color = if (active) Accent else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                result.artist,
                color = if (canNavigateArtist) Accent.copy(alpha = 0.75f) else TextSecondary,
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
        Text(result.duration, color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = if (result.source == Source.YT_MUSIC) Icons.Default.MusicNote else Icons.Default.VideoLibrary,
            contentDescription = if (result.source == Source.YT_MUSIC) "YouTube Music" else "YouTube",
            tint = if (result.source == Source.YT_MUSIC) Color(0xFFFF0000) else Color(0xFFFF6D00),
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

    Box(modifier.background(Surface)) {
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
