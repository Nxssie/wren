package ui

import models.LyricsResult
import models.LyricLine
import api.fetchLyrics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import player.FFmpegPlayer
import models.QueueItem
import java.net.URL

// Lyrics state machine
private sealed class LyricsState {
    object Idle : LyricsState()
    object Loading : LyricsState()
    data class Loaded(val result: LyricsResult) : LyricsState()
    object NotFound : LyricsState()
}

@Composable
fun NowPlayingScreen(player: FFmpegPlayer) {
    val currentId    by player.currentTitle
    val displayTitle by player.displayTitle
    val position     by player.position
    val duration     by player.duration
    val isPlaying    by player.isPlaying
    val isLoading    by player.isLoading
    val queue        by player.queue
    val queueIndex   by player.queueIndex

    if (currentId.isEmpty()) {
        NpEmptyState()
        return
    }

    val currentItem  = queue.getOrNull(queueIndex)
    val artist       = currentItem?.artist ?: ""
    val progress     = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    // --- Lyrics state ---
    var lyricsState by remember { mutableStateOf<LyricsState>(LyricsState.Idle) }
    var queueHeight by remember { mutableStateOf(200.dp) }

    LaunchedEffect(currentId) {
        lyricsState = LyricsState.Loading
        // Wait up to 5s for duration to arrive from mpv
        repeat(25) {
            if (player.duration.value > 0) return@repeat
            delay(200)
        }
        val dur = player.duration.value
        lyricsState = fetchLyrics(displayTitle, artist, dur)
            ?.let { LyricsState.Loaded(it) }
            ?: LyricsState.NotFound
    }

    // Active lyric line index
    val posMs = (position * 1000).toLong()
    val activeLineIdx = remember(lyricsState, posMs) {
        val lines = (lyricsState as? LyricsState.Loaded)?.result?.lines ?: return@remember 0
        val idx = lines.indexOfLast { it.timeMs <= posMs }
        if (idx < 0) 0 else idx
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        NpHeader(queueIndex = queueIndex, queueSize = queue.size)

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Left panel: artwork + compact track info (fixed width, no vertical competition with lyrics)
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(Surface)
                    .drawBehind {
                        drawLine(PsPearl200, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
                    }
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NpArtwork(videoId = currentId, sizeDp = 220)
                Text(
                    displayTitle,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = (-0.5).sp,
                    lineHeight = 22.sp,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotEmpty()) {
                    Text(
                        "_${artist.lowercase().replace(" ", "_")};",
                        fontFamily = FontMono,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                NpProgress(
                    progress = progress,
                    position = position,
                    duration = duration,
                    isLoading = isLoading
                )
            }

            // Right panel: lyrics + queue (no fixed-height artwork competing for vertical space)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                NpLyrics(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    lyricsState = lyricsState,
                    activeLineIdx = activeLineIdx
                )
                if (queue.size > 1) {
                    NpQueue(queue = queue, activeIndex = queueIndex, player = player, height = queueHeight, onHeightChange = { queueHeight = it })
                }
            }
        }
    }
}

@Composable
private fun NpLyrics(
    modifier: Modifier,
    lyricsState: LyricsState,
    activeLineIdx: Int
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activeLineIdx) {
        if (activeLineIdx > 1) {
            listState.animateScrollToItem((activeLineIdx - 2).coerceAtLeast(0))
        }
    }

    Column(
        modifier
            .drawBehind {
                // Top 1px hairline separator
                drawLine(
                    color = Color(0x1F000000),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f
                )
            }
    ) {
        // Section header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "_lyrics;",
                fontFamily = FontMono,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = PsSteel400
            )
            val statusLabel = when (lyricsState) {
                is LyricsState.Idle    -> ""
                is LyricsState.Loading -> "loading..."
                is LyricsState.NotFound -> "// no_lyrics;"
                is LyricsState.Loaded  -> if (lyricsState.result.synced) "synced;" else "plain_text;"
            }
            if (statusLabel.isNotEmpty()) {
                Text(
                    statusLabel,
                    fontFamily = FontMono,
                    fontSize = 10.sp,
                    letterSpacing = 1.4.sp,
                    color = PsSteel400
                )
            }
        }

        when (lyricsState) {
            is LyricsState.Idle, is LyricsState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "...",
                        fontFamily = FontMono,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }
            is LyricsState.NotFound -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "// no_lyrics;",
                        fontFamily = FontMono,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }
            is LyricsState.Loaded -> {
                val lines = lyricsState.result.lines
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(lines) { index, line ->
                        val isActive = index == activeLineIdx
                        Text(
                            line.text,
                            fontFamily = FontMono,
                            fontSize = if (isActive) 16.sp else 13.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isActive) TextPrimary else TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NpEmptyState() {
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "// no_stream_active;",
                fontFamily = FontMono,
                fontSize = 14.sp,
                color = PsSteel400
            )
            Text(
                "_start_playback_from_search;",
                fontFamily = FontMono,
                fontSize = 11.sp,
                color = PsSteel400.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun NpHeader(queueIndex: Int, queueSize: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = Color(0x1F000000),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
                drawLine(
                    color = PsIrisCyan.copy(alpha = 0.8f),
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width * 0.55f, size.height - 1f),
                    strokeWidth = 2f
                )
            }
            .padding(start = 28.dp, end = 28.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "_now_playing;",
                fontFamily = FontMono,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = PsSteel400
            )
            Text(
                "NOW",
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                letterSpacing = (-1).sp,
                lineHeight = 36.sp,
                color = TextPrimary
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // NPL badge — intentionally always dark
            Box(
                Modifier
                    .background(PsInk900)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "NPL;",
                    fontFamily = FontMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.4.sp,
                    color = PsWhite
                )
            }
            if (queueSize > 0) {
                Text(
                    "${(queueIndex + 1).coerceAtLeast(1)} / $queueSize",
                    fontFamily = FontMono,
                    fontSize = 11.sp,
                    color = PsSteel400
                )
            }
        }
    }
}

