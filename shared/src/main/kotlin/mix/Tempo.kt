package mix

/**
 * Tempo folding for mixing. A track recorded at 87 BPM mixes with one at 174 BPM because the
 * half-time grid lines up, so before comparing two tempos one of them is folded by octaves into
 * the band around the other. A missing tempo (0) stays missing: folding it would invent a beat.
 */
object Tempo {

    private const val SQRT2 = 1.4142135623730951

    /** [candidateBpm] shifted by whole octaves (×2, ÷2, …) into the band closest to [anchorBpm]. */
    fun fold(anchorBpm: Double, candidateBpm: Double): Double {
        if (anchorBpm <= 0.0 || candidateBpm <= 0.0) return 0.0

        // The band is ±half an octave around the anchor, so the fold always picks the nearer
        // octave; a tempo exactly one octave away is equidistant and folds up.
        val low = anchorBpm / SQRT2
        val high = anchorBpm * SQRT2
        var folded = candidateBpm
        var guard = 0
        while (folded < low && guard++ < 64) folded *= 2.0
        guard = 0
        while (folded > high && guard++ < 64) folded /= 2.0
        return folded
    }

    /** Signed percent difference of [candidateBpm] from [anchorBpm], after folding. */
    fun deltaPercent(anchorBpm: Double, candidateBpm: Double): Double {
        if (anchorBpm <= 0.0 || candidateBpm <= 0.0) return Double.MAX_VALUE
        val folded = fold(anchorBpm, candidateBpm)
        return (folded - anchorBpm) / anchorBpm * 100.0
    }
}
