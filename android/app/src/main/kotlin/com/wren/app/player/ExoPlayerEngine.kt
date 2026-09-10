package com.wren.app.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import api.ListeningHistory
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
import models.QueueItem
import models.RepeatMode
import player.PlayerEngine
import util.Log

/**
 * Media3/ExoPlayer implementation of [PlayerEngine]. It drives a single media item and
 * advances the queue itself (resolving each URL lazily through [resolveStreamUrl]), the
 * same shape the desktop FFmpegPlayer uses, so queues with unresolved URLs work.
 */
class ExoPlayerEngine(private val context: Context) : PlayerEngine, PlaybackControls {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val player: ExoPlayer = ExoPlayer.Builder(context)
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
    private val _shuffle = MutableStateFlow(false)
    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)

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
    override val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    override val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private var consecutiveLoadFailures = 0
    private val maxConsecutiveLoadFailures = 3

    init {
        player.volume = _volume.value / 100f
        WrenPlaybackService.controls = this
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                _isPaused.value = !isPlaying && player.playbackState != Player.STATE_ENDED
                WrenPlaybackService.update(context)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isLoading.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) onTrackEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("ExoPlayerEngine", "Playback error for ${current()?.videoId}", error)
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
                _position.value = player.currentPosition / 1000.0
                val total = player.duration
                if (total > 0) {
                    // Publish once the duration is known so the widget gains its seek bar.
                    if (_duration.value == 0.0) WrenPlaybackService.update(context)
                    _duration.value = total / 1000.0
                }
                delay(500)
            }
        }
    }

    // ── Transport ────────────────────────────────────────────────────────────

    override fun start() {
        warmupStreamConnection()
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        _isPlaying.value = false
        _isPaused.value = false
        _isLoading.value = false
        WrenPlaybackService.stop(context)
    }

    fun release() {
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

    override fun loadQueue(items: List<QueueItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val ordered = if (_shuffle.value && items.size > 1) items.shuffled() else items
        _queue.value = ordered
        _queueIndex.value = startIndex.coerceIn(0, ordered.lastIndex)
        playIndex(_queueIndex.value)
        WrenPlaybackService.start(context)
    }

    override fun jumpTo(index: Int) {
        if (index !in _queue.value.indices) return
        playIndex(index)
    }

    /** Drops everything after the current item; playback keeps going untouched. */
    override fun clearQueue() {
        val item = current()
        if (item == null) {
            _queue.value = emptyList()
            _queueIndex.value = -1
        } else {
            _queue.value = listOf(item)
            _queueIndex.value = 0
        }
    }

    override fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
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
            if (player.playbackState == Player.STATE_ENDED) {
                if (_queue.value.isNotEmpty()) playIndex(_queueIndex.value) else return
            } else {
                player.play()
            }
        }
        WrenPlaybackService.update(context)
    }

    override fun seek(seconds: Double) {
        player.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
        _position.value = seconds
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

    private fun playIndex(index: Int) {
        val item = _queue.value.getOrNull(index) ?: return
        _queueIndex.value = index
        _currentTitle.value = item.videoId
        _displayTitle.value = item.title
        WrenPlaybackService.update(context)
        _isEnqueuing.value = true
        _isLoading.value = true
        _position.value = 0.0
        _duration.value = 0.0

        scope.launch {
            val url = withContext(Dispatchers.IO) { resolveStreamUrl(item.videoId) }
            if (url == null) {
                // item.url is a watch page, not a stream — never hand it to the player.
                Log.e("ExoPlayerEngine", "No stream URL for ${item.videoId}; skipping")
                _isLoading.value = false
                _isEnqueuing.value = false
                advance(force = true)
                return@launch
            }
            _isEnqueuing.value = false
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
            consecutiveLoadFailures = 0
            ListeningHistory.record(item)
            WrenPlaybackService.start(context)
            prefetch(index + 1)
        }
    }

    private fun prefetch(index: Int) {
        val item = _queue.value.getOrNull(index) ?: return
        scope.launch { withContext(Dispatchers.IO) { resolveStreamUrl(item.videoId) } }
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
}
