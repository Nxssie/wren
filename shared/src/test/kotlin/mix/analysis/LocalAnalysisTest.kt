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

    /** Deterministic broadband bursts: the percussive half of a drum-led remix. */
    private fun noiseBursts(bpm: Double, seconds: Double = 8.0): FloatArray {
        val samples = FloatArray((sampleRate * seconds).toInt())
        val period = (60.0 / bpm * sampleRate).toInt()
        val burst = (0.05 * sampleRate).toInt()
        var seed = 12_345L
        var start = 0
        while (start < samples.size) {
            for (i in 0 until burst) {
                val index = start + i
                if (index >= samples.size) break
                seed = (seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
                val noise = ((seed ushr 33).toInt() % 2000) / 2000.0
                samples[index] += (0.55 * exp(-3.0 * i / burst) * noise).toFloat()
            }
            start += period
        }
        return samples
    }

    @Test
    fun `a key survives a percussive layer on top of it`() {
        val tonal = triadLoop(root = 0, minor = false)
        val drums = noiseBursts(140.0)
        val mixed = FloatArray(tonal.size) { i -> (tonal[i] + drums[i]).coerceIn(-1f, 1f) }

        assertEquals(CamelotKey(8, "B"), LocalAnalysis.key(mixed, sampleRate))
    }

    @Test
    fun `a track tied between a key and its relative still names one of them`() {
        // A minor and C major share every note, so a loop that touches both scores them almost
        // identically. Both slots mix with the same neighbours, so naming the better half is useful
        // information; a silence is not.
        val aMinor = triadLoop(root = 9, minor = true)
        val cMajor = triadLoop(root = 0, minor = false)
        val blended = FloatArray(minOf(aMinor.size, cMajor.size)) { i -> (aMinor[i] + cMajor[i]) / 2f }

        val key = LocalAnalysis.key(blended, sampleRate)

        assertNotNull(key)
        assertEquals(8, key!!.number, "an A minor / C major tie must land on the same Camelot number")
    }

    @Test
    fun `a 123 bpm pulse train reads 123`() {
        val bpm = LocalAnalysis.tempo(clickTrain(123.0), sampleRate)

        assertNotNull(bpm)
        assertTrue(abs(bpm!! - 123.0) <= 2.0, "expected ~123, got $bpm")
    }

    @Test
    fun `a track tied with a neighbouring key still names one of them`() {
        // A minor and D minor share two notes and are one step apart on the wheel: the mixing rules
        // already tolerate that difference, so the better half is reported instead of nothing.
        val aMinor = triadLoop(root = 9, minor = true)
        val dMinor = triadLoop(root = 2, minor = true)
        val blended = FloatArray(minOf(aMinor.size, dMinor.size)) { i -> (aMinor[i] + dMinor[i]) / 2f }

        val key = LocalAnalysis.key(blended, sampleRate)

        assertNotNull(key)
        assertTrue(key!!.number in 7..8, "an A minor / D minor tie must land on 7A or 8A, got $key")
    }

    @Test
    fun `tie compatibility follows the mixing rules`() {
        val aMinor = CamelotKey(8, "A")

        assertTrue(LocalAnalysis.tieIsCompatible(aMinor, CamelotKey(8, "A")), "same slot")
        assertTrue(LocalAnalysis.tieIsCompatible(aMinor, CamelotKey(8, "B")), "relative")
        assertTrue(LocalAnalysis.tieIsCompatible(aMinor, CamelotKey(7, "A")), "one step down")
        assertTrue(LocalAnalysis.tieIsCompatible(aMinor, CamelotKey(9, "A")), "one step up")
        assertTrue(LocalAnalysis.tieIsCompatible(CamelotKey(12, "A"), CamelotKey(1, "A")), "wraps 12 -> 1")
        assertTrue(!LocalAnalysis.tieIsCompatible(aMinor, CamelotKey(11, "A")), "three steps away")
        assertTrue(!LocalAnalysis.tieIsCompatible(aMinor, CamelotKey(2, "B")), "distant and other side")
    }

    // ── The main part of a whole track ───────────────────────────────────────

    @Test
    fun `an intro at another tempo does not decide the tempo of the track`() {
        // A quiet 90 BPM intro, then the loud 128 BPM body: the body is what the track is.
        val intro = clickTrain(90.0, seconds = 20.0).map { it * 0.25f }.toFloatArray()
        val body = clickTrain(128.0, seconds = 60.0)
        val track = intro + body

        val bpm = LocalAnalysis.analyse(track, sampleRate)?.bpm

        assertNotNull(bpm)
        assertTrue(abs(bpm!! - 128.0) <= 2.0, "expected the 128 BPM body, got $bpm")
    }

    @Test
    fun `a quiet intro in another key does not decide the key of the track`() {
        val intro = triadLoop(root = 0, minor = false, seconds = 20.0).map { it * 0.25f }.toFloatArray()
        val body = triadLoop(root = 9, minor = true, seconds = 60.0)
        val track = intro + body

        val key = LocalAnalysis.analyse(track, sampleRate)?.camelot

        assertEquals(CamelotKey(8, "A"), key, "the body is A minor; the C major intro is the quiet part")
    }

    @Test
    fun `a track that is uniform reads the same as a single window`() {
        val uniform = clickTrain(128.0, seconds = 80.0)

        val whole = LocalAnalysis.analyse(uniform, sampleRate)?.bpm
        val single = LocalAnalysis.tempo(uniform, sampleRate)

        assertNotNull(whole)
        assertTrue(abs(whole!! - single!!) <= 2.0, "whole=$whole single=$single")
    }

    @Test
    fun `silence has nothing to analyse`() {
        assertNull(LocalAnalysis.analyse(FloatArray(sampleRate * 60), sampleRate))
    }

    @Test
    fun `a modulation after the opening minute does not change the key`() {
        // The key is measured over the opening minute on purpose: on real tracks, averaging a later
        // modulation in produced a key that matched neither section.
        val opening = triadLoop(root = 9, minor = true, seconds = 60.0)
        val laterLoud = triadLoop(root = 2, minor = true, seconds = 40.0).map { it * 1.2f }.toFloatArray()

        val key = LocalAnalysis.analyse(opening + laterLoud, sampleRate)?.camelot

        assertEquals(CamelotKey(8, "A"), key)
    }
}
