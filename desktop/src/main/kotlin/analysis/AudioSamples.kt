package analysis

import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ShortBuffer

/**
 * Pulls a bounded slice of a stream into the mono float samples the local analyser reads.
 *
 * Deliberately not the player: the player streams in real time through a `SourceDataLine`, while
 * this decodes as fast as the network allows and stops early. Twenty seconds is enough for a tempo
 * estimate and a chroma profile, and it is short enough that a set of tracks is usable.
 */
object AudioSamples {

    /** 22.05 kHz covers everything up to 11 kHz — plenty for onsets and pitch classes. */
    const val TARGET_SAMPLE_RATE = 22_050

    /**
     * Six minutes, which covers a normal track end to end and stops a DJ mix or a live set from
     * turning one detection into a long download. The analyser weighs the parts of this buffer
     * against each other, so a fixed short window is not needed any more: twenty seconds sampled
     * the intro of a track whose body is what the answer should describe.
     */
    const val DEFAULT_SECONDS = 360.0

    /**
     * Decodes at most [seconds] of [streamUrl] into mono samples in [-1, 1].
     *
     * Returns `null` when nothing usable came back: an analysis on silence would otherwise report a
     * confident tempo for a track that failed to load.
     */
    fun decode(
        streamUrl: String,
        seconds: Double = DEFAULT_SECONDS,
        targetRate: Int = TARGET_SAMPLE_RATE
    ): FloatArray? {
        if (streamUrl.isBlank() || seconds <= 0.0) return null

        val grabber = FFmpegFrameGrabber(streamUrl)
        return try {
            grabber.audioChannels = 1
            grabber.sampleRate = targetRate
            grabber.sampleFormat = AV_SAMPLE_FMT_S16
            // Same reconnect options the player uses; no custom user agent, the URL is signed for
            // the client that resolved it.
            grabber.setOption("reconnect", "1")
            grabber.setOption("reconnect_streamed", "1")
            grabber.setOption("timeout", "30000000")
            grabber.start()

            val wanted = (seconds * targetRate).toInt()
            val collected = ArrayList<Float>(wanted)
            while (collected.size < wanted) {
                val frame = runCatching { grabber.grabSamples() }.getOrNull() ?: break
                val buffer = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                val chunk = ShortArray(buffer.remaining())
                buffer.get(chunk)
                for (value in chunk) collected.add(value / 32768.0f)
            }
            if (collected.isEmpty()) null else collected.toFloatArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    /**
     * Folds interleaved 16-bit frames down to mono at [targetRate], averaging every source frame
     * that falls in one target frame. Pure on purpose: the resampling rule is the part worth a
     * test, and it must not need a network or a codec to be checked.
     */
    fun fold(interleaved: ShortArray, channels: Int, sourceRate: Int, targetRate: Int): FloatArray {
        if (channels <= 0 || sourceRate <= 0 || targetRate <= 0) return FloatArray(0)
        val frames = interleaved.size / channels
        if (frames == 0) return FloatArray(0)
        if (sourceRate == targetRate) {
            return FloatArray(frames) { frame ->
                var sum = 0.0f
                for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
                sum / channels / 32768.0f
            }
        }

        val ratio = sourceRate.toDouble() / targetRate
        val targetFrames = (frames / ratio).toInt()
        return FloatArray(targetFrames) { target ->
            val from = (target * ratio).toInt()
            val to = (((target + 1) * ratio).toInt()).coerceAtMost(frames)
            var sum = 0.0f
            var count = 0
            for (frame in from until to) {
                for (channel in 0 until channels) {
                    sum += interleaved[frame * channels + channel]
                    count++
                }
            }
            if (count == 0) 0f else sum / count / 32768.0f
        }
    }
}
