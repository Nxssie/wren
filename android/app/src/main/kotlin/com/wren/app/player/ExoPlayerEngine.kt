package com.wren.app.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import api.SoundCloudLikes
import api.YouTubeLikes
import api.StreamRequestHeaders
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import util.Http
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import api.ListeningHistory
import api.forgetStreamUrl
import api.resolveStreamUrl
import api.warmupStreamConnection
import com.wren.app.playback.WrenPlaybackService
import com.wren.app.util.artworkFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import models.Source
import models.QueueItem
import models.RepeatMode
import player.LoudnessStore
import player.PlaybackStateStore
import player.PlayerEngine
import player.SavedPlayback
import kotlin.math.abs
import util.Log

/**
 * Media3/ExoPlayer implementation of [PlayerEngine]. It drives a single media item and
 * advances the queue itself (resolving each URL lazily through [resolveStreamUrl]), the
 * same shape the desktop FFmpegPlayer uses, so queues with unresolved URLs work.
 */
private const val PERSIST_EVERY_SEC = 5.0

class ExoPlayerEngine(private val context: Context) : PlayerEngine, PlaybackControls {

    /** Shares the resolver's connection pool; logs media requests so 403s can be diagnosed. */
    private val streamHttpClient = Http.client.newBuilder()
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (!response.isSuccessful) {
                Log.w(
                    "ExoPlayerEngine",
                    "media ${request.method} ${request.url.host}${request.url.encodedPath} -> ${response.code}\n" +
                        request.headers.joinToString("\n") { (k, v) -> "  $k: $v" },
                )
            }
            response
        }
        .build()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Normalises each track to a common level (see Loudness) and measures it while it plays.
     * Getting a processor into the chain means building the audio sink by hand: ExoPlayer offers
     * no other hook for post-processing the decoded PCM.
     */
    private val loudnessProcessor = LoudnessAudioProcessor { videoId, measurement ->
        scope.launch { withContext(Dispatchers.IO) { LoudnessStore.record(videoId, measurement) } }
    }

    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(loudnessProcessor))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    // Same OkHttp client that resolved the URL, and the user agent of the InnerTube client
    // that issued it: googlevideo 403s when the media request does not look like the
    // client that asked for the URL, which is how "nothing plays" with no visible error.
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(renderersFactory)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DataSource.Factory {
                    ResolvingDataSource(OkHttpDataSource.Factory(streamHttpClient).createDataSource()) { spec ->
                        val userAgent = StreamRequestHeaders.userAgentFor(spec.uri.toString())
                        if (userAgent == null) spec else spec.withAdditionalHeaders(mapOf("User-Agent" to userAgent))
                    }
                },
            )
                // The default gives up on a chunk after three tries, which on a patchy mobile
                // link is a few seconds of bad signal. Each retry backs off up to five seconds.
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES)),
        )
        // Audio is cheap to hold: buffer minutes ahead while the signal is there so a dead spot
        // plays through instead of stalling. Time thresholds win over the default byte cap.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _isPlaying = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    private val _isPaused = MutableStateFlow(false)
    private val _isEnqueuing = MutableStateFlow(false)
    private val _position = MutableStateFlow(0.0)
    private val _duration = MutableStateFlow(0.0)
    private val _volume = MutableStateFlow(50)
    private val _currentTitle = MutableStateFlow("")
    private val _displayTitle = MutableStateFlow("")
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    private val _queueIndex = MutableStateFlow(-1)
    private val _queueTitle = MutableStateFlow<String?>(null)
    private val _shuffle = MutableStateFlow(false)
    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    private val _lastError = MutableStateFlow<String?>(null)

    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    override val isEnqueuing: StateFlow<Boolean> = _isEnqueuing.asStateFlow()
    override val position: StateFlow<Double> = _position.asStateFlow()
    override val duration: StateFlow<Double> = _duration.asStateFlow()
    override val volume: StateFlow<Int> = _volume.asStateFlow()
    override val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()
    override val displayTitle: StateFlow<String> = _displayTitle.asStateFlow()
    override val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()
    override val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()
    override val queueTitle: StateFlow<String?> = _queueTitle.asStateFlow()
    override val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    override val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()
    /** Short, human-readable reason the last track could not play; cleared on the next success. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var consecutiveLoadFailures = 0
    private val maxConsecutiveLoadFailures = 3

    /** Network retries spent on the current item; reset by any load that is not a retry. */
    private var sameTrackRetries = 0
    private var retryJob: Job? = null

    /**
     * Position to start from the next time the current item is loaded. Set on restore (the
     * stream is not resolved until the user presses play, so launch costs no network and
     * makes no sound) and cleared once consumed.
     */
    private var resumePositionSec: Double? = null
    private var lastPersistedPositionSec = 0.0
    /** True while the current item has been restored but not yet loaded into the player. */
    private val awaitingResume: Boolean get() = player.mediaItemCount == 0 && current() != null

    init {
        player.volume = _volume.value / 100f
        // Read the stored levels up front: the audio thread only ever looks them up in memory.
        LoudnessStore.warmUp()
        WrenPlaybackService.controls = this
        restore()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                _isPaused.value = !isPlaying && player.playbackState != Player.STATE_ENDED
                if (!isPlaying) persist()
                WrenPlaybackService.update(context)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isLoading.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) onTrackEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("ExoPlayerEngine", "Playback error for ${current()?.videoId}", error)
                _lastError.value = "playback failed: ${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase()}"
                if (isNetworkError(error) && retrySameTrack()) return
                consecutiveLoadFailures++
                if (consecutiveLoadFailures > maxConsecutiveLoadFailures) {
                    Log.e("ExoPlayerEngine", "Giving up after $consecutiveLoadFailures consecutive failures")
                    _isPlaying.value = false
                    return
                }
                advance(force = true)
            }
        })

        scope.launch {
            while (isActive) {
                // Nothing loaded yet after a restore: keep showing the saved position/duration.
                if (awaitingResume) { delay(500); continue }
                _position.value = player.currentPosition / 1000.0
                val total = player.duration
                if (total > 0) {
                    // Publish once the duration is known so the widget gains its seek bar.
                    if (_duration.value == 0.0) WrenPlaybackService.update(context)
                    _duration.value = total / 1000.0
                }
                // Checkpoint the position every few seconds so a killed process resumes close by.
                if (player.isPlaying && abs(_position.value - lastPersistedPositionSec) >= PERSIST_EVERY_SEC) persist()
                delay(500)
            }
        }
    }

    // ── Transport ────────────────────────────────────────────────────────────

    override fun start() {
        warmupStreamConnection()
    }

    override fun stop() {
        persistLoudness()
        player.stop()
        player.clearMediaItems()
        _isPlaying.value = false
        _isPaused.value = false
        _isLoading.value = false
        WrenPlaybackService.stop(context)
    }

    fun release() {
        persist()
        // Straight to disk: releasing the player resets the sink (dropping the meter), and the
        // scope is cancelled below, so a dispatched write would never land.
        loudnessProcessor.currentMeasurement()?.let { (videoId, measurement) ->
            LoudnessStore.record(videoId, measurement)
        }
        scope.cancel()
        player.release()
        WrenPlaybackService.controls = null
        WrenPlaybackService.stop(context)
    }

    override fun load(url: String, videoId: String, title: String) {
        _queue.value = listOf(QueueItem(url = url, videoId = videoId, title = title))
        _queueIndex.value = 0
        playIndex(0)
    }

    override fun loadQueue(items: List<QueueItem>, startIndex: Int, title: String?) {
        if (items.isEmpty()) return
        val start = startIndex.coerceIn(0, items.lastIndex)
        val selected = items[start]
        // The tapped track is absolute: shuffle only the tracks around it.
        val ordered = if (_shuffle.value && items.size > 1) {
            val rest = items.filterIndexed { index, _ -> index != start }.shuffled().toMutableList()
            rest.add(start, selected)
            rest
        } else {
            items
        }
        _queue.value = ordered
        _queueTitle.value = title?.takeIf { it.isNotBlank() }
        _queueIndex.value = start
        resumePositionSec = null
        playIndex(_queueIndex.value)
        WrenPlaybackService.start(context)
    }

    override fun jumpTo(index: Int) {
        if (index !in _queue.value.indices) return
        playIndex(index)
    }

    /** Drops everything after the current item; playback keeps going untouched. */
    override fun clearQueue() {
        _queueTitle.value = null
        val item = current()
        if (item == null) {
            _queue.value = emptyList()
            _queueIndex.value = -1
        } else {
            _queue.value = listOf(item)
            _queueIndex.value = 0
        }
        persist()
    }

    override fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
        persist()
        val current = current() ?: return
        val reordered = if (_shuffle.value) _queue.value.shuffled() else restoreOriginalOrder()
        _queue.value = reordered
        val newIndex = reordered.indexOfFirst { it.videoId == current.videoId }
        if (newIndex >= 0) {
            _queueIndex.value = newIndex
            prefetch(newIndex + 1)
        }
    }

    /** Unshuffling cannot recover insertion order once shuffled, so keep what is playing stable. */
    private fun restoreOriginalOrder(): List<QueueItem> {
        val current = current() ?: return _queue.value
        return listOf(current) + _queue.value.filterNot { it.videoId == current.videoId }
    }

    override fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.SINGLE
            RepeatMode.SINGLE -> RepeatMode.OFF
        }
        persist()
    }

    override fun next() {
        when (_repeatMode.value) {
            RepeatMode.SINGLE -> playIndex(_queueIndex.value)
            RepeatMode.ALL -> if (_queue.value.isNotEmpty()) playIndex((_queueIndex.value + 1) % _queue.value.size)
            RepeatMode.OFF -> advance(force = false)
        }
    }

    override fun previous() {
        val index = _queueIndex.value - 1
        if (index >= 0) playIndex(index)
    }

    override fun playPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            when {
                // Restored from disk: the stream is resolved now, from where the user left off.
                awaitingResume -> playIndex(_queueIndex.value, startAtSec = resumePositionSec ?: 0.0)
                player.playbackState == Player.STATE_ENDED ->
                    if (_queue.value.isNotEmpty()) playIndex(_queueIndex.value) else return
                else -> player.play()
            }
        }
        WrenPlaybackService.update(context)
    }

    override fun seek(seconds: Double) {
        if (awaitingResume) {
            // Nothing loaded yet: remember where to start instead of seeking an empty player.
            resumePositionSec = seconds.coerceAtLeast(0.0)
        } else {
            player.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
        }
        _position.value = seconds
        persist()
    }

    override fun setVolume(vol: Int) {
        val v = vol.coerceIn(0, 100)
        _volume.value = v
        player.volume = v / 100f
    }

    // ── Queue internals ──────────────────────────────────────────────────────

    private fun current(): QueueItem? = _queue.value.getOrNull(_queueIndex.value)

    private fun onTrackEnded() {
        when (_repeatMode.value) {
            RepeatMode.SINGLE -> {
                player.seekTo(0)
                player.play()
            }
            RepeatMode.ALL -> playIndex((_queueIndex.value + 1) % _queue.value.size)
            RepeatMode.OFF -> advance(force = false)
        }
    }

    private fun advance(force: Boolean) {
        val nextIndex = _queueIndex.value + 1
        if (nextIndex < _queue.value.size) {
            playIndex(nextIndex)
        } else if (force && _queue.value.isNotEmpty() && _repeatMode.value == RepeatMode.ALL) {
            playIndex(0)
        } else {
            _isPlaying.value = false
            player.stop()
        }
    }

    /**
     * A dropped connection is not a bad track. Instead of skipping, wait for the signal to come
     * back, then reload the same item where it stopped — with a fresh URL, since the old one may
     * be bound to the network the phone just left. Skipping only happens once the budget is spent.
     */
    private fun retrySameTrack(): Boolean {
        val item = current() ?: return false
        if (sameTrackRetries >= RETRY_DELAYS_MS.size) return false
        val attempt = sameTrackRetries++
        val resumeAt = maxOf(_position.value, player.currentPosition.coerceAtLeast(0) / 1000.0)
        Log.w("ExoPlayerEngine", "network error on ${item.videoId}; retry ${attempt + 1}/${RETRY_DELAYS_MS.size} in ${RETRY_DELAYS_MS[attempt]} ms from ${"%.1f".format(resumeAt)} s")
        _isLoading.value = true
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RETRY_DELAYS_MS[attempt])
            if (current()?.videoId != item.videoId) return@launch
            forgetStreamUrl(item.videoId)
            playIndex(_queueIndex.value, startAtSec = resumeAt, retry = true)
        }
        return true
    }

    /** SoundCloud's HLS playlists carry no .m3u8 in the path, so the type is stated, not sniffed. */
    private fun mediaItemFor(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (".m3u8" in url || "/playlist" in url) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private fun isNetworkError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        // An expired or IP-bound stream URL comes back as a 403, and a re-resolve is the cure.
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> true
        else -> false
    }

    private fun playIndex(index: Int, startAtSec: Double = 0.0, retry: Boolean = false) {
        val item = _queue.value.getOrNull(index) ?: return
        if (!retry) {
            // A new load, by the user or the queue, starts the network budget over.
            retryJob?.cancel()
            sameTrackRetries = 0
        }
        _queueIndex.value = index
        // Files the level of whatever was playing under its own id before the next track starts.
        loudnessProcessor.beginTrack(item.videoId)
        _currentTitle.value = item.videoId
        _displayTitle.value = item.title
        WrenPlaybackService.update(context)
        _isEnqueuing.value = true
        _isLoading.value = true
        _position.value = startAtSec
        _duration.value = 0.0
        resumePositionSec = null
        persist()

        scope.launch {
            val url = withContext(Dispatchers.IO) { resolveStreamUrl(item.videoId) }
            if (url == null) {
                // item.url is a watch page, not a stream — never hand it to the player.
                Log.e("ExoPlayerEngine", "No stream URL for ${item.videoId}; skipping")
                _lastError.value = "no stream for ${item.title.ifBlank { item.videoId }}"
                _isLoading.value = false
                _isEnqueuing.value = false
                advance(force = true)
                return@launch
            }
            _isEnqueuing.value = false
            player.setMediaItem(mediaItemFor(url), (startAtSec * 1000).toLong().coerceAtLeast(0))
            player.prepare()
            player.play()
            consecutiveLoadFailures = 0
            _lastError.value = null
            ListeningHistory.record(item)
            WrenPlaybackService.start(context)
            prefetch(index + 1)
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    /** Puts the last session's queue back, paused, ready to resume from the saved position. */
    private fun restore() {
        val saved = PlaybackStateStore.load() ?: return
        _queue.value = saved.queue
        _queueIndex.value = saved.index
        _queueTitle.value = saved.queueTitle
        _shuffle.value = saved.shuffle
        _repeatMode.value = saved.repeatMode
        resumePositionSec = saved.positionSec
        _position.value = saved.positionSec
        _duration.value = saved.durationSec
        val item = saved.queue[saved.index]
        _currentTitle.value = item.videoId
        _displayTitle.value = item.title
        _isPaused.value = true
        lastPersistedPositionSec = saved.positionSec
    }

    private fun persist() {
        val queue = _queue.value
        val index = _queueIndex.value
        if (queue.isEmpty() || index !in queue.indices) {
            PlaybackStateStore.clear()
            return
        }
        val position = if (awaitingResume) resumePositionSec ?: _position.value else _position.value
        lastPersistedPositionSec = position
        PlaybackStateStore.save(
            SavedPlayback(
                queue = queue,
                index = index,
                positionSec = position,
                durationSec = _duration.value,
                shuffle = _shuffle.value,
                repeatMode = _repeatMode.value,
                queueTitle = _queueTitle.value,
            ),
        )
    }

    private fun prefetch(index: Int) {
        val item = _queue.value.getOrNull(index) ?: return
        scope.launch { withContext(Dispatchers.IO) { resolveStreamUrl(item.videoId) } }
    }

    /** Keeps however much of the current track has been measured so far, for the next play. */
    private fun persistLoudness() {
        val (videoId, measurement) = loudnessProcessor.currentMeasurement() ?: return
        scope.launch { withContext(Dispatchers.IO) { LoudnessStore.record(videoId, measurement) } }
    }

    // ── Notification contract ────────────────────────────────────────────────

    override val notificationTitle: String get() = _displayTitle.value.ifBlank { _currentTitle.value }
    override val notificationSubtitle: String get() = current()?.artist.orEmpty()
    override val notificationPlaying: Boolean get() = _isPlaying.value
    override val notificationPosition: Double get() = _position.value
    override val notificationDuration: Double get() = _duration.value
    override val notificationArtwork: String? get() = artworkFor(current())

    override fun onToggle() = playPause()
    override fun onNext() = next()
    override fun onPrevious() = previous()

    override fun onSeek(seconds: Double) {
        seek(seconds)
        WrenPlaybackService.update(context)
    }

    override fun onLike() {
        val item = current() ?: return
        scope.launch {
            // Each platform's own notion of "keep this": a SoundCloud like, or a YouTube like, which
            // is what puts a video in the library's collection.
            when (item.source) {
                Source.SOUNDCLOUD -> SoundCloudLikes.toggle(item.url)
                else -> YouTubeLikes.toggle(item.videoId)
            }
        }
    }
}

/** Per-chunk retries inside the player before it reports an error. */
private const val LOAD_RETRIES = 8

/** How long to wait for the signal before each reload of the same track. */
private val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 8_000, 15_000)

private const val MIN_BUFFER_MS = 90_000
private const val MAX_BUFFER_MS = 300_000
private const val PLAYBACK_BUFFER_MS = 2_500
private const val REBUFFER_MS = 5_000
