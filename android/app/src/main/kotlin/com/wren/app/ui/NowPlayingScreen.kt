package com.wren.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import api.SoundCloudLikes
import api.fetchLyrics
import auth.SoundCloudAuth
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import com.wren.app.util.artworkFor
import com.wren.app.util.downloadsDestination
import download.DownloadManager
import models.Source
import kotlinx.coroutines.launch
import models.LyricsResult
import models.QueueItem
import models.RepeatMode
import player.PlayerEngine
import kotlin.math.abs
import kotlin.math.roundToInt

/** Height of the "up next" strip when the queue is fully collapsed. */
private val PeekHeight = 76.dp
/** Height of the compact track header that replaces the player when the queue is expanded. */
private val CompactHeaderHeight = 80.dp

/**
 * The three resting states of the queue sheet, as a fraction of its travel.
 * Mid-drag values in between are rendered continuously; a release settles on the nearest.
 */
private val Anchors = listOf(0f, 0.5f, 1f)

/**
 * YouTube Music-style now playing with a queue sheet that has three states:
 *
 *  - **player** (progress 0): the cover fills the pane as a backdrop, title and controls
 *    sit at the bottom over a scrim; the queue peeks as "up next".
 *  - **half** (progress 0.5): the sheet takes the lower half, the backdrop and controls
 *    compress into the top half.
 *  - **queue** (progress 1): a compact header (thumb, title, play) at the top, queue below.
 *
 * One `progress` value drives every layer, so any drag position is a valid frame.
 *
 * @param showQueue raised by a tap on the player bar (there is no tab to switch to when Now
 *   Playing is already open); consumed here to open the queue sheet.
 */
