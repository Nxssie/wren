package player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.AppDirs
import java.io.File

class LoudnessTest {

    // ── Gain to target ───────────────────────────────────────────────────────

    @Test
    fun `brings a loud master down to the target`() {
        // -8 LUFS, peaking at -2: only the loudness matters here.
        val gain = Loudness.gainDb(LoudnessMeasurement(lufs = -8.0, truePeakDbfs = -2.0))
        assertEquals(-6.0, gain, 1e-6)
    }

    @Test
    fun `boosts a quiet master up to the target`() {
        val gain = Loudness.gainDb(LoudnessMeasurement(lufs = -17.5, truePeakDbfs = -12.0))
        assertEquals(3.5, gain, 1e-6)
    }

    @Test
    fun `keeps boost away from the peak ceiling`() {
        // -20 LUFS wants +6 dB, but it already peaks at -6 dBFS: anything past +5 would clip.
        val gain = Loudness.gainDb(LoudnessMeasurement(lufs = -20.0, truePeakDbfs = -6.0))
        assertEquals(5.0, gain, 1e-6)
    }

    @Test
    fun `never amplifies past the ceiling even for a very quiet track`() {
        val gain = Loudness.gainDb(LoudnessMeasurement(lufs = -30.0, truePeakDbfs = -0.2))
        assertEquals(-0.8, gain, 1e-6)
    }

    @Test
    fun `caps how far a track is moved either way`() {
        assertEquals(9.0, Loudness.gainDb(LoudnessMeasurement(lufs = -40.0, truePeakDbfs = -40.0)), 1e-6)
        assertEquals(-12.0, Loudness.gainDb(LoudnessMeasurement(lufs = 5.0, truePeakDbfs = -0.1)), 1e-6)
    }

    @Test
    fun `converts decibels to a linear multiplier`() {
        assertEquals(0.5011872f, Loudness.gain(LoudnessMeasurement(lufs = -8.0, truePeakDbfs = -2.0)), 1e-6f)
    }

    // ── Measurement ──────────────────────────────────────────────────────────

    @Test
    fun `measures a constant tone`() {
        val meter = LoudnessMeter()
        // Amplitude 8192 = -12.04 dBFS, so the K-weighted estimate lands 3 dB above that.
        meter.accept(chunkSumSquares = 8192.0 * 8192.0 * 100, count = 100, chunkPeakMax = 8192)

        val measurement = meter.measurement()!!
        assertEquals(-9.04, measurement.lufs, 0.01)
        assertEquals(-12.04, measurement.truePeakDbfs, 0.01)
    }

    @Test
    fun `has no measurement for silence`() {
        val meter = LoudnessMeter()
        meter.accept(chunkSumSquares = 0.0, count = 4410, chunkPeakMax = 0)

        assertNull(meter.measurement())
    }

    @Test
    fun `leaves silent stretches out of the average`() {
        val quiet = LoudnessMeter()
        quiet.accept(chunkSumSquares = 8192.0 * 8192.0 * 100, count = 100, chunkPeakMax = 8192)
        quiet.accept(chunkSumSquares = 0.0, count = 900, chunkPeakMax = 0)

        val withSilence = quiet.measurement()!!
        // Digital silence must not drag the level down: 100 loud samples, 1000 total.
        assertEquals(-9.04, withSilence.lufs, 0.01)
    }

    @Test
    fun `reset forgets the previous track`() {
        val meter = LoudnessMeter()
        meter.accept(chunkSumSquares = 8192.0 * 8192.0 * 100, count = 100, chunkPeakMax = 8192)
        meter.reset()

        assertNull(meter.measurement())
    }

    // ── Store ────────────────────────────────────────────────────────────────

    @Test
    fun `reads stored levels and merges new ones`(@TempDir dir: File) {
        AppDirs.init(File(dir, "config"), File(dir, "state"))
        LoudnessStore.clear()

        AppDirs.state.mkdirs()
        File(AppDirs.state, "loudness.json")
            .writeText("""{"abc":{"lufs":-20.0,"truePeakDbfs":-6.0}}""")
        assertEquals(1.7783f, LoudnessStore.gain("abc")!!, 1e-3f)

        LoudnessStore.record("xyz", LoudnessMeasurement(lufs = -15.0, truePeakDbfs = -2.0))
        // Visible straight away: readers never wait for the write that just happened.
        assertEquals(1.1220f, LoudnessStore.gain("xyz")!!, 1e-3f)

        val written = File(AppDirs.state, "loudness.json").readText()
        assertTrue("abc" in written && "xyz" in written)

        LoudnessStore.clear()
    }
}
