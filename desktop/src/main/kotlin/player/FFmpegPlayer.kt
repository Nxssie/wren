package player

import api.resolveStreamUrl
import api.warmupStreamConnection
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import models.QueueItem
import models.RepeatMode
import util.Log

/**
 * Lightweight in-process audio player backed by FFmpeg via JavaCV.
 *
 * Replaces the mpv subprocess + Unix socket IPC with a single JAR dependency.
 * Decodes audio streams (Opus/MP4 from YouTube) to PCM and plays through
 * the system's Java Sound API mixer.
 *
 * Public API mirrors MpvPlayer so all UI composables are drop-in compatible.
 * All state properties are MutableState for Compose reactivity.
 */
class FFmpegPlayer {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Reactive state (mirrors MpvPlayer exactly) ──
    val isPlaying = mutableStateOf(false)
    val isLoading = mutableStateOf(false)
    val position = mutableStateOf(0.0)
    val duration = mutableStateOf(0.0)
    val volume = mutableStateOf(50)
    val currentTitle = mutableStateOf("")
    val displayTitle = mutableStateOf("")
    val queue = mutableStateOf<List<QueueItem>>(emptyList())
    val queueIndex = mutableStateOf(-1)
    val shuffle = mutableStateOf(false)
    val repeatMode = mutableStateOf(RepeatMode.OFF)
    val isPaused = mutableStateOf(false)
    val isEnqueuing = mutableStateOf(false)

    // ── FFmpeg internals ──
    private var grabber: FFmpegFrameGrabber? = null
    private val grabberLock = Any()
    private val seekLock = Any()

    // Pre-allocated buffers to eliminate per-frame allocations (GC pressure causes stutter)
    private val shortBuffer = ShortArray(65536)
    private val byteBuffer = ByteArray(131072)

    // Audio playback
    private var audioLine: SourceDataLine? = null
    private val audioLock = Any()

    // Playback state
    private var playbackJob: Job? = null
    private val isPlayingInternal = AtomicBoolean(false)
    private var eofReached = AtomicBoolean(false)
    private val _isPaused = AtomicBoolean(false)

    // Consecutive stream-open failures (e.g. googlevideo rejecting the connection).
    // Bounds the auto-skip-on-failure loop so an unplayable queue doesn't spin forever.
    private var consecutiveLoadFailures = 0
    private val maxConsecutiveLoadFailures = 3

    // Gapless preload — the next track's grabber is opened and connected ahead of
    // time so the transition on EOF doesn't pay network-connect + probe latency,
    // which is what caused the audible cut before jumping to the next song.
    private data class PreloadedTrack(val videoId: String, val grabber: FFmpegFrameGrabber)
    private var preloadedNext: PreloadedTrack? = null
    private val preloadLock = Any()

    fun start() {
        warmupStreamConnection()
    }

    fun stop() {
        scope.cancel()
        runBlocking { stopPlayback() }
        synchronized(audioLock) {
            audioLine?.stop()
            audioLine?.close()
            audioLine = null
        }
        discardPreload()
        isPlayingInternal.set(false)
    }

    fun load(url: String, videoId: String, title: String = "") {
        queue.value = emptyList()
        queueIndex.value = -1
        scope.launch { loadInternal(url, videoId, title) }
    }

    fun loadQueue(items: List<QueueItem>, startIndex: Int = 0) {
        val shuffled = if (shuffle.value && items.size > 1) {
            items.shuffled()
        } else {
            items
        }
        queue.value = shuffled
        queueIndex.value = startIndex
        val item = shuffled[startIndex]
        isEnqueuing.value = true
        // Pre-resolve stream URL in background so playback starts instantly
        scope.launch {
            val resolvedUrl = resolveStreamUrl(item.videoId) ?: item.url
            isEnqueuing.value = false
            loadInternal(resolvedUrl, item.videoId, item.title)
        }
        prefetchAt(shuffled, startIndex + 1, count = 4)
    }

    fun toggleShuffle() {
        shuffle.value = !shuffle.value
        val q = queue.value
        if (q.isNotEmpty()) {
            val currentIdx = queueIndex.value
            val currentItem = q[currentIdx]
            val shuffled = if (shuffle.value) {
                q.shuffled()
            } else {
                q
            }
            queue.value = shuffled
            val newIdx = shuffled.indexOfFirst { it.videoId == currentItem.videoId }
            if (newIdx >= 0) {
                queueIndex.value = newIdx
                prefetchAt(shuffled, newIdx + 1, count = 4)
                schedulePreloadNext()
            }
        }
    }

