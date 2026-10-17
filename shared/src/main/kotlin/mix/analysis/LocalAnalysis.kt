package mix.analysis

import mix.BpmKey
import mix.CamelotKey
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Tempo and key estimated from the audio itself, so the Mix view works without a metadata provider
 * and without a key nobody can hand out.
 *
 * Both estimates are deliberately conservative: when the signal does not support an answer the
 * function returns `null`. A wrong tempo or key is worse than a missing one, because every
 * suggestion in the set is built on it and a plausible-looking wrong answer never announces itself.
 */
object LocalAnalysis {

    const val PROVIDER_NAME = "local"

    /** Tempo search range. Outside it the octave is ambiguous enough that a bare number misleads. */
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0

    /** ~46 ms analysis frame: long enough to resolve a kick, short enough to keep onsets separate. */
    private const val ONSET_FRAME = 1_024

    /** Resolution of the tempo search: 0.25 BPM is well inside what a DJ would notice. */
    private const val BPM_STEP = 0.25

    /**
     * How much worse a faster tempo may score and still be preferred over its slower octave.
     *
     * Measured, not guessed: on a pulse train whose true period is not a whole number of frames the
     * double-period point scores up to ~5% higher, because the onset envelope is asymmetric around
     * each hit (fast attack, slow decay) and the longer lag lands on the flatter part.
     */
    private const val OCTAVE_TOLERANCE = 0.90

    /** ~12 ms hop: a quarter of the frame, so a beat never falls between two windows. */
    private const val ONSET_HOP = 256

    /** Chroma frame of ~93 ms resolves bass notes down to ~10 Hz bins without smearing pitch. */
    private const val CHROMA_FRAME = 4_096

    private const val MIN_KEY_HZ = 55.0
    private const val MAX_KEY_HZ = 2_000.0

    /** Below this the spectrum is flat enough that any key we name would be invented. */
    private const val MIN_KEY_CONFIDENCE = 0.02

