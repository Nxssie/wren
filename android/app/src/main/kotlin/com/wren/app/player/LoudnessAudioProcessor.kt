package com.wren.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlin.math.abs
import player.LoudnessMeter
import player.LoudnessMeasurement
import player.LoudnessStore
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Level normalises the decoded PCM against the level stored for each track, and measures the
 * track while it plays so the gain is known the next time around.
 *
 * The gain is looked up once, on the first buffer of a track, and then held: an AGC that keeps
 * steering the gain while a song plays is audibly pumping, and it cannot tell a quiet master
 * from a quiet passage. Measuring always uses the *pre-gain* samples, so repeated plays measure
 * the same level instead of compounding their own correction.
 *
 * Measurements are handed to [onMeasured] on the audio thread — the callback must not block,
 * so persistence happens elsewhere. Sink flushes (seeks) deliberately keep the meter running;
 * the few buffers still in flight when a track changes go to the next track's measurement,
 * which is noise against a whole song.
 */
class LoudnessAudioProcessor(
    private val onMeasured: (videoId: String, measurement: LoudnessMeasurement) -> Unit,
) : BaseAudioProcessor() {

    private val meter = LoudnessMeter()
    private var gain = 1.0f
    private var generation = 0

    /** Told by the engine before each load; read on the audio thread in [queueInput]. */
    @Volatile private var requested: Requested? = null
    @Volatile private var appliedGeneration = -1
    @Volatile private var appliedVideoId: String? = null

    private data class Requested(val videoId: String, val generation: Int)

    /** Marks a fresh load of [videoId] — the same track twice counts twice. */
    fun beginTrack(videoId: String) {
        requested = Requested(videoId, ++generation)
    }

    /** Level measured so far for the track playing now, for the engine to persist on stop. */
    fun currentMeasurement(): Pair<String, LoudnessMeasurement>? {
        val videoId = appliedVideoId ?: return null
        return meter.measurement()?.let { videoId to it }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

    override fun onReset() {
        meter.reset()
        appliedVideoId = null
        appliedGeneration = -1
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        applyPendingTrack()

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processShorts(inputBuffer, outputBuffer)
            else -> processFloats(inputBuffer, outputBuffer)
        }
        outputBuffer.flip()
    }

    private fun processShorts(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        val input = inputBuffer.asShortBuffer()
        val output = outputBuffer.asShortBuffer()
        var sumOfSquares = 0.0
        var peakAbs = 0
        val count = input.remaining()

        while (input.hasRemaining()) {
            val sample = input.get().toInt()
            output.put(scaled(sample).toShort())
            sumOfSquares += sample.toDouble() * sample
            peakAbs = maxOf(peakAbs, abs(sample))
        }
        inputBuffer.position(inputBuffer.limit())
        outputBuffer.position(outputBuffer.position() + count * 2)
        meter.accept(sumOfSquares, count.toLong(), peakAbs)
    }

    private fun processFloats(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        val input = inputBuffer.asFloatBuffer()
        val output = outputBuffer.asFloatBuffer()
        var sumOfSquares = 0.0
        var peakAbs = 0
        val count = input.remaining()

        while (input.hasRemaining()) {
            val sample = input.get()
            output.put((sample * gain).coerceIn(-1f, 1f))
            // The meter is fed 16-bit-scaled values so both encodings land on the same scale.
            val forMeter = sample * 32768f
            sumOfSquares += forMeter.toDouble() * forMeter
            peakAbs = maxOf(peakAbs, abs(forMeter).toInt())
        }
        inputBuffer.position(inputBuffer.limit())
        outputBuffer.position(outputBuffer.position() + count * 4)
        meter.accept(sumOfSquares, count.toLong(), peakAbs)
    }

    private fun scaled(sample: Int): Int =
        (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

    /** Publishes the finished track's level and picks up the next track's gain. */
    private fun applyPendingTrack() {
        val want = requested ?: return
        if (want.generation == appliedGeneration) return
        appliedVideoId?.let { videoId -> meter.measurement()?.let { onMeasured(videoId, it) } }
        meter.reset()
        appliedVideoId = want.videoId
        appliedGeneration = want.generation
        gain = LoudnessStore.gain(want.videoId) ?: 1.0f
    }
}
