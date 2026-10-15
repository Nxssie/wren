package com.wren.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import api.SoundCloudLikes
import api.YouTubeLikes
import auth.GoogleAuth
import auth.SoundCloudAuth
import com.wren.app.MainActivity
import com.wren.app.R
import com.wren.app.WrenApplication
import com.wren.app.playback.WrenPlaybackService
import com.wren.app.player.ExoPlayerEngine
import com.wren.app.util.artworkFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import models.QueueItem
import models.Source
import okhttp3.Request
import util.Http
import util.Log

/** The rounded card everything sits on; its radius is also what the artwork's corners are cut to. */
private val card = ImageProvider(R.drawable.wren_widget_bg)

/** Shows while the artwork is still on the wire; already shaped like the corner it sits in. */
private val artworkLoading = ImageProvider(R.drawable.wren_widget_placeholder)

private fun onBg() = ColorProvider(R.color.wren_widget_text)

/** Tap target of each control; the glyph is drawn smaller inside it. */
private val CONTROL_SIZE = 44.dp

/** Space above and below the card, so it sits shorter than the launcher's cell. */
private val CARD_MARGIN = 10.dp

private const val TAG = "WrenWidget"

/** What the widget shows. Playback position is deliberately absent: a per-second repaint is not
 * progress, and the widget has no seek bar to carry it. */
private data class WrenWidgetState(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val playing: Boolean,
    val hasTrack: Boolean,
    val source: Source = Source.YT_MUSIC,
    /** Whether the track is kept on its platform, or null when the user is not signed in there. */
    val liked: Boolean? = null,
) {
    companion object {
        fun of(engine: ExoPlayerEngine?): WrenWidgetState {
            val item = engine?.queue?.value?.getOrNull(engine.queueIndex.value) ?: return idle
            return WrenWidgetState(
                title = engine.displayTitle.value.ifBlank { item.title }.ifBlank { "Wren" },
                artist = item.artist,
                artworkUrl = artworkFor(item),
                playing = engine.isPlaying.value,
                hasTrack = true,
                source = item.source,
                liked = likedOf(item),
            )
        }

        /** The platform's own "kept" state: a SoundCloud like, or a YouTube like (the collection). */
        private fun likedOf(item: QueueItem): Boolean? = when (item.source) {
            Source.SOUNDCLOUD -> if (SoundCloudAuth.isAuthenticated) SoundCloudLikes.isLiked(item.url) else null
            else -> if (GoogleAuth.isAuthenticated) YouTubeLikes.isLiked(item.videoId) else null
        }

        val idle = WrenWidgetState("Wren", "", null, playing = false, hasTrack = false)
    }
}

/**
 * The home-screen widget: what is playing, with previous / play-pause / next. Controls go to
 * [WrenPlaybackService] as a foreground-service PendingIntent, which is what keeps them working
 * while the app is in the background — and what the system recognises as a widget interaction.
 *
 * Everything the layout depends on is read *inside* the composition. Glance runs [provideGlance]
 * once per session and answers later updates by recomposing the same lambda, so a value captured
 * out here — the track, the transport, the launcher's size — would stay frozen at whatever it was
 * when the session opened. That is exactly the bug this replaces: play never turned into pause.
 */
class WrenWidget : GlanceAppWidget() {

    /** The cover is cut square to the card's height, so the layout needs the exact size, not a bucket. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WrenWidgetContent() }
    }

    /** Glance's default swallows the cause; a widget that only says "can't show content" is undebuggable. */
    override fun onCompositionError(context: Context, glanceId: GlanceId, appWidgetId: Int, throwable: Throwable) {
        Log.w(TAG, "composition failed for widget $appWidgetId", throwable)
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }
}

class WrenWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WrenWidget()
}

/** The engine's state as composition state, so a change in it recomposes the widget. */
@Composable
private fun rememberWidgetState(engine: ExoPlayerEngine?): WrenWidgetState {
    if (engine == null) return WrenWidgetState.idle
    // Each flow is subscribed on its own; the derived state is rebuilt from their current values.
    engine.displayTitle.collectAsState().value
    engine.isPlaying.collectAsState().value
    engine.queueIndex.collectAsState().value
    engine.queue.collectAsState().value
    SoundCloudLikes.liked.collectAsState().value
    YouTubeLikes.liked.collectAsState().value
    val state = WrenWidgetState.of(engine)
    // YouTube ratings are looked up per video; ask for this one so the icon is right, not a guess.
    val videoId = engine.queue.value.getOrNull(engine.queueIndex.value)?.takeIf { it.source != Source.SOUNDCLOUD }?.videoId
    LaunchedEffect(videoId) { if (videoId != null) YouTubeLikes.ensureKnown(videoId) }
    return state
}