    fun toggleRepeat() {
        repeatMode.value = when (repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.SINGLE
            RepeatMode.SINGLE -> RepeatMode.OFF
        }
        schedulePreloadNext()
    }

    fun next() {
        val q = queue.value
        when (repeatMode.value) {
            RepeatMode.SINGLE -> {
                if (q.isNotEmpty()) {
                    val item = q[queueIndex.value]
                    scope.launch { loadInternal(item.url, item.videoId, item.title) }
                }
                return
            }
            RepeatMode.ALL -> {
                if (q.size <= 1) {
                    if (q.isNotEmpty()) {
                        val item = q[queueIndex.value]
                        scope.launch { loadInternal(item.url, item.videoId, item.title) }
                    }
                    return
                }
                val idx = (queueIndex.value + 1) % q.size
                queueIndex.value = idx
                val item = q[idx]
                scope.launch { loadInternal(item.url, item.videoId, item.title) }
                prefetchAt(q, (idx + 1) % q.size)
                return
            }
            RepeatMode.OFF -> {}
        }
        val idx = queueIndex.value + 1
        if (idx < q.size) {
            queueIndex.value = idx
            val item = q[idx]
            scope.launch { loadInternal(item.url, item.videoId, item.title) }
            prefetchAt(q, idx + 1)
        }
    }

    fun previous() {
        val q = queue.value
        val idx = queueIndex.value - 1
        if (idx >= 0) {
            queueIndex.value = idx
            val item = q[idx]
            scope.launch { loadInternal(item.url, item.videoId, item.title) }
            prefetchAt(q, idx - 1)
        }
    }

    private fun prefetchAt(items: List<QueueItem>, index: Int, count: Int = 1) {
        val end = (index + count).coerceAtMost(items.size)
        items.subList(index.coerceAtLeast(0), end)
            .forEach { item ->
                scope.launch { resolveStreamUrl(item.videoId) }
            }
    }

    private suspend fun loadInternal(url: String, videoId: String, title: String = "") {
        Log.i("FFmpegPlayer", "Loading videoId=$videoId title=\"$title\"")
        isEnqueuing.value = false
        currentTitle.value = videoId
        displayTitle.value = title
        position.value = 0.0
        duration.value = 0.0
        isLoading.value = true

        // Stop any existing playback (waits for old playLoop to fully exit)
        eofReached.set(false)
        stopPlayback()

        // Reuse the preloaded grabber if it's for this exact track (gapless transition).
        // Any other pending preload is now stale (user skipped/jumped elsewhere) — release it.
        val preloaded = synchronized(preloadLock) {
            val p = preloadedNext
            preloadedNext = null
            if (p?.videoId == videoId) p else {
                p?.let { runCatching { it.grabber.stop() } }
                null
            }
        }

        scope.launch {
            try {
                // Create and configure grabber (or reuse the preloaded, already-connected one)
                val newGrabber = if (preloaded != null) {
                    Log.i("FFmpegPlayer", "Using preloaded grabber for videoId=$videoId (gapless)")
                    preloaded.grabber
                } else {
                    val streamUrl = resolveStreamUrl(videoId) ?: url
                    openGrabber(streamUrl)
                }

                synchronized(grabberLock) {
                    grabber = newGrabber
                }

                // Determine duration
                var durSeconds: Double? = null
                val lengthInTime = newGrabber.lengthInTime
                if (lengthInTime > 0) {
                    durSeconds = lengthInTime / 1_000_000.0
                }
                duration.value = durSeconds ?: 0.0

                isLoading.value = false

                // Open audio mixer
                val line = openMixer(newGrabber)
                synchronized(audioLock) {
                    audioLine = line
                }

                isPlayingInternal.set(true)
                isPlaying.value = true

                // Start position updater
                startPositionUpdater(newGrabber)

                // Start playback loop
                playbackJob = scope.launch {
                    playLoop(newGrabber, line)
                }

                consecutiveLoadFailures = 0
                schedulePreloadNext()

            } catch (e: Exception) {
                Log.e("FFmpegPlayer", "Failed to load stream for videoId=$videoId", e)
                isLoading.value = false
                isEnqueuing.value = false
                isPlaying.value = false
                isPlayingInternal.set(false)

                consecutiveLoadFailures++
                if (consecutiveLoadFailures > maxConsecutiveLoadFailures) {
                    Log.e("FFmpegPlayer", "Giving up after $consecutiveLoadFailures consecutive failures")
                } else {
                    advanceToNext()
                }
            }
        }
    }

