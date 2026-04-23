package player

import api.resolveStreamUrl
import api.warmupStreamConnection
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine

data class QueueItem(val url: String, val videoId: String, val title: String, val artist: String = "")

enum class RepeatMode { OFF, ALL, SINGLE }

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

    // ── FFmpeg internals ──
    private var grabber: FFmpegFrameGrabber? = null
    private val grabberLock = Any()
    private val seekLock = Any()

    // Audio playback
    private var audioLine: SourceDataLine? = null
    private val audioLock = Any()

    // Playback state
    private var playbackJob: Job? = null
    private val isPlayingInternal = AtomicBoolean(false)
    private var eofReached = AtomicBoolean(false)
    private val _isPaused = AtomicBoolean(false)

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
        isPlayingInternal.set(false)
    }

    fun load(url: String, videoId: String, title: String = "") {
        queue.value = emptyList()
        queueIndex.value = -1
        loadInternal(url, videoId, title)
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
        // Pre-resolve stream URL in background so playback starts instantly
        scope.launch {
            val resolvedUrl = resolveStreamUrl(item.videoId) ?: item.url
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
            }
        }
    }

    fun toggleRepeat() {
        repeatMode.value = when (repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.SINGLE
            RepeatMode.SINGLE -> RepeatMode.OFF
        }
    }

    fun next() {
        val q = queue.value
        when (repeatMode.value) {
            RepeatMode.SINGLE -> {
                if (q.isNotEmpty()) {
                    val item = q[queueIndex.value]
                    loadInternal(item.url, item.videoId, item.title)
                }
                return
            }
            RepeatMode.ALL -> {
                if (q.size <= 1) {
                    if (q.isNotEmpty()) {
                        val item = q[queueIndex.value]
                        loadInternal(item.url, item.videoId, item.title)
                    }
                    return
                }
                val idx = (queueIndex.value + 1) % q.size
                queueIndex.value = idx
                val item = q[idx]
                loadInternal(item.url, item.videoId, item.title)
                prefetchAt(q, (idx + 1) % q.size)
                return
            }
            RepeatMode.OFF -> {}
        }
        val idx = queueIndex.value + 1
        if (idx < q.size) {
            queueIndex.value = idx
            val item = q[idx]
            loadInternal(item.url, item.videoId, item.title)
            prefetchAt(q, idx + 1)
        }
    }

    fun previous() {
        val q = queue.value
        val idx = queueIndex.value - 1
        if (idx >= 0) {
            queueIndex.value = idx
            val item = q[idx]
            loadInternal(item.url, item.videoId, item.title)
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

    private fun loadInternal(url: String, videoId: String, title: String = "") {
        currentTitle.value = videoId
        displayTitle.value = title
        position.value = 0.0
        duration.value = 0.0
        isLoading.value = true

        // Stop any existing playback (waits for old playLoop to fully exit)
        eofReached.set(false)
        runBlocking { stopPlayback() }

        val streamUrl = runBlocking {
            resolveStreamUrl(videoId) ?: url
        }

        scope.launch {
            try {
                // Create and configure grabber
                val newGrabber = FFmpegFrameGrabber(streamUrl)
                newGrabber.setAudioChannels(2)
                newGrabber.setPixelFormat(avutil.AV_SAMPLE_FMT_S16)
                newGrabber.setOption("re", "1")
                newGrabber.setOption("timeout", "30000000")

                synchronized(grabberLock) {
                    grabber = newGrabber
                }

                newGrabber.start()

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

            } catch (e: Exception) {
                isLoading.value = false
                isPlaying.value = false
                isPlayingInternal.set(false)
            }
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
        while (this@FFmpegPlayer.scope.coroutineContext.isActive && isPlayingInternal.get()) {
            // Wait until unpaused before grabbing — keeps grabber timestamp in sync with playback
            if (_isPaused.get()) {
                delay(16)
                continue
            }

            try {
                // Hold seekLock while grabbing to prevent concurrent seek() calls
                val frame = synchronized(seekLock) { grabber.grabSamples() }
                if (frame == null) {
                    eofReached.set(true)
                    break // EOF
                }

                if (frame.samples != null && frame.samples[0] != null) {
                    val sampleBuffer = frame.samples[0] as java.nio.Buffer
                    val remaining = sampleBuffer.remaining()
                    if (remaining > 0) {
                        // Convert any Buffer type to byte[] for SourceDataLine.write()
                        val bytes = bufferToBytes(sampleBuffer)
                        if (bytes.isNotEmpty()) {
                            line.write(bytes, 0, bytes.size)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isPlayingInternal.get()) {
                    delay(100)
                }
            }
        }

        // Drain remaining audio
        line.drain()

        isPlayingInternal.set(false)
        isPlaying.value = false

        // Auto-advance on EOF (only if not manually stopped)
        if (eofReached.get()) {
            scope.launch {
                when (repeatMode.value) {
                    RepeatMode.SINGLE -> {
                        if (queue.value.isNotEmpty()) {
                            val item = queue.value[queueIndex.value]
                            loadInternal(item.url, item.videoId, item.title)
                        }
                    }
                    else -> next()
                }
            }
        }
    }

    /** Convert any NIO Buffer to a byte array for SourceDataLine.write() */
    private fun bufferToBytes(buf: java.nio.Buffer): ByteArray {
        return when (buf) {
            is ShortBuffer -> {
                val shorts = ShortArray(buf.remaining())
                buf.get(shorts)
                shortsToBytes(shorts)
            }
            is ByteBuffer -> {
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                bytes
            }
            is java.nio.FloatBuffer -> {
                val shorts = ShortArray(buf.remaining())
                for (i in 0 until shorts.size) {
                    shorts[i] = (buf.get(i) * 32767f).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                shortsToBytes(shorts)
            }
            is java.nio.IntBuffer -> {
                val shorts = ShortArray(buf.remaining())
                for (i in 0 until shorts.size) {
                    shorts[i] = (buf.get(i) shr 16).toShort()
                }
                shortsToBytes(shorts)
            }
            else -> ByteArray(0)
        }
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((shorts[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
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
        // Apply via FloatControl if available
        synchronized(audioLock) {
            val line = audioLine ?: return
            try {
                val gainControl = line.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
                if (gainControl != null) {
                    // Map 0-100 to -60dB..0dB
                    val gainDb = -60f + (vol / 100f) * 60f
                    gainControl.value = gainDb
                }
            } catch (_: Exception) {
                // Some mixers don't support volume control
            }
        }
    }
}
