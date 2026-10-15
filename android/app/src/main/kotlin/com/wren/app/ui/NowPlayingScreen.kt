package com.wren.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.Lyrics
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import models.LyricsResult
import models.QueueItem
import models.RepeatMode
import player.PlayerEngine
import util.runCatchingExceptCancellation
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

/** How long a manual scroll buys quiet before auto-scroll resumes, so reading ahead is possible. */
private const val USER_SCROLL_GRACE_MS = 6_000L

/** What the lyrics surface is showing. There is no idle: a track is always known by then. */
private sealed class LyricsState {
    object Loading : LyricsState()
    object NotFound : LyricsState()
    object Unavailable : LyricsState()
    data class Loaded(val result: LyricsResult) : LyricsState()
}

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
 * The collapsed sheet is itself the way in: its strip is tappable and draggable, so there is
 * nothing for a separate mini player to do while this screen is open.
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
    val queueTitle by engine.queueTitle.collectAsState()
    val downloads by DownloadManager.states.collectAsState()
    val likedSet by SoundCloudLikes.liked.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val item = queue.getOrNull(index)
    val artworkUrl = artworkFor(item)
    // Only SoundCloud tracks can be saved, and only when saving has been turned on: the request
    // is anonymous, but a client fetching whole files is what gets a client id rotated.
    val download: (() -> Unit)? = item
        ?.takeIf { it.source == Source.SOUNDCLOUD && allowSoundCloudDownloads }
        ?.let { track -> { DownloadManager.enqueue(track, downloadsDestination(context)) } }
    val like: (() -> Unit)? = item?.takeIf { it.source == Source.SOUNDCLOUD && SoundCloudAuth.isAuthenticated }?.let { track ->
        { scope.launch { SoundCloudLikes.toggle(track.url) } }
    }

    var lyrics by remember(item?.videoId) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    var scrubPosition by remember(item?.videoId) { mutableStateOf<Float?>(null) }
    // The sheet holds the queue and the lyrics rather than only the queue, so reading them is the
    // same gesture as opening it. The choice survives a track change, so reading does not restart.
    var showLyrics by remember { mutableStateOf(false) }
    val displayTitle by engine.displayTitle.collectAsState()

    // Keyed on the metadata actually sent, so a fast track change never ends up fetching the
    // previous track's title and artist.
    LaunchedEffect(item?.videoId, displayTitle, item?.artist) {
        lyrics = LyricsState.Loading
        val title = displayTitle.ifBlank { item?.title.orEmpty() }
        if (item == null || title.isBlank()) return@LaunchedEffect
        // lrclib matches better with a duration; wait up to 5s for it and proceed without
        // otherwise, rather than refetching every time the duration settles.
        repeat(25) {
            if (duration > 0) return@repeat
            delay(200)
        }
        lyrics = runCatchingExceptCancellation { fetchLyrics(title, item.artist, duration) }
            .fold(
                onSuccess = { found -> found?.let(LyricsState::Loaded) ?: LyricsState.NotFound },
                onFailure = { LyricsState.Unavailable },
            )
    }

    // No back handler of its own: the lyrics are inside the sheet now, so the sheet's handler is
    // the one that answers and a second meaning for the same gesture would only surprise.

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
                onOpenLyrics = { showLyrics = true; settle(-4_000f) },
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
                nowPlaying = item?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" · ") },
                shuffle = shuffle,
                showLyrics = showLyrics,
                onShowLyrics = { showLyrics = it },
                onDrag = ::dragBy,
                onDragStopped = ::settle,
                onToggle = { settle(if (progress > 0.25f) 4_000f else -4_000f) },
                onShuffle = { engine.toggleShuffle() },
                onClear = { engine.clearQueue() },
            )
            Divider(color = HairlineSoft)
            if (showLyrics) {
                LyricsList(
                    state = lyrics,
                    position = position,
                    // Auto-scroll only once the sheet is actually open, so reading does not race a
                    // list nobody can see yet.
                    scrolling = expanded,
                    onSeek = { timeMs -> engine.seek(timeMs / 1000.0) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(Modifier.weight(1f), state = listState) {
                    itemsIndexed(queue, key = { i, q -> "$i:${q.source}:${q.videoId}" }) { queueIndex, queueItem ->
                        TrackRow(
                            trackKey = queueItem.videoId,
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
    nowPlaying: String?,
    shuffle: Boolean,
    showLyrics: Boolean,
    onShowLyrics: (Boolean) -> Unit,
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
                if (asQueue) {
                    // The sheet holds two things now, so the line that only named the queue becomes
                    // the switch between them.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SheetTab("_queue;", selected = !showLyrics, onClick = { onShowLyrics(false) })
                        Spacer(Modifier.width(14.dp))
                        SheetTab("_lyrics;", selected = showLyrics, onClick = { onShowLyrics(true) })
                    }
                } else {
                    Text(
                        "up next",
                        color = PsSteel400,
                        fontFamily = FontMono,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    when {
                        !asQueue -> nextTitle ?: "nothing queued"
                        showLyrics -> nowPlaying ?: "nothing playing"
                        else -> "${queueTitle ?: "queue"} · $queueSize"
                    },
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!asQueue) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Show queue",
                    tint = TextSecondary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            } else if (!showLyrics) {
                // Queue actions say nothing while reading, so they give the room to the lyrics.
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
            }
        }
    }
}

/** One half of the sheet; the label that used to sit here is now the way to change halves. */
@Composable
private fun SheetTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) TextPrimary else PsSteel400,
        fontFamily = FontMono,
        fontSize = 11.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
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
    onOpenLyrics: () -> Unit,
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
                LyricsToggle(onOpenLyrics)
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
    }
}