    /** Configures and connects a grabber against a resolved stream URL. */
    private fun openGrabber(streamUrl: String): FFmpegFrameGrabber =
        FFmpegFrameGrabber(streamUrl).also { g ->
            g.setAudioChannels(2)
            g.setOption("sample_fmt", "s16")
            g.setOption("re", "1")
            g.setOption("timeout", "30000000")
            // NOTE: do NOT set a custom user_agent here — the resolved googlevideo URL is
            // signed for the client yt-dlp used to fetch it (e.g. c=ANDROID_VR in the query
            // string). Sending a mismatched desktop-browser UA gets every request rejected.
            // reconnect only affects behavior on an actual mid-stream drop, so it's safe.
            g.setOption("reconnect", "1")
            g.setOption("reconnect_streamed", "1")
            g.setOption("reconnect_delay_max", "5")
            g.start()
        }

    /** Advances the queue to the next track, respecting repeat mode. Used on EOF and on load failure. */
    private fun advanceToNext() {
        val q = queue.value
        when (repeatMode.value) {
            RepeatMode.SINGLE -> {
                if (q.isNotEmpty()) {
                    val item = q[queueIndex.value]
                    scope.launch { loadInternal(item.url, item.videoId, item.title) }
                }
            }
            else -> {
                val nextIdx = queueIndex.value + 1
                if (nextIdx < q.size) {
                    queueIndex.value = nextIdx
                    val item = q[nextIdx]
                    scope.launch { loadInternal(item.url, item.videoId, item.title) }
                } else if (repeatMode.value == RepeatMode.ALL && q.isNotEmpty()) {
                    queueIndex.value = 0
                    val item = q[0]
                    scope.launch { loadInternal(item.url, item.videoId, item.title) }
                }
            }
        }
    }

    /**
     * Opens and connects the FFmpegFrameGrabber for the upcoming track ahead of time,
     * so the transition on EOF (or manual next()) can reuse it instantly instead of
     * paying network-connect + stream-probe latency during the gap.
     */
    private fun schedulePreloadNext() {
        discardPreload()

        val q = queue.value
        if (q.isEmpty()) return
        val idx = queueIndex.value
        val nextIdx = when {
            repeatMode.value == RepeatMode.SINGLE -> return // same track repeats — nothing new to preload
            idx + 1 < q.size -> idx + 1
            repeatMode.value == RepeatMode.ALL -> 0
            else -> return
        }
        val item = q[nextIdx]

        scope.launch {
            runCatching {
                val streamUrl = resolveStreamUrl(item.videoId) ?: item.url
                val g = openGrabber(streamUrl)

                synchronized(preloadLock) {
                    // Only keep it if the queue hasn't moved on underneath us while we connected
                    if (queue.value === q && queueIndex.value == idx) {
                        preloadedNext = PreloadedTrack(item.videoId, g)
                        Log.i("FFmpegPlayer", "Preloaded next track videoId=${item.videoId}")
                    } else {
                        runCatching { g.stop() }
                    }
                }
            }.onFailure { Log.w("FFmpegPlayer", "Failed to preload next track videoId=${item.videoId}", it) }
        }
    }

    private fun discardPreload() {
        synchronized(preloadLock) {
            preloadedNext?.let { p -> runCatching { p.grabber.stop() } }
            preloadedNext = null
        }
    }

    private fun startPositionUpdater(grabber: FFmpegFrameGrabber) {
        scope.launch {
            while (coroutineContext.isActive && isPlayingInternal.get()) {
                // Don't update position while paused — keep bar frozen at pause point
                if (!_isPaused.get()) {
                    try {
                        val posUs = grabber.timestamp
                        if (posUs > 0) {
                            position.value = posUs / 1_000_000.0
                        }
                    } catch (_: Exception) {
                        // grabber might be closed or invalid
                    }
                }
                delay(250)
            }
        }
    }

