package com.wren.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import models.SearchResult
import models.toQueueItem
import player.PlayerEngine
import provider.MusicProvider
import provider.Platform
import api.resolveStreamUrl

@Composable
fun SearchScreen(
    provider: MusicProvider,
    platform: Platform,
    onPlatformChange: (Platform) -> Unit,
    engine: PlayerEngine,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        PlatformSelector(platform, onPlatformChange)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("search ${platform.label}", color = PsSteel400, fontFamily = FontMono, fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(0.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = TextPrimary,
                cursorColor = TextPrimary,
                focusedBorderColor = TextPrimary,
                unfocusedBorderColor = PsPearl200,
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
                itemsIndexed(results, key = { _, item -> "${item.source}:${item.videoId}" }) { index, item ->
                    TrackRow(
                        title = item.title,
                        subtitle = item.subtitleText(),
                        artworkUrl = item.thumbnailUrl.ifBlank { null },
                        onClick = { engine.loadQueue(results.map { it.toQueueItem() }, index) },
                        onAction = if (provider.supportsStations) {
                            {
                                scope.launch {
                                    val station = runCatching { provider.station(item) }.getOrDefault(emptyList())
                                    if (station.isNotEmpty()) {
                                        engine.loadQueue(station.map { it.toQueueItem() }, 0)
                                    }
                                }
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformSelector(platform: Platform, onChange: (Platform) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Platform.entries.forEach { candidate ->
            val selected = candidate == platform
            Text(
                candidate.label,
                color = if (selected) PsInk900 else TextSecondary,
                fontFamily = FontMono,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(if (selected) PsIrisCyan else Surface, RoundedCornerShape(2.dp))
                    .clickable { onChange(candidate) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
