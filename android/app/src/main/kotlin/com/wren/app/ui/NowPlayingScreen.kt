package com.wren.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.fetchLyrics
import com.wren.app.util.artworkFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import models.LyricsResult
import models.QueueItem
import models.RepeatMode
import player.PlayerEngine
import kotlin.math.roundToInt

private val PeekHeight = 76.dp

/**
 * YouTube Music-style now playing: the player fills the screen and the queue lives in a
 * sheet anchored to the bottom, peeking as an "up next" strip. Drag the strip (or tap it)
 * to expand the queue over the player; drag down anywhere on the queue to collapse it.
 */
@Composable
fun NowPlayingScreen(engine: PlayerEngine) {
    val queue by engine.queue.collectAsState()
    val index by engine.queueIndex.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val position by engine.position.collectAsState()
    val duration by engine.duration.collectAsState()
    val shuffle by engine.shuffle.collectAsState()
    val repeatMode by engine.repeatMode.collectAsState()

    val item = queue.getOrNull(index)

    var lyrics by remember(item?.videoId) { mutableStateOf<LyricsResult?>(null) }
    var scrubPosition by remember(item?.videoId) { mutableStateOf<Float?>(null) }

    LaunchedEffect(item?.videoId, duration) {
        lyrics = null
        if (item != null) {
            lyrics = runCatching { fetchLyrics(item.title, item.artist, duration) }.getOrNull()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
        val density = LocalDensity.current
        val peekPx = with(density) { PeekHeight.toPx() }
        val paneMaxHeight = maxHeight
        val collapsedPx = with(density) { (paneMaxHeight - PeekHeight).coerceAtLeast(0.dp).toPx() }

        // 0f == expanded (sheet covers the player), collapsedPx == only the peek strip shows.
        val offset = remember { Animatable(collapsedPx) }
        val scope = rememberCoroutineScope()
        val expanded = offset.value < collapsedPx / 2

        LaunchedEffect(collapsedPx) { offset.snapTo(offset.value.coerceIn(0f, collapsedPx)) }

        val settle: (Float) -> Unit = { velocity ->
            val target = when {
                velocity < -1_200f -> 0f
                velocity > 1_200f -> collapsedPx
                offset.value < collapsedPx / 2 -> 0f
                else -> collapsedPx
            }
            scope.launch { offset.animateTo(target, spring(stiffness = Spring.StiffnessLow)) }
        }

        // Collapsing the queue by dragging down once the list is already at its top.
        val collapseOnOverscroll = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0f || offset.value <= 0f) return Offset.Zero
                    val next = (offset.value + available.y).coerceAtMost(collapsedPx)
                    val consumed = next - offset.value
                    scope.launch { offset.snapTo(next) }
                    return Offset(0f, consumed)
                }
            }
        }

        LaunchedEffect(queue.size) {
            if (queue.size <= 1) offset.animateTo(collapsedPx, spring(stiffness = Spring.StiffnessLow))
        }

        val listState = rememberLazyListState()
        LaunchedEffect(expanded, index) {
            if (expanded && index >= 0) listState.scrollToItem(index)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = PeekHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Fits exactly the space above the peek strip so there are no dead gaps,
            // with weighted breathing room that adapts to any screen height.
            PlayerPane(
                paneHeight = paneMaxHeight - PeekHeight,
                artSize = ((paneMaxHeight - PeekHeight) * 0.42f).coerceIn(160.dp, 320.dp),
                item = item,
                lyrics = lyrics,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                shuffle = shuffle,
                repeatMode = repeatMode,
                scrubPosition = scrubPosition,
                onScrub = { scrubPosition = it },
                onScrubFinished = {
                    scrubPosition?.let { engine.seek(it.toDouble()) }
                    scrubPosition = null
                },
                engine = engine,
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .nestedScroll(collapseOnOverscroll)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Surface),
        ) {
            QueueHeader(
                queueSize = queue.size,
                nextTitle = nextTitle(queue, index, repeatMode),
                expanded = expanded,
                shuffle = shuffle,
                onDrag = { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, collapsedPx)) } },
                onDragStopped = { velocity -> settle(velocity) },
                onToggle = { settle(if (expanded) 4_000f else -4_000f) },
                onShuffle = { engine.toggleShuffle() },
                onClear = { engine.clearQueue() },
            )

            if (expanded) {
                Divider(color = HairlineSoft)
                LazyColumn(Modifier.weight(1f), state = listState) {
                    itemsIndexed(queue, key = { i, q -> "$i:${q.source}:${q.videoId}" }) { queueIndex, queueItem ->
                        TrackRow(
                            title = queueItem.title.ifBlank { queueItem.videoId },
                            subtitle = queueItem.subtitleText(),
                            artworkUrl = artworkFor(queueItem),
                            highlight = queueIndex == index,
                            onClick = { engine.jumpTo(queueIndex) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(
    queueSize: Int,
    nextTitle: String?,
    expanded: Boolean,
    shuffle: Boolean,
    onDrag: (Float) -> Unit,
    onDragStopped: suspend CoroutineScope.(Float) -> Unit,
    onToggle: () -> Unit,
    onShuffle: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState(onDrag),
                orientation = Orientation.Vertical,
                onDragStopped = onDragStopped,
            )
            .clickable(onClick = onToggle),
    ) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(Hairline, RoundedCornerShape(2.dp))
                .align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (expanded) "queue · $queueSize" else "up next",
                    color = PsSteel400,
                    fontFamily = FontMono,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (expanded) "showing everything that follows" else nextTitle ?: "nothing queued",
                    color = if (expanded) TextSecondary else TextPrimary,
                    fontSize = if (expanded) 12.sp else 13.sp,
                    fontWeight = if (expanded) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                IconButton(onClick = onShuffle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle queue",
                        tint = if (shuffle) PsIrisCyan else TextSecondary,
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear queue", tint = TextSecondary)
                }
            } else {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Show queue",
                    tint = TextSecondary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerPane(
    paneHeight: Dp,
    artSize: Dp,
    item: QueueItem?,
    lyrics: LyricsResult?,
    position: Double,
    duration: Double,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    scrubPosition: Float?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    engine: PlayerEngine,
) {
    Column(
        Modifier.fillMaxWidth().height(paneHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1.1f))
        Artwork(artworkFor(item), Modifier.size(artSize))
        Spacer(Modifier.height(16.dp))
        Text(
            item?.title?.ifBlank { item?.videoId.orEmpty() } ?: "nothing playing",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item?.artist.orEmpty(),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1.1f))

        val shown = scrubPosition ?: position.toFloat()
        Slider(
            value = shown.coerceIn(0f, duration.toFloat().coerceAtLeast(0.1f)),
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            valueRange = 0f..duration.toFloat().coerceAtLeast(0.1f),
            colors = SliderDefaults.colors(
                thumbColor = PsIrisCyan,
                activeTrackColor = PsIrisCyan,
                inactiveTrackColor = HairlineSoft,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shown.toDouble()), color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp)
            Text(formatTime(duration), color = PsSteel400, fontFamily = FontMono, fontSize = 11.sp)
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { engine.toggleShuffle() }) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffle) PsIrisCyan else TextSecondary,
                )
            }
            IconButton(onClick = { engine.previous() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = TextPrimary)
            }
            IconButton(onClick = { engine.playPause() }) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = TextPrimary,
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { engine.next() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = TextPrimary)
            }
            IconButton(onClick = { engine.toggleRepeat() }) {
                Icon(
                    if (repeatMode == RepeatMode.SINGLE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat",
                    tint = if (repeatMode == RepeatMode.OFF) TextSecondary else PsIrisCyan,
                )
            }
        }
    }

    lyrics?.let { result ->
        Spacer(Modifier.height(16.dp))
        Text(
            if (result.synced) "lyrics" else "lyrics (unsynced)",
            color = PsSteel400,
            fontFamily = FontMono,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val activeIndex = if (result.synced) {
            result.lines.indexOfLast { it.timeMs <= (position * 1000).toLong() }
        } else -1
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            result.lines.forEachIndexed { lineIndex, line ->
                Text(
                    line.text,
                    color = if (lineIndex == activeIndex) PsIrisCyan else TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

private fun nextTitle(
    queue: List<QueueItem>,
    index: Int,
    repeatMode: RepeatMode,
): String? {
    val candidate = queue.getOrNull(index + 1)
        ?: if (repeatMode == RepeatMode.ALL) queue.firstOrNull() else null
    return candidate?.title?.ifBlank { candidate.videoId }
}

private fun formatTime(seconds: Double): String {
    if (seconds <= 0 || seconds.isNaN()) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