@Composable
fun NowPlayingScreen(engine: PlayerEngine, showQueue: MutableState<Boolean>) {
    val queue by engine.queue.collectAsState()
    val index by engine.queueIndex.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val position by engine.position.collectAsState()
    val duration by engine.duration.collectAsState()
    val shuffle by engine.shuffle.collectAsState()
    val repeatMode by engine.repeatMode.collectAsState()
    val queueTitle by engine.queueTitle.collectAsState()
    val downloads by DownloadManager.states.collectAsState()
    val likedSet by SoundCloudLikes.liked.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val item = queue.getOrNull(index)
    val artworkUrl = artworkFor(item)
    // Only SoundCloud tracks can be saved (YouTube streams are not ours to keep).
    val download: (() -> Unit)? = item?.takeIf { it.source == Source.SOUNDCLOUD }?.let { track ->
        { DownloadManager.enqueue(track, downloadsDestination(context)) }
    }
    val like: (() -> Unit)? = item?.takeIf { it.source == Source.SOUNDCLOUD && SoundCloudAuth.isAuthenticated }?.let { track ->
        { scope.launch { SoundCloudLikes.toggle(track.url) } }
    }

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
        val collapsedPx = with(density) { (maxHeight - PeekHeight).coerceAtLeast(0.dp).toPx() }
        val expandedPx = with(density) { CompactHeaderHeight.toPx() }
        val travel = (collapsedPx - expandedPx).coerceAtLeast(1f)

        // The sheet state is a fraction of its travel (0 = peek strip, 1 = queue open), not
        // a pixel offset, so it survives the constraints changing between layout passes.
        val sheet = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val progress = sheet.value.coerceIn(0f, 1f)
        val expanded = progress > 0.9f
        val sheetTopPx = collapsedPx - progress * travel

        fun settle(velocityPx: Float) {
            val velocity = velocityPx / travel   // fractions per second, sign as on screen
            val target = when {
                // A flick skips straight past the half state in its direction.
                velocity < -3f -> 1f
                velocity > 3f -> 0f
                velocity < -0.6f -> Anchors.firstOrNull { it > progress + 0.05f } ?: 1f
                velocity > 0.6f -> Anchors.lastOrNull { it < progress - 0.05f } ?: 0f
                else -> Anchors.minByOrNull { abs(it - progress) } ?: 0f
            }
            scope.launch { sheet.animateTo(target, spring(stiffness = Spring.StiffnessLow)) }
        }

        fun dragBy(deltaPx: Float) {
            scope.launch { sheet.snapTo((sheet.value - deltaPx / travel).coerceIn(0f, 1f)) }
        }

        // Dragging down on a queue list that is already at its top collapses the sheet.
        val collapseOnOverscroll = remember(travel) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0f || sheet.value <= 0f) return Offset.Zero
                    val next = (sheet.value - available.y / travel).coerceAtLeast(0f)
                    val consumedPx = (sheet.value - next) * travel
                    scope.launch { sheet.snapTo(next) }
                    return Offset(0f, consumedPx)
                }

                // Releasing mid-travel must land on an anchor, same as releasing the handle.
                override suspend fun onPreFling(available: Velocity): Velocity {
                    val resting = Anchors.any { abs(it - sheet.value) < 0.001f }
                    if (resting) return Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }

        LaunchedEffect(queue.size) {
            if (queue.size <= 1) sheet.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
        }

        // Back closes the queue sheet before it leaves Now Playing.
        BackHandler(enabled = progress > 0.01f) { settle(4_000f) }

        // Tapping the player bar while already here opens the queue, the same resting state a
        // drag up would reach. Cleared on use so leaving and returning does not reopen it.
        LaunchedEffect(showQueue.value) {
            if (!showQueue.value) return@LaunchedEffect
            sheet.animateTo(Anchors.last(), spring(stiffness = Spring.StiffnessLow))
            showQueue.value = false
        }

        val listState = rememberLazyListState()
        LaunchedEffect(expanded, index) {
            if (expanded && index >= 0) listState.scrollToItem(index)
        }

        val sheetTopDp = with(density) { sheetTopPx.toDp() }

        // Layer 1 — full-bleed artwork backdrop above the sheet; fades out as the compact
        // header takes over at the end of the travel.
        val backdropAlpha = 1f - ramp(progress, 0.7f, 1f)
        if (backdropAlpha > 0.01f && artworkUrl != null) {
            Box(Modifier.fillMaxWidth().height(sheetTopDp).alpha(backdropAlpha)) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Background.copy(alpha = 0.1f),
                            0.45f to Background.copy(alpha = 0.35f),
                            0.75f to Background.copy(alpha = 0.85f),
                            1f to Background,
                        ),
                    ),
                )
            }
        }

        // Layer 2 — the player. Fills the space above the sheet, anchored to its bottom edge,
        // and fades before the compact header appears.
        val playerAlpha = 1f - ramp(progress, 0.6f, 0.92f)
        if (playerAlpha > 0.01f) {
            PlayerPane(
                paneHeight = sheetTopDp,
                item = item,
                lyrics = lyrics,
                onDownload = download,
                downloadState = item?.let { downloads[it.url] },
                liked = item?.let { it.url in likedSet } ?: false,
                onLike = like,
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
                modifier = Modifier.alpha(playerAlpha),
            )
        }

        // Layer 3 — compact header, only for the expanded state.
        val headerAlpha = ramp(progress, 0.75f, 1f)
        if (headerAlpha > 0.01f) {
            CompactHeader(
                item = item,
                artworkUrl = artworkUrl,
                isPlaying = isPlaying,
                onPlayPause = { engine.playPause() },
                onCollapse = { settle(4_000f) },
                modifier = Modifier.alpha(headerAlpha),
            )
        }

        // Layer 4 — the queue sheet.
        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, sheetTopPx.roundToInt()) }
                .nestedScroll(collapseOnOverscroll)
                .background(Surface),
        ) {
            SheetHeader(
                progress = progress,
                queueSize = queue.size,
                queueTitle = queueTitle,
                nextTitle = nextTitle(queue, index, repeatMode),
                shuffle = shuffle,
                onDrag = ::dragBy,
                onDragStopped = ::settle,
                onToggle = { settle(if (progress > 0.25f) 4_000f else -4_000f) },
                onShuffle = { engine.toggleShuffle() },
                onClear = { engine.clearQueue() },
            )
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

/** Linear 0..1 ramp of [value] between [from] and [to], clamped. */
private fun ramp(value: Float, from: Float, to: Float): Float =
    ((value - from) / (to - from)).coerceIn(0f, 1f)

/**
 * Handle plus caption row. Collapsed it reads as an "up next" strip; from the half state
 * on it becomes the queue's own header ("playing from" caption, count, shuffle/clear).
 */
@Composable
private fun SheetHeader(
    progress: Float,
    queueSize: Int,
    queueTitle: String?,
    nextTitle: String?,
    shuffle: Boolean,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    onToggle: () -> Unit,
    onShuffle: () -> Unit,
    onClear: () -> Unit,
) {
    val asQueue = progress > 0.25f
    Column(
        Modifier
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState(onDrag),
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> onDragStopped(velocity) },
            )
            .clickable(onClick = onToggle),
    ) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(Hairline)
                .align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (asQueue) "_playing_from;" else "up next",
                    color = PsSteel400,
                    fontFamily = FontMono,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (asQueue) "${queueTitle ?: "queue"} · $queueSize" else nextTitle ?: "nothing queued",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (asQueue) {
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

/** Thumb, title, artist and play/pause pinned to the top while the queue is open. */
@Composable
private fun CompactHeader(
    item: QueueItem?,
    artworkUrl: String?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(CompactHeaderHeight)
            .background(Background)
            .clickable(onClick = onCollapse)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artworkUrl, Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item?.title?.ifBlank { item.videoId } ?: "nothing playing",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item?.artist.orEmpty(),
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = TextPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * Title, download (SoundCloud only), progress and transport, anchored to the bottom of the
 * pane so they sit right above the sheet whatever its position; the cover behind them is
 * the backdrop layer. Lyrics scroll in below when the sheet is collapsed.
 */
@Composable
private fun PlayerPane(
    paneHeight: Dp,
    item: QueueItem?,
    lyrics: LyricsResult?,
    onDownload: (() -> Unit)?,
    downloadState: DownloadManager.State?,
    liked: Boolean,
    onLike: (() -> Unit)?,
    position: Double,
    duration: Double,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    scrubPosition: Float?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().height(paneHeight).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().height(paneHeight).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item?.title?.ifBlank { item.videoId } ?: "nothing playing",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item?.artist.orEmpty(),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onLike != null) {
                    Spacer(Modifier.width(4.dp))
                    LikeButton(liked, onLike, tint = TextPrimary)
                }
                if (onDownload != null) DownloadButton(downloadState, onDownload, tint = TextPrimary)
            }
            Spacer(Modifier.height(12.dp))

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
            TransportRow(isPlaying, shuffle, repeatMode, engine)
            Spacer(Modifier.height(8.dp))
        }

        lyrics?.let { result -> LyricsBlock(result, position) }
    }
}

@Composable
private fun TransportRow(isPlaying: Boolean, shuffle: Boolean, repeatMode: RepeatMode, engine: PlayerEngine) {
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
        // Filled disc behind the play glyph, like the reference.
        Box(
            Modifier
                .size(64.dp)
                .background(Accent)
                .clickable { engine.playPause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = OnAccent,
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

@Composable
private fun LyricsBlock(result: LyricsResult, position: Double) {
    Spacer(Modifier.height(16.dp))
    Text(
        if (result.synced) "lyrics" else "lyrics (unsynced)",
        color = PsSteel400,
        fontFamily = FontMono,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
    val activeIndex = if (result.synced) {
        result.lines.indexOfLast { it.timeMs <= (position * 1000).toLong() }
    } else -1
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        result.lines.forEachIndexed { lineIndex, line ->
            Text(
                line.text,
                color = if (lineIndex == activeIndex) PsIrisCyan else TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
    Spacer(Modifier.height(PeekHeight))
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