    /**
     * Krumhansl–Kessler probe-tone profiles: how strongly each scale degree is heard as belonging to
     * the key. They are the published values, not tuned here, so the correlation stays interpretable.
     */
    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )
    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    /** Both estimates, or `null` when neither could be made. */
    fun bpmKey(samples: FloatArray, sampleRate: Int): BpmKey? {
        if (samples.isEmpty() || sampleRate <= 0) return null
        val bpm = tempo(samples, sampleRate)
        val key = key(samples, sampleRate)
        if (bpm == null && key == null) return null
        return BpmKey(bpm = bpm, camelot = key, provider = PROVIDER_NAME)
    }

    // ── Tempo ────────────────────────────────────────────────────────────────

    /**
     * Beats per minute from the onset envelope: how loud the signal gets from one frame to the next,
     * self-correlated so a repeating pulse reinforces itself at its own period.
     *
     * The search runs over BPM rather than over whole frames because the beat period is almost never
     * a whole number of frames, and rounding it to one manufactures a fake octave error: 128 BPM sits
     * at 40.375 frames, where the two-frame-period point at 81 lands closer to the grid and wins.
     */
    fun tempo(samples: FloatArray, sampleRate: Int): Double? {
        if (samples.size < ONSET_FRAME * 4 || sampleRate <= 0) return null

        val envelope = onsetEnvelope(samples)
        if (envelope.sum() <= 1e-6) return null

        val hopSeconds = ONSET_HOP.toDouble() / sampleRate
        val scores = LinkedHashMap<Double, Double>()

        var bpm = MIN_BPM
        while (bpm <= MAX_BPM) {
            val lag = 60.0 / bpm / hopSeconds
            scores[bpm] = correlationAtLag(envelope, lag)
            bpm += BPM_STEP
        }

        val best = scores.values.maxOrNull() ?: return null
        if (best <= 0.0) return null

        // Two tempi that explain the signal equally well differ by an octave: prefer the faster one,
        // because the slower reading can always be produced by skipping beats. The grid is walked
        // from the fastest candidate down, so the first one inside the tolerance is the fastest.
        val chosen = scores.keys
            .sortedDescending()
            .firstOrNull { (scores[it] ?: 0.0) >= best * OCTAVE_TOLERANCE }
            ?: return null
        return chosen
    }

    /** Positive frame-to-frame energy increase per frame; percussive events show up as spikes. */
    private fun onsetEnvelope(samples: FloatArray): DoubleArray {
        val frames = (samples.size - ONSET_FRAME) / ONSET_HOP + 1
        val energy = DoubleArray(frames)
        for (frame in 0 until frames) {
            val start = frame * ONSET_HOP
            var sum = 0.0
            for (i in 0 until ONSET_FRAME) {
                val value = samples[start + i].toDouble()
                sum += value * value
            }
            energy[frame] = ln(1.0 + 1_000.0 * sum / ONSET_FRAME)
        }
        val envelope = DoubleArray(frames)
        for (frame in 1 until frames) {
            envelope[frame] = (energy[frame] - energy[frame - 1]).coerceAtLeast(0.0)
        }
        return envelope
    }

    /**
     * Cosine similarity between the envelope and a copy shifted by [lag] frames.
     *
     * The lag is fractional and the shift is applied by interpolating the *signal*, not the
     * correlation curve: interpolating the curve averages two misaligned teeth of the comb instead
     * of landing on one, which is what made 128 BPM read as 64.
     */
    private fun correlationAtLag(envelope: DoubleArray, lag: Double): Double {
        if (lag < 1.0) return 0.0
        val base = kotlin.math.floor(lag).toInt()
        if (base + 1 >= envelope.size) return 0.0
        val fraction = lag - base

        var dot = 0.0
        var leading = 0.0
        var lagged = 0.0
        for (i in base + 1 until envelope.size) {
            val shifted = envelope[i - base] * (1.0 - fraction) + envelope[i - base - 1] * fraction
            dot += envelope[i] * shifted
            leading += envelope[i] * envelope[i]
            lagged += shifted * shifted
        }
        val denominator = kotlin.math.sqrt(leading * lagged)
        return if (denominator <= 0.0) 0.0 else dot / denominator
    }

    // ── Key ──────────────────────────────────────────────────────────────────

    /**
     * The Camelot slot whose major or minor probe-tone profile best matches the chroma the audio
     * actually contains. Null when the spectrum is too evenly spread to name a tonic.
     */
    fun key(samples: FloatArray, sampleRate: Int): CamelotKey? {
        if (samples.size < CHROMA_FRAME || sampleRate <= 0) return null

        val chroma = chroma(samples, sampleRate)
        val total = chroma.sum()
        if (total <= 1e-9) return null

        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        var bestPitchClass = -1
        var bestMinor = false
        var secondPitchClass = -1
        var secondMinor = false

        for (minor in booleanArrayOf(false, true)) {
            val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
            for (tonic in 0 until 12) {
                val score = correlated(chroma, profile, tonic) / total
                if (score > bestScore) {
                    secondScore = bestScore
                    secondPitchClass = bestPitchClass
                    secondMinor = bestMinor
                    bestScore = score
                    bestPitchClass = tonic
                    bestMinor = minor
                } else if (score > secondScore) {
                    secondScore = score
                    secondPitchClass = tonic
                    secondMinor = minor
                }
            }
        }

        if (bestPitchClass < 0) return null
        val best = CamelotKey.ofPitchClass(bestPitchClass, minor = bestMinor)

        // A flat chroma matches every rotation about equally; that is not a key, it is noise.
        if (!secondScore.isFinite() || bestScore - secondScore >= MIN_KEY_CONFIDENCE) return best

        // Half the material that straddles a relative pair scores the two sides almost identically:
        // A minor and C major share every note. That is not noise, and the two slots mix with the
        // same neighbours, so the better half is reported. Two distant slots tied are still unknown.
        val runnerUp = CamelotKey.ofPitchClass(secondPitchClass, minor = secondMinor)
        return if (runnerUp.number == best.number) best else null
    }

    /** Energy per pitch class over the whole buffer, folded from the averaged magnitude spectrum. */
    private fun chroma(samples: FloatArray, sampleRate: Int): DoubleArray {
        val chroma = DoubleArray(12)
        val fft = DoubleFFT_1D(CHROMA_FRAME.toLong())
        val window = DoubleArray(CHROMA_FRAME) { i ->
            0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (CHROMA_FRAME - 1))
        }
        val buffer = DoubleArray(CHROMA_FRAME)
        val binHz = sampleRate.toDouble() / CHROMA_FRAME

        var start = 0
        while (start + CHROMA_FRAME <= samples.size) {
            for (i in 0 until CHROMA_FRAME) buffer[i] = samples[start + i] * window[i]
            fft.realForward(buffer)

            for (bin in 1 until CHROMA_FRAME / 2) {
                val re = buffer[2 * bin]
                val im = buffer[2 * bin + 1]
                val frequency = bin * binHz
                if (frequency < MIN_KEY_HZ || frequency > MAX_KEY_HZ) continue
                val magnitude = re * re + im * im
                if (magnitude <= 0.0) continue
                chroma[pitchClassOf(frequency)] += magnitude
            }
            start += CHROMA_FRAME
        }
        return chroma
    }

    /** Frequency to pitch class, 0 = C, with A4 = 440 Hz as the reference. */
    private fun pitchClassOf(frequency: Double): Int {
        val semitones = 12.0 * ln(frequency / 440.0) / ln(2.0) + 9.0
        return ((semitones.roundToInt() % 12) + 12) % 12
    }

    /** Sum of the profile against the chroma rotated so [tonic] sits at the profile's first degree. */
    private fun correlated(chroma: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        var score = 0.0
        for (degree in 0 until 12) {
            score += chroma[(tonic + degree) % 12] * profile[degree]
        }
        return score
    }
}