    private suspend fun playLoop(grabber: FFmpegFrameGrabber, line: SourceDataLine) {
        eofReached.set(false)
        _isPaused.set(false)
        isPaused.value = false

        // Timeout for detecting EOF vs network buffering (2 seconds of no frames)
        val eofTimeoutMs = 2000L
        var lastFrameTime = System.currentTimeMillis()
        var hasReceivedFrame = false

        // Auto-level loudness across tracks: nudge a smoothed gain so each track's short-term
        // RMS approaches a common target, instead of every track playing at its native level.
        var agcGain = 1.0f
        val targetRms = 6000f
        val agcSmoothing = 0.02f
        val agcGainRange = 0.5f..2.0f

        // Fade the first/last few seconds of a track in/out. The gapless preload already
        // removes the silent gap between tracks, so this is what actually smooths the
        // transition — a true overlapping crossfade would need to decode and mix two
        // streams at once, which is a much bigger change than this.
        val fadeSeconds = 2.5

        while (this@FFmpegPlayer.scope.coroutineContext.isActive && isPlayingInternal.get()) {
            // Wait until unpaused before grabbing — keeps grabber timestamp in sync with playback
            if (_isPaused.get()) {
                delay(16)
                continue
            }

            try {
                val frame = synchronized(seekLock) { grabber.grabSamples() }

                if (frame == null) {
                    // No frame available — could be buffering or EOF
                    // Only start timeout after we've successfully received at least one frame
                    if (hasReceivedFrame && System.currentTimeMillis() - lastFrameTime > eofTimeoutMs) {
                        eofReached.set(true)
                        break
                    }
                    // Still within timeout or haven't received first frame yet
                    delay(50)
                    continue
                }

                // Got a frame — reset timeout and track that we've received data
                lastFrameTime = System.currentTimeMillis()
                hasReceivedFrame = true

                if (frame.samples != null && frame.samples[0] != null) {
                    val sampleBuffer = frame.samples[0] as java.nio.Buffer
                    val remaining = sampleBuffer.remaining()
                    if (remaining > 0) {
                        val (bytes, len) = bufferToBytes(sampleBuffer)
                        if (len > 0) {
                            // Estimate this chunk's loudness and slowly steer agcGain toward
                            // whatever gain would bring it to targetRms (clamped so near-silent
                            // passages don't get amplified into audible noise).
                            var sumSq = 0.0
                            var i = 0
                            while (i < len) {
                                val s = ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)).toShort()
                                sumSq += s.toDouble() * s.toDouble()
                                i += 2
                            }
                            val sampleCount = len / 2
                            if (sampleCount > 0) {
                                val rms = kotlin.math.sqrt(sumSq / sampleCount).toFloat().coerceAtLeast(1f)
                                val desiredGain = (targetRms / rms).coerceIn(agcGainRange)
                                agcGain += (desiredGain - agcGain) * agcSmoothing
                            }

                            val dur = duration.value
                            val pos = position.value
                            val fadeIn = if (pos < fadeSeconds) (pos / fadeSeconds).toFloat().coerceIn(0f, 1f) else 1f
                            val fadeOut = if (dur > 0 && dur - pos < fadeSeconds) ((dur - pos) / fadeSeconds).toFloat().coerceIn(0f, 1f) else 1f

                            val gain = digitalVolume * agcGain * fadeIn * fadeOut
                            if (gain != 1.0f) {
                                for (b in 0 until len step 2) {
                                    val sample = ((bytes[b].toInt() and 0xFF) or ((bytes[b + 1].toInt() and 0xFF) shl 8)).toShort()
                                    val scaled = (sample.toFloat() * gain).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                                    bytes[b] = scaled.toInt().toByte()
                                    bytes[b + 1] = (scaled.toInt() ushr 8).toByte()
                                }
                            }
                            line.write(bytes, 0, len)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isPlayingInternal.get()) {
                    Log.w("FFmpegPlayer", "grabSamples/write failed, retrying: ${e.message}")
                    delay(10)
                }
            }
        }

        // Drain remaining audio
        line.drain()

        isPlayingInternal.set(false)
        isPlaying.value = false

        // Auto-advance on EOF (only if not manually stopped)
        if (eofReached.get()) {
            Log.i(
                "FFmpegPlayer",
                "EOF for videoId=${currentTitle.value} at position=${position.value}s / duration=${duration.value}s"
            )
            advanceToNext()
        }
    }

