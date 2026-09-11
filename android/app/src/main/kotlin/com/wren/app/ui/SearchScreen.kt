package com.wren.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wren.app.util.downloadsDestination
import download.DownloadManager
import kotlinx.coroutines.launch
import models.SearchResult
import models.Source
import models.toQueueItem
import player.PlayerEngine
import provider.MusicProvider
import api.SoundCloudLikes
import api.resolveStreamUrl
import provider.Platform

@Composable
fun SearchScreen(
    provider: MusicProvider,
    engine: PlayerEngine,
    seedQuery: String? = null,
    onSeedConsumed: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val downloads by DownloadManager.states.collectAsState()
    val liked by SoundCloudLikes.liked.collectAsState()
    val canLike = provider.platform == Platform.SOUNDCLOUD && provider.isAuthenticated

    fun doSearch() {
        if (query.isBlank()) return
        scope.launch {
            loading = true
            error = null
            results = runCatching { provider.search(query) }
                .onFailure { error = it.message ?: it::class.simpleName }
                .getOrDefault(emptyList())
            loading = false
            // Warm the stream cache so the first tap starts instantly.
            results.take(8).forEach { launch { resolveStreamUrl(it.videoId) } }
        }
    }

    // An artist tapped in the Library lands here as a ready-to-run query.
    LaunchedEffect(seedQuery) {
        val seed = seedQuery ?: return@LaunchedEffect
        query = seed
        doSearch()
        onSeedConsumed()
    }

    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("search ${provider.platform.label}", color = PsSteel400, fontFamily = FontMono, fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(0.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = TextPrimary,
                cursorColor = TextPrimary,
                focusedBorderColor = TextPrimary,
                unfocusedBorderColor = Hairline,
                backgroundColor = Surface,
                placeholderColor = PsSteel400,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when {
            loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
            }
            error != null -> Text(
                "search failed: $error",
                color = PsSignalDanger,
                fontFamily = FontMono,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { index, item -> "${item.source}:${item.videoId}:$index" }) { index, item ->
                    TrackRow(
                        title = item.title,
                        subtitle = item.subtitleText(),
                        artworkUrl = item.thumbnailUrl.ifBlank { null },
                        onClick = { engine.loadQueue(results.map { it.toQueueItem() }, index, "search · $query") },
                        onAction = if (provider.supportsStations) {
                            {
                                scope.launch {
                                    val station = runCatching { provider.station(item) }.getOrDefault(emptyList())
                                    if (station.isNotEmpty()) {
                                        engine.loadQueue(station.map { it.toQueueItem() }, 0, "radio · ${item.title}")
                                    }
                                }
                            }
                        } else null,
                        onDownload = if (item.source == Source.SOUNDCLOUD) {
                            { DownloadManager.enqueue(item.toQueueItem(), downloadsDestination(context)) }
                        } else null,
                        downloadState = downloads[item.url],
                        liked = if (canLike) item.url in liked else null,
                        onLike = if (canLike) {
                            { scope.launch { SoundCloudLikes.toggle(item.url, item.soundcloudId) } }
                        } else null,
                    )
                }
            }
        }
    }
}
