package com.wren.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wren.app.MainActivity
import com.wren.app.R
import com.wren.app.player.PlaybackControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import util.Log
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps playback alive while Wren is in the background and shows
 * the media notification. It owns no player itself — it renders and forwards actions to
 * the engine currently registered as [controls].
 *
 * The notification is a [androidx.media.app.NotificationCompat.MediaStyle] card backed by a
 * [MediaSessionCompat], which is what turns it into the system media widget (lock screen,
 * notification shade, quick-settings carousel) instead of a dismissible alert.
 */
class WrenPlaybackService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val artworkCache = LruCache<String, Bitmap>(MAX_CACHED_ARTWORK)
    private val artworkLoading = mutableSetOf<String>()

    private lateinit var mediaSession: MediaSessionCompat
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSessionCompat(this, "wren").apply {
            setSessionActivity(contentPendingIntent())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (controls?.notificationPlaying == false) controls?.onToggle()
                }

                override fun onPause() {
                    if (controls?.notificationPlaying == true) controls?.onToggle()
                }

                override fun onSkipToNext() = controls?.onNext() ?: Unit

                override fun onSkipToPrevious() = controls?.onPrevious() ?: Unit

                override fun onSeekTo(pos: Long) {
                    controls?.onSeek(pos / 1000.0)
                    publish(foreground = false)
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (controls == null) {
            // A sticky restart after the process died has no player to control, and entering the
            // foreground for an empty player is exactly what the platform forbids.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE -> controls?.onToggle()
            ACTION_NEXT -> controls?.onNext()
            ACTION_PREVIOUS -> controls?.onPrevious()
            ACTION_LIKE -> controls?.onLike()
            ACTION_DISMISS -> {
                // Only reachable while paused: a playing notification is ongoing and cannot be
                // swiped. A foreground service with no notification is both unstoppable by the
                // user and a policy violation, so leaving is the whole job here.
                if (controls?.notificationPlaying != true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        publish(foreground = true)
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    /**
     * Pushes the current transport state into the session and repaints the notification.
     * The session carries position plus a playback speed, so the system animates the seek
     * bar by itself and we never have to tick notifications.
     */
    private fun publish(foreground: Boolean) {
        val controls = controls
        val playing = controls?.notificationPlaying == true

        if (::mediaSession.isInitialized) {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, controls?.notificationTitle?.ifBlank { "Wren" } ?: "Wren")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, controls?.notificationSubtitle.orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMillis(controls))
                .apply {
                    artworkBitmap(controls?.notificationArtwork)?.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    }
                }
                .build()
            val state = PlaybackStateCompat.Builder()
                .setActions(SUPPORTED_ACTIONS)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    positionMillis(controls),
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build()
            mediaSession.setMetadata(metadata)
            mediaSession.setPlaybackState(state)
        }

        val notification = buildNotification(playing, positionMillis(controls), durationMillis(controls))
        if (foreground) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        ensureArtwork(controls?.notificationArtwork)
    }

    private fun buildNotification(playing: Boolean, positionMs: Long, durationMs: Long): Notification {
        val controls = controls
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(controls?.notificationTitle?.ifBlank { "Wren" } ?: "Wren")
            .setContentText(controls?.notificationSubtitle.orEmpty())
            .setLargeIcon(artworkBitmap(controls?.notificationArtwork))
            .setContentIntent(contentPendingIntent())
            // Swiping a paused notification away has to mean "stop showing me this": the
            // service stops with it (see ACTION_DISMISS).
            .setDeleteIntent(actionPendingIntent(ACTION_DISMISS))
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionPendingIntent(ACTION_PREVIOUS))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                actionPendingIntent(ACTION_TOGGLE),
            )
            .addAction(android.R.drawable.ic_media_next, "Next", actionPendingIntent(ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        if (durationMs > 0) {
            builder.setProgress(
                (durationMs / 1000).toInt(),
                (positionMs / 1000).toInt().coerceAtLeast(0),
                false,
            )
        }
        return builder.build()
    }

    /** Decodes downscaled art: the notification never needs more than [ART_TARGET_PX]. */
    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > ART_TARGET_PX || bounds.outHeight / sample > ART_TARGET_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The widget's body and the session card both land on Now Playing, not on the home tab. */
    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NOW_PLAYING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionPendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, WrenPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun positionMillis(controls: PlaybackControls?): Long =
        ((controls?.notificationPosition ?: 0.0) * 1000).toLong().coerceAtLeast(0)

    private fun durationMillis(controls: PlaybackControls?): Long =
        ((controls?.notificationDuration ?: 0.0) * 1000).toLong().coerceAtLeast(0)

    private fun artworkBitmap(url: String?): Bitmap? = url?.let { artworkCache.get(it) }

    /** Downloads album art once per URL, then repaints the notification with it. */
    private fun ensureArtwork(url: String?) {
        if (url.isNullOrBlank()) return
        if (artworkCache.get(url) != null) return
        synchronized(artworkLoading) {
            if (!artworkLoading.add(url)) return
        }
        scope.launch {
            val bitmap = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    response.body?.byteStream()?.use { stream -> decodeArtwork(stream.readBytes()) }
                }
            }.onFailure { Log.w(TAG, "artwork download failed for $url", it) }.getOrNull()
            synchronized(artworkLoading) { artworkLoading.remove(url) }
            if (bitmap != null) {
                artworkCache.put(url, bitmap)
                mainHandler.post { if (!destroyed && controls != null) publish(foreground = false) }
            } else {
                Log.w(TAG, "artwork decode returned no bitmap for $url")
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Now playing controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        @Volatile
        var controls: PlaybackControls? = null

        private const val CHANNEL_ID = "wren_playback"
        private const val TAG = "WrenPlaybackService"
        private const val NOTIFICATION_ID = 1
        private const val MAX_CACHED_ARTWORK = 8
        private const val ART_TARGET_PX = 512
        private const val ACTION_START = "com.wren.app.START"
        private const val ACTION_UPDATE = "com.wren.app.UPDATE"
        private const val ACTION_DISMISS = "com.wren.app.DISMISS"

        // The home-screen widget sends these straight to the service, so they travel a step
        // further than the notification actions above.
        internal const val ACTION_TOGGLE = "com.wren.app.TOGGLE"
        internal const val ACTION_NEXT = "com.wren.app.NEXT"
        internal const val ACTION_PREVIOUS = "com.wren.app.PREVIOUS"
        internal const val ACTION_LIKE = "com.wren.app.LIKE"
        private const val SUPPORTED_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO

        fun start(context: Context) = send(context, ACTION_START)

        fun update(context: Context) {
            if (controls != null) send(context, ACTION_UPDATE)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WrenPlaybackService::class.java))
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, WrenPlaybackService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