/** Opens the sheet straight into the lyrics, for when that is why you reached for the player. */
@Composable
private fun LyricsToggle(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = TextSecondary)
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

/**
 * The lyrics half of the sheet, in the karaoke shape: the line being sung sits centred and larger,
 * and the ones around it fade with distance. Synced lines can be tapped to jump there, which is the
 * reason to read them next to the player rather than instead of it. A manual scroll buys
 * [USER_SCROLL_GRACE_MS] of quiet so reading ahead does not fight the song.
 */
@Composable
private fun LyricsList(
    state: LyricsState,
    position: Double,
    scrolling: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val autoScroll = rememberUserAwareAutoScroll(listState)

    // -1 for unsynced lyrics, so nothing is highlighted instead of the first line being wrong.
    val activeIndex = remember(state, position) {
        val result = (state as? LyricsState.Loaded)?.result ?: return@remember -1
        if (!result.synced) return@remember -1
        result.lines.indexOfLast { it.timeMs <= (position * 1000).toLong() }.coerceAtLeast(0)
    }

    // Half a viewport of padding top and bottom turns "scroll this item to the top" into "centre
    // this item", which is the whole shape of a karaoke line: the first and last lines can reach
    // the middle too.
    LaunchedEffect(activeIndex, scrolling) {
        if (scrolling && activeIndex >= 0) autoScroll(activeIndex)
    }

    Column(modifier) {
        Text(
            when (state) {
                is LyricsState.Loading -> "loading..."
                is LyricsState.NotFound -> "// no_lyrics;"
                is LyricsState.Unavailable -> "// lyrics_unavailable;"
                is LyricsState.Loaded -> if (state.result.synced) "synced;" else "plain_text;"
            },
            color = PsSteel400,
            fontFamily = FontMono,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )

        when (state) {
            is LyricsState.Loading -> LyricsNotice("...")
            is LyricsState.NotFound -> LyricsNotice("// no_lyrics;")
            is LyricsState.Unavailable -> LyricsNotice("// lyrics_unavailable;")
            is LyricsState.Loaded -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = maxHeight / 2),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(state.result.lines) { index, line ->
                        val distance = if (activeIndex < 0) 0 else abs(index - activeIndex)
                        val isActive = distance == 0
                        // The neighbours shrink rather than the active line growing: scaling the
                        // active one up would push a long line past the screen edge, and that is
                        // the one line you have to be able to read. Scaled through graphicsLayer
                        // rather than by font size, because a changing text size changes the
                        // item's height and the list jumps under its own auto-scroll.
                        val scale by animateFloatAsState(if (isActive) 1f else 0.84f, label = "lyricScale")
                        Text(
                            line.text,
                            fontFamily = FontMono,
                            fontSize = 17.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            color = when {
                                // Unsynced lyrics have no active line, so nothing is dimmed.
                                activeIndex < 0 -> TextPrimary
                                isActive -> TextPrimary
                                distance == 1 -> TextPrimary.copy(alpha = 0.55f)
                                else -> TextPrimary.copy(alpha = 0.26f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                // Only synced lines know where they are; a plain line is all timeMs 0.
                                .clickable(enabled = state.result.synced) { onSeek(line.timeMs) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsNotice(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = TextSecondary, fontFamily = FontMono, fontSize = 13.sp)
}

@Composable
private fun rememberUserAwareAutoScroll(listState: LazyListState): suspend (Int) -> Unit {
    var lastUserScrollAt by remember { mutableStateOf(0L) }
    var programmatic by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling && !programmatic) lastUserScrollAt = System.currentTimeMillis()
        }
    }

    return remember(listState) {
        val scrollTo: suspend (Int) -> Unit = { index ->
            if (System.currentTimeMillis() - lastUserScrollAt > USER_SCROLL_GRACE_MS) {
                programmatic = true
                try {
                    listState.animateScrollToItem(index)
                } finally {
                    programmatic = false
                }
            }
        }
        scrollTo
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
