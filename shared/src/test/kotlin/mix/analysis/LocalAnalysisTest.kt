package mix.analysis

import mix.BpmKey
import mix.CamelotKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The analysis has to be right about a signal whose answer is known, or it is only guessing with
 * extra steps. Every case below is synthesised: a pulse train at a stated tempo, and triad loops in
 * a stated key, so the expected answer is arithmetic rather than opinion.
 */
class LocalAnalysisTest {

    private val sampleRate = 22_050

    /** A decaying tone every beat: a usable pulse for onset detection, with no noise to seed. */
    private fun clickTrain(bpm: Double, seconds: Double = 8.0): FloatArray {
        val samples = FloatArray((sampleRate * seconds).toInt())
        val period = (60.0 / bpm * sampleRate).toInt()
        val burst = (0.06 * sampleRate).toInt()
        var start = 0
        while (start < samples.size) {
            for (i in 0 until burst) {
                val index = start + i
                if (index >= samples.size) break
                val envelope = exp(-4.0 * i / burst)
                samples[index] += (0.8 * envelope * sin(2 * PI * 1800.0 * i / sampleRate)).toFloat()
            }
            start += period
        }
        return samples
    }

    /** Two bars of the I–IV–V triads of one key, each triad held for a beat. */
    private fun triadLoop(root: Int, minor: Boolean, seconds: Double = 8.0): FloatArray {
        val third = if (minor) 3 else 4
        val triads = listOf(
            intArrayOf(root, root + third, root + 7),
            intArrayOf(root + 5, root + 5 + third, root + 12),
            intArrayOf(root + 7, root + 7 + third, root + 14)
        )
        val samples = FloatArray((sampleRate * seconds).toInt())
        val beat = (0.5 * sampleRate).toInt()
        var position = 0
        var triad = 0
        while (position < samples.size) {
            for (pitchClass in triads[triad % triads.size]) {
                val frequency = 440.0 * Math.pow(2.0, (pitchClass - 9) / 12.0)
                for (i in 0 until beat) {
                    val index = position + i
                    if (index >= samples.size) break
                    // Short attack/release so the loop does not smear across bar boundaries.
                    val edge = minOf(i, beat - i).toDouble() / (0.01 * sampleRate)
                    val envelope = edge.coerceIn(0.0, 1.0)
                    samples[index] += (0.25 * envelope * sin(2 * PI * frequency * i / sampleRate)).toFloat()
                }
            }
            position += beat
            triad++
        }
        return samples
    }

    // ── Tempo ────────────────────────────────────────────────────────────────

    @Test
    fun `a 128 bpm pulse train reads 128`() {
        val bpm = LocalAnalysis.tempo(clickTrain(128.0), sampleRate)

        assertNotNull(bpm)
        assertTrue(abs(bpm!! - 128.0) <= 2.0, "expected ~128, got $bpm")
    }

    @Test
    fun `a 90 bpm pulse train reads 90`() {
        val bpm = LocalAnalysis.tempo(clickTrain(90.0), sampleRate)

        assertNotNull(bpm)
        assertTrue(abs(bpm!! - 90.0) <= 2.0, "expected ~90, got $bpm")
    }

    @Test
    fun `a fast pulse train may fold to half time but never to a wrong tempo`() {
        val bpm = LocalAnalysis.tempo(clickTrain(175.0), sampleRate)

        assertNotNull(bpm)
        val distance = minOf(abs(bpm!! - 175.0), abs(bpm - 87.5))
        assertTrue(distance <= 2.0, "expected ~175 or ~87.5, got $bpm")
    }

    @Test
    fun `silence has no tempo`() {
        assertNull(LocalAnalysis.tempo(FloatArray(sampleRate * 4), sampleRate))
    }

    // ── Key ──────────────────────────────────────────────────────────────────

    @Test
    fun `C major with no sharps reads 8B`() {
        assertEquals(CamelotKey(8, "B"), LocalAnalysis.key(triadLoop(root = 0, minor = false), sampleRate))
    }

    @Test
    fun `A minor reads 8A`() {
        assertEquals(CamelotKey(8, "A"), LocalAnalysis.key(triadLoop(root = 9, minor = true), sampleRate))
    }

    @Test
    fun `G major reads 9B`() {
        assertEquals(CamelotKey(9, "B"), LocalAnalysis.key(triadLoop(root = 7, minor = false), sampleRate))
    }

    @Test
    fun `silence has no key`() {
        assertNull(LocalAnalysis.key(FloatArray(sampleRate * 4), sampleRate))
    }

    // ── Combined answer ──────────────────────────────────────────────────────

    @Test
    fun `the combined answer names the analyser as its provider`() {
        val answer = LocalAnalysis.bpmKey(triadLoop(root = 0, minor = false), sampleRate)

        assertNotNull(answer)
        assertEquals(LocalAnalysis.PROVIDER_NAME, answer!!.provider)
        assertEquals(CamelotKey(8, "B"), answer.camelot)
        assertTrue(answer.bpm != null || answer.camelot != null)
    }

    @Test
    fun `an empty buffer answers nothing instead of throwing`() {
        val answer = LocalAnalysis.bpmKey(FloatArray(0), sampleRate)

        assertTrue(answer == null || (answer.bpm == null && answer.camelot == null))
    }
}
