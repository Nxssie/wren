package player

import kotlinx.serialization.Serializable
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** One track's measured level, accumulated from decoded PCM while it played. */
@Serializable
data class LoudnessMeasurement(
    /** Energy-average loudness, roughly integrated LUFS (see [LoudnessMeter]). */
    val lufs: Double,
    /** Loudest sample seen, dBFS. Not a true-peak scan: inter-sample peaks are missed. */
    val truePeakDbfs: Double,
)

/**
 * Level normalisation target, shared so both players put a track at the same level.
 *
 * The measurement is an approximation of integrated LUFS, so the absolute number can sit a
 * couple of dB off the real thing — but the *estimator* is the same on every platform, so
 * the bias cancels out in the relative level between tracks, which is what makes a loud
 * SoundCloud master and a quiet YouTube upload stop sounding like different apps.
 */
object Loudness {
    /** Streaming-service convention (Spotify, YouTube), quieter than the CD-era -9 LUFS. */
    const val TARGET_LUFS = -14.0

    /** Bounds on how far a track is moved, so nothing is crushed or blown up. */
    private const val MAX_GAIN_DB = 9.0
    private const val MIN_GAIN_DB = -12.0

    /** Headroom left below full scale after applying the gain. */
    private const val PEAK_CEILING_DBFS = -1.0

    /**
     * Gain in dB bringing [measurement] to [TARGET_LUFS] without pushing the track into
     * clipping. The peak cap is applied last and only ever lowers the result.
     */
    fun gainDb(measurement: LoudnessMeasurement): Double {
        val desired = (TARGET_LUFS - measurement.lufs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        return min(desired, PEAK_CEILING_DBFS - measurement.truePeakDbfs)
    }

    fun gain(measurement: LoudnessMeasurement): Float = 10.0.pow(gainDb(measurement) / 20.0).toFloat()
}

/**
 * Accumulates a track's loudness from 16-bit PCM as it plays — one multiply-add per sample,
 * which is what the desktop AGC already did inline before this replaced it.
 *
 * Chunks quieter than [GATE_DBFS] are left out of the average: without that, the digital
 * silence a stream carries between tracks drags the whole track down. That is a crude stand-in
 * for the relative gate of real R128 measurement (this is a plain energy average, no gating
 * curve), and it is enough because the value is only ever compared against other tracks
 * measured the same way.
 */
class LoudnessMeter {
    // Volatile because a player may snapshot the running measurement from another thread
    // than the one queuing audio (Android persists it when a track is skipped).
    @Volatile private var sumOfSquares = 0.0
    @Volatile private var samples = 0L
    @Volatile private var peakAbs = 0

    /**
     * @param chunkSumSquares Σ sample² over the chunk, with [count] samples in it.
     * @param chunkPeakMax loudest |sample| in the chunk.
     */
    fun accept(chunkSumSquares: Double, count: Long, chunkPeakMax: Int) {
        if (count > 0 && dbfs(sqrt(chunkSumSquares / count)) >= GATE_DBFS) {
            sumOfSquares += chunkSumSquares
            samples += count
        }
        if (chunkPeakMax > peakAbs) peakAbs = chunkPeakMax
    }

    /** Null until a chunk above the gate has been seen — silence has no level to normalise. */
    fun measurement(): LoudnessMeasurement? {
        if (samples == 0L) return null
        return LoudnessMeasurement(
            lufs = dbfs(sqrt(sumOfSquares / samples)) + K_WEIGHTING_OFFSET_DB,
            truePeakDbfs = dbfs(peakAbs.toDouble()),
        )
    }

    fun reset() {
        sumOfSquares = 0.0
        samples = 0L
        peakAbs = 0
    }

    private companion object {
        const val FULL_SCALE = 32768.0
        const val GATE_DBFS = -55.0
        /** Empirically, K-weighted music loudness sits this far above its plain RMS. */
        const val K_WEIGHTING_OFFSET_DB = 3.0

        fun dbfs(amplitude: Double) = 20.0 * log10(amplitude / FULL_SCALE)
    }
}