@Composable
private fun NpArtwork(videoId: String, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .border(1.dp, Color(0x1A000000))
    ) {
        NpThumbnail(videoId, Modifier.fillMaxSize())
        // Reticle corner brackets
        Canvas(Modifier.fillMaxSize()) {
            val c  = 14.dp.toPx()
            val sw = 1.5f
            val col = Color.White.copy(alpha = 0.88f)
            val w = size.width; val h = size.height
            drawLine(col, Offset(0f, 0f),   Offset(c, 0f),     sw)
            drawLine(col, Offset(0f, 0f),   Offset(0f, c),     sw)
            drawLine(col, Offset(w, 0f),    Offset(w - c, 0f), sw)
            drawLine(col, Offset(w, 0f),    Offset(w, c),      sw)
            drawLine(col, Offset(0f, h),    Offset(c, h),      sw)
            drawLine(col, Offset(0f, h),    Offset(0f, h - c), sw)
            drawLine(col, Offset(w, h),     Offset(w - c, h),  sw)
            drawLine(col, Offset(w, h),     Offset(w, h - c),  sw)
        }
    }
}

@Composable
private fun NpThumbnail(videoId: String, modifier: Modifier) {
    var bitmap by remember(videoId) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(videoId) {
        withContext(Dispatchers.IO) {
            val candidates = listOf(
                "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            )
            for (url in candidates) {
                runCatching {
                    val img = org.jetbrains.skia.Image.makeFromEncoded(URL(url).readBytes())
                    if (img.width > 200) {
                        bitmap = img.toComposeImageBitmap()
                        return@withContext
                    }
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

@Composable
private fun NpProgress(
    progress: Float,
    position: Double,
    duration: Double,
    isLoading: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(PsInset)
                .border(1.dp, Color(0x1A000000))
        ) {
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = PsSteel400,
                    backgroundColor = PsInset
                )
            } else {
                // Progress fill — intentionally always PsInk900
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(PsInk900)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(npFormatTime(position), fontFamily = FontMono, fontSize = 11.sp, color = TextSecondary)
            Text(npFormatTime(duration), fontFamily = FontMono, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun NpQueue(queue: List<QueueItem>, activeIndex: Int, player: FFmpegPlayer, height: Dp, onHeightChange: (Dp) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (activeIndex - 1).coerceAtLeast(0))
    val density = LocalDensity.current
    val heightRef = rememberUpdatedState(height)
    val callbackRef = rememberUpdatedState(onHeightChange)

    LaunchedEffect(activeIndex) {
        listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(Surface)
    ) {
        // Drag handle — resize by dragging up/down
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .drawBehind {
                    drawLine(
                        color = if (globalDark) PsWhite.copy(alpha = 0.12f) else PsInk900,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1f
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        with(density) {
                            callbackRef.value(
                                (heightRef.value - dragAmount.y.toDp()).coerceIn(80.dp, 400.dp)
                            )
                        }
                    }
                }
        ) {
            Box(
                Modifier
                    .size(width = 32.dp, height = 3.dp)
                    .background(PsSteel400.copy(alpha = 0.4f))
                    .align(Alignment.Center)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "_queue;",
                fontFamily = FontMono,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = PsSteel400
            )
            Text(
                "${queue.size} tracks",
                fontFamily = FontMono,
                fontSize = 10.sp,
                color = PsSteel400
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            itemsIndexed(queue) { index, item ->
                NpQueueRow(
                    item = item,
                    index = index,
                    isActive = index == activeIndex,
                    onClick = { player.loadQueue(queue, index) }
                )
            }
        }
    }
}

@Composable
private fun NpQueueRow(item: QueueItem, index: Int, isActive: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) PsInset else Color.Transparent)
            .drawBehind {
                if (isActive) {
                    // Cyan left strip — intentionally always PsIrisCyan
                    drawRect(
                        color = PsIrisCyan,
                        topLeft = Offset(0f, 0f),
                        size = Size(3.dp.toPx(), size.height)
                    )
                }
                drawLine(
                    color = Color(0x0D000000),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "%02d".format(index + 1),
            fontFamily = FontMono,
            fontSize = 11.sp,
            color = if (isActive) TextPrimary else PsSteel400,
            modifier = Modifier.width(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontFamily = FontMono,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.artist.isNotEmpty()) {
                Text(
                    item.artist,
                    fontFamily = FontMono,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun npFormatTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0L)
    return "%d:%02d".format(s / 60, s % 60)
}
