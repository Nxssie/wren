package com.wren.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.wren.app.MainActivity
import com.wren.app.R
import com.wren.app.WrenApplication
import com.wren.app.playback.WrenPlaybackService
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
import okhttp3.Request
import util.Http

private fun bg() = ColorProvider(R.color.wren_widget_bg)
private fun onBg() = ColorProvider(R.color.wren_widget_text)

/** What the widget shows. Playback position is deliberately absent: a per-second repaint is not
 * progress, and the widget has no seek bar to carry it. */
private data class WrenWidgetState(
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val playing: Boolean,
    val hasTrack: Boolean,
) {
    companion object {
        fun of(context: Context): WrenWidgetState {
            val engine = (context.applicationContext as? WrenApplication)?.engine ?: return idle()
            val item = engine.queue.value.getOrNull(engine.queueIndex.value) ?: return idle()
            return WrenWidgetState(
                title = engine.displayTitle.value.ifBlank { item.title }.ifBlank { "Wren" },
                subtitle = item.artist,
                artworkUrl = artworkFor(item),
                playing = engine.isPlaying.value,
                hasTrack = true,
            )
        }

        fun idle() = WrenWidgetState("Wren", "nothing playing", null, playing = false, hasTrack = false)
    }
}

/**
 * The home-screen widget: what is playing, with previous / play-pause / next. Controls go to
 * [WrenPlaybackService] as a foreground-service PendingIntent, which is what keeps them working
 * while the app is in the background — and what the system recognises as a widget interaction.
 */
class WrenWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WrenWidgetState.of(context)
        // Decoded before the content so the render itself never waits on the network.
        val artwork = state.artworkUrl?.let { WrenWidgetArtwork.load(it) }
        provideContent { WrenWidgetContent(state, artwork) }
    }
}

class WrenWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WrenWidget()
}

@Composable
private fun WrenWidgetContent(state: WrenWidgetState, artwork: Bitmap?) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bg())
            .clickable(actionStartActivity(openIntent(context, state.hasTrack)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.size(60.dp).background(ColorProvider(R.color.wren_widget_placeholder)),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(
                    provider = ImageProvider(artwork),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
        }

        Spacer(GlanceModifier.width(14.dp))

        Column(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                state.title,
                maxLines = 1,
                style = TextStyle(
                    color = onBg(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            if (state.hasTrack) {
                // The YouTube Music shape: a spread transport row under the title, play larger than
                // the skips, rather than three buttons stacked beside the text.
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetControl(R.drawable.ic_widget_previous, WrenPlaybackService.ACTION_PREVIOUS, "Previous", 32.dp, 6.dp)
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetControl(
                        if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                        WrenPlaybackService.ACTION_TOGGLE,
                        if (state.playing) "Pause" else "Play",
                        38.dp,
                        5.dp,
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    WidgetControl(R.drawable.ic_widget_next, WrenPlaybackService.ACTION_NEXT, "Next", 32.dp, 6.dp)
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun WidgetControl(icon: Int, serviceAction: String, description: String, size: Dp, inset: Dp) {
    val context = LocalContext.current
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        colorFilter = ColorFilter.tint(onBg()),
        modifier = GlanceModifier
            .size(size)
            .clickable(
                actionStartService(
                    intent = Intent(context, WrenPlaybackService::class.java).setAction(serviceAction),
                    isForegroundService = true,
                )
            )
            .padding(inset),
    )
}

/** A tap on the body opens Now Playing when something is loaded, and the app otherwise. */
private fun openIntent(context: Context, hasTrack: Boolean): Intent =
    Intent(context, MainActivity::class.java).apply {
        if (hasTrack) action = MainActivity.ACTION_NOW_PLAYING
    }

/**
 * Repaints every placed widget when the track, its artwork or the transport state changes.
 *
 * The engine is a process singleton, so the flow is only alive while the app is: a widget cannot
 * update after the process dies, and on the next start the collector re-reads the restored track.
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
                engine.displayTitle,
                engine.isPlaying,
                engine.queueIndex,
                engine.queue,
            ) { _, _, _, _ -> WrenWidgetState.of(app) }
                .distinctUntilChanged()
                .collect { state ->
                    // Warm the cache first so the repaint does not fetch inside provideGlance.
                    state.artworkUrl?.let { WrenWidgetArtwork.load(it) }
                    WrenWidget().updateAll(app)
                }
        }
    }
}

/** Artwork downscaled for a widget tile, cached per URL so a repaint does not re-hit the network. */
private object WrenWidgetArtwork {
    private const val TARGET_PX = 256
    private val cache = LruCache<String, Bitmap>(8)

    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        // The widget has a render budget of its own; better a placeholder tile than a late one.
        withTimeoutOrNull(5_000) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    response.body?.byteStream()?.use { decode(it.readBytes()) }
                }
            }.getOrNull()
        }?.also { cache.put(url, it) }
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
