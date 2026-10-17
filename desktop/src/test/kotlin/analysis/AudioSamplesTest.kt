package analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The resampling rule is the only part of the decoder that can be checked without a codec, so it is
 * the part that is pinned here: how interleaved 16-bit frames become mono floats at the target rate.
 */
class AudioSamplesTest {

    @Test
    fun `stereo input at twice the target rate is halved and kept in range`() {
        val frames = 4_096
        val interleaved = ShortArray(frames * 2) { 16_384 } // 0.5 in both channels

        val mono = AudioSamples.fold(interleaved, channels = 2, sourceRate = 44_100, targetRate = 22_050)

        assertEquals(frames / 2, mono.size)
        assertTrue(mono.all { abs(it - 0.5f) < 0.01f }, "every sample should stay around 0.5")
    }

    @Test
    fun `mono input at the target rate passes through unchanged`() {
        val values = shortArrayOf(0, 16_384, -16_384, 32_767)

        val mono = AudioSamples.fold(values, channels = 1, sourceRate = 22_050, targetRate = 22_050)

        assertEquals(values.size, mono.size)
        assertEquals(0f, mono[0])
        assertTrue(abs(mono[1] - 0.5f) < 0.01f)
        assertTrue(abs(mono[2] + 0.5f) < 0.01f)
    }

    @Test
    fun `interleaved stereo is averaged into one channel`() {
        // Left at full scale, right silent: the mono mix must sit halfway, not at either end.
        val interleaved = shortArrayOf(32_767, 0, 32_767, 0)

        val mono = AudioSamples.fold(interleaved, channels = 2, sourceRate = 22_050, targetRate = 22_050)

        assertEquals(2, mono.size)
        assertTrue(abs(mono[0] - 0.5f) < 0.02f, "expected ~0.5, got ${mono[0]}")
    }

    @Test
    fun `empty or nonsense input answers an empty buffer`() {
        assertTrue(AudioSamples.fold(ShortArray(0), 2, 44_100, 22_050).isEmpty())
        assertTrue(AudioSamples.fold(shortArrayOf(1, 2, 3), 0, 44_100, 22_050).isEmpty())
        assertTrue(AudioSamples.fold(shortArrayOf(1, 2, 3), 1, 0, 22_050).isEmpty())
    }

    @Test
    fun `a blank url is not decoded`() {
        assertEquals(null, AudioSamples.decode(""))
    }
}