@Composable
private fun WrenWidgetContent() {
    val context = LocalContext.current
    val engine = (context.applicationContext as? WrenApplication)?.engine
    val state = rememberWidgetState(engine)
    // The card's real height for this placement, which is what the cover is cut square to.
    val side = LocalSize.current.height - CARD_MARGIN * 2
    val url = state.artworkUrl
    // produceState keeps its value across key changes, so a new URL first drops back to whatever
    // is decoded for it (or the placeholder) before fetching; without that the widget kept showing
    // the previous track's cover under the new title.
    val raw by produceState<Bitmap?>(initialValue = null, url) {
        value = url?.let { WrenWidgetArtwork.cached(it) ?: WrenWidgetArtwork.load(it) }
    }
    val cover = remember(raw, side) { raw?.let { WrenWidgetArtwork.cut(context, url.orEmpty(), it, side) } }
    val tint = remember(raw) { raw?.let { ColorProvider(WrenWidgetArtwork.tintOf(url.orEmpty(), it)) } }
    Log.i(TAG, "render title=${state.title} playing=${state.playing} side=$side cover=${cover != null}")

    // A card coloured from the cover needs its own text colour; the plain one keeps the theme's.
    val onCard = tint?.let { ColorProvider(Color.White) } ?: onBg()
    val cardModifier = if (tint == null) {
        GlanceModifier.background(card)
    } else {
        GlanceModifier.background(card, colorFilter = ColorFilter.tint(tint))
    }

    Box(GlanceModifier.fillMaxSize().padding(vertical = CARD_MARGIN)) {
    Row(
        modifier = cardModifier
            .fillMaxSize()
            .clickable(actionStartActivity(openIntent(context, state.hasTrack))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        // Square by construction and flush with the left edge: whatever the launcher does with the
        // card's height, the cover keeps the 1:1 shape the YouTube Music widget gives it.
        Box(
            modifier = GlanceModifier.size(side).background(artworkLoading),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(
                    provider = ImageProvider(cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = GlanceModifier.defaultWeight().padding(start = 16.dp, top = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                if (state.artist.isBlank()) state.title else "${state.title} \u2013 ${state.artist}",
                maxLines = 1,
                style = TextStyle(
                    color = onCard,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (state.hasTrack) {
                Spacer(GlanceModifier.height(6.dp))
                // Previous, play, next and the platform's keep-this action, spread evenly.
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    WidgetControl(R.drawable.ic_widget_previous, WrenPlaybackService.ACTION_PREVIOUS, "Previous")
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetControl(
                        if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        WrenPlaybackService.ACTION_TOGGLE,
                        if (state.playing) "Pause" else "Play",
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetControl(R.drawable.ic_widget_next, WrenPlaybackService.ACTION_NEXT, "Next")
                    if (state.liked != null) {
                        Spacer(GlanceModifier.defaultWeight())
                        val soundCloud = state.source == Source.SOUNDCLOUD
                        WidgetControl(
                            icon = when {
                                soundCloud && state.liked -> R.drawable.ic_widget_heart_filled
                                soundCloud -> R.drawable.ic_widget_heart
                                state.liked -> R.drawable.ic_widget_library_added
                                else -> R.drawable.ic_widget_library_add
                            },
                            serviceAction = WrenPlaybackService.ACTION_LIKE,
                            description = when {
                                soundCloud && state.liked -> "Unlike"
                                soundCloud -> "Like"
                                state.liked -> "Remove from collection"
                                else -> "Add to collection"
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun WidgetControl(icon: Int, serviceAction: String, description: String) {
    val context = LocalContext.current
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
        modifier = GlanceModifier
            .size(CONTROL_SIZE)
            .clickable(
                actionStartService(
                    intent = Intent(context, WrenPlaybackService::class.java).setAction(serviceAction),
                    isForegroundService = true,
                )
            )
            .padding(8.dp),
    )
}

/** A tap on the body opens Now Playing when something is loaded, and the app otherwise. */
private fun openIntent(context: Context, hasTrack: Boolean): Intent =
    Intent(context, MainActivity::class.java).apply {
        if (hasTrack) action = MainActivity.ACTION_NOW_PLAYING
    }

/**
 * Wakes every placed widget when the track or the transport changes.
 *
 * A widget whose Glance session is alive already recomposes from the engine's flows; this covers
 * the one that has gone idle, whose session Glance closed after the launcher's last event and which
 * would otherwise show the last frame until something else prodded it. A repaint reads nothing but
 * memory, so it is cheap to fire on every change.
 *
 * The engine is a process singleton, so this only runs while the app does: nothing can repaint the
 * widget after the process dies, and on the next start the collector re-reads the restored track.
 */
object WrenWidgetUpdater {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext as? WrenApplication ?: return
        val engine = app.engine
        job = scope.launch {
            combine(
                listOf(
                    engine.displayTitle,
                    engine.isPlaying,
                    engine.queueIndex,
                    engine.queue,
                    SoundCloudLikes.liked,
                    YouTubeLikes.liked,
                ),
            ) { WrenWidgetState.of(engine) }
                .distinctUntilChanged()
                .collect {
                    // One failed repaint must not stop the collector, or the widget would stay stale
                    // for the rest of the process's life.
                    runCatching { WrenWidget().updateAll(app) }
                        .onFailure { Log.w(TAG, "repaint failed", it) }
                }
        }
    }
}

/** Artwork downscaled for a widget tile, cached per URL so a repaint does not re-hit the network. */
private object WrenWidgetArtwork {
    private const val TARGET_PX = 256

    /** Enough pixels to average a colour without walking the whole cover. */
    private const val TINT_SAMPLES = 8

    private val cache = LruCache<String, Bitmap>(8)
    private val cutCache = LruCache<String, Bitmap>(4)
    private val tints = LruCache<String, Color>(8)

    /** The cover if it has already been decoded; the render never waits on the network. */
    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        // The widget has a render budget of its own; better a placeholder tile than a late one.
        withTimeoutOrNull(5_000) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    response.body?.byteStream()?.use { square(decode(it.readBytes())) }
                }
            }.onFailure { Log.w(TAG, "artwork fetch failed for $url", it) }.getOrNull()
        }?.also { cache.put(url, it) }
    }

    /**
     * The cover as a square with its left corners cut to the card's radius — the shape the YouTube
     * Music widget gives it. The radius follows the box the bitmap is drawn into, so this is redone
     * whenever that box changes size and memoised per size.
     */
    fun cut(context: Context, url: String, bitmap: Bitmap, side: Dp): Bitmap {
        val key = "$url@${side.value}"
        cutCache.get(key)?.let { return it }
        val density = context.resources.displayMetrics.density
        val radius = context.resources.getDimension(R.dimen.wren_widget_radius) *
            bitmap.width / (side.value * density)
        val rounded = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rounded)
        canvas.clipPath(
            Path().apply {
                addRoundRect(
                    RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                    floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius),
                    Path.Direction.CW,
                )
            }
        )
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return rounded.also { cutCache.put(key, it) }
    }

    /**
     * The card's colour, taken from the cover the way YouTube Music does it: muted and dark enough
     * that the white title and controls read over any artwork.
     */
    fun tintOf(url: String, bitmap: Bitmap): Color {
        tints.get(url)?.let { return it }
        val tiny = Bitmap.createScaledBitmap(bitmap, TINT_SAMPLES, TINT_SAMPLES, true)
        var red = 0L
        var green = 0L
        var blue = 0L
        var sampled = 0
        for (y in 0 until tiny.height) {
            for (x in 0 until tiny.width) {
                val pixel = tiny.getPixel(x, y)
                // Near-black and near-white pixels say nothing about the cover's colour.
                val luma = AndroidColor.red(pixel) * 299 + AndroidColor.green(pixel) * 587 +
                    AndroidColor.blue(pixel) * 114
                if (luma < 24_000 || luma > 230_000) continue
                red += AndroidColor.red(pixel)
                green += AndroidColor.green(pixel)
                blue += AndroidColor.blue(pixel)
                sampled++
            }
        }
        val average = maxOf(sampled, 1)
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(
            (red / average).toInt(),
            (green / average).toInt(),
            (blue / average).toInt(),
            hsv,
        )
        hsv[1] = hsv[1].coerceAtMost(0.45f)
        hsv[2] = hsv[2].coerceIn(0.20f, 0.38f)
        return Color(AndroidColor.HSVToColor(hsv)).also { tints.put(url, it) }
    }

    /**
     * A square crop of the centre, so a non-square cover is zoomed rather than distorted. YouTube's
     * 4:3 thumbnails letterbox a 16:9 frame in black bars, so those are cropped to the frame's
     * height instead of the image's, or the bars end up inside the square.
     */
    private fun square(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.width == bitmap.height) return bitmap
        val letterboxed = bitmap.width * 3 == bitmap.height * 4
        val side = if (letterboxed) bitmap.width * 9 / 16 else minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > TARGET_PX || bounds.outHeight / sample > TARGET_PX) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