    /** Convert any NIO Buffer to bytes using pre-allocated buffers (zero allocation) */
    private fun bufferToBytes(buf: java.nio.Buffer): Pair<ByteArray, Int> {
        return when (buf) {
            is ShortBuffer -> {
                val count = buf.remaining().coerceAtMost(shortBuffer.size)
                buf.get(shortBuffer, 0, count)
                val len = shortsToBytes(shortBuffer, 0, count, byteBuffer, 0)
                byteBuffer to len
            }
            is ByteBuffer -> {
                val count = buf.remaining().coerceAtMost(byteBuffer.size)
                buf.get(byteBuffer, 0, count)
                byteBuffer to count
            }
            is java.nio.FloatBuffer -> {
                val count = buf.remaining().coerceAtMost(shortBuffer.size)
                for (i in 0 until count) {
                    shortBuffer[i] = (buf.get(i) * 32767f).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                val len = shortsToBytes(shortBuffer, 0, count, byteBuffer, 0)
                byteBuffer to len
            }
            is java.nio.IntBuffer -> {
                val count = buf.remaining().coerceAtMost(shortBuffer.size)
                for (i in 0 until count) {
                    shortBuffer[i] = (buf.get(i) shr 16).toShort()
                }
                val len = shortsToBytes(shortBuffer, 0, count, byteBuffer, 0)
                byteBuffer to len
            }
            else -> byteBuffer to 0
        }
    }

    private fun shortsToBytes(shorts: ShortArray, sOff: Int, count: Int,
                             bytes: ByteArray, bOff: Int): Int {
        for (i in 0 until count) {
            bytes[bOff + i * 2] = (shorts[sOff + i].toInt() and 0xFF).toByte()
            bytes[bOff + i * 2 + 1] = ((shorts[sOff + i].toInt() shr 8) and 0xFF).toByte()
        }
        return count * 2
    }

    private fun openMixer(grabber: FFmpegFrameGrabber): SourceDataLine {
        val sampleRate = grabber.sampleRate.toFloat()
        val channels = grabber.audioChannels
        val bytesPerSample = 2 // S16 = 16-bit

        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate,
            16,
            channels,
            channels * bytesPerSample,
            sampleRate,
            false, // little-endian (native for S16 from FFmpeg)
            emptyMap() // Java 25+ requires non-null properties Map
        )

        // Try to get a line with this format
        val line = try {
            @Suppress("DEPRECATION")
            AudioSystem.getSourceDataLine(format) as SourceDataLine
        } catch (e: Exception) {
            Log.w("FFmpegPlayer", "getSourceDataLine failed for native format (rate=$sampleRate, channels=$channels), falling back to 44100/16/2", e)
            // Final fallback: try with a generic format
            val fallback = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44100f, 16, 2, 4, 44100f, false, emptyMap()
            )
            @Suppress("DEPRECATION")
            AudioSystem.getSourceDataLine(fallback) as SourceDataLine
        }

        val bufferSize = maxOf(32768, line.bufferSize)
        line.open(format, bufferSize)
        line.start()
        return line
    }

    private suspend fun stopPlayback() {
        isPlayingInternal.set(false)
        _isPaused.set(false)
        isPaused.value = false
        playbackJob?.cancelAndJoin()
        synchronized(grabberLock) {
            runCatching { grabber?.stop() }
            grabber = null
        }
        synchronized(audioLock) {
            audioLine?.flush()
            audioLine?.drain()
        }
    }

    fun playPause() {
        synchronized(audioLock) {
            val line = audioLine ?: return
            if (_isPaused.get()) {
                // Resume: restart audio line, keep playLoop running
                line.start()
                _isPaused.set(false)
                isPaused.value = false
            } else {
                // Pause: stop audio line, keep playLoop running
                line.stop()
                _isPaused.set(true)
                isPaused.value = true
            }
        }
    }

    fun seek(seconds: Double) {
        // Pause audio line to avoid playing stale data
        synchronized(audioLock) {
            audioLine?.stop()
            audioLine?.flush()
        }

        // Seek on grabber — playLoop will retry grabSamples after this
        synchronized(seekLock) {
            runCatching {
                val timestamp = (seconds * 1_000_000).toLong()
                grabber?.timestamp = timestamp
                position.value = seconds
            }
        }

        // Restart audio line — playLoop continues from new position
        synchronized(audioLock) {
            audioLine?.start()
        }
    }

    fun setVolume(vol: Int) {
        volume.value = vol
        digitalVolume = vol / 100f
    }

    @Volatile
    private var digitalVolume: Float = 1.0f
}
