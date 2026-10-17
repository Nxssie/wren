package mix

import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

/** How strictly a suggestion has to match the anchor track. Mirrors the switches on the screen. */
data class MixFilters(
    val bpmTolerancePercent: Double = 6.0,
    val requireHarmonic: Boolean = true,
    val allowTempoFolding: Boolean = true
)

/**
 * Why one candidate fits the anchor: the harmonic relation, the signed tempo difference after
 * folding ([Double.NaN] when one of the tracks has no tempo), and the reasons a DJ would read.
 */
data class Affinity(
    val score: Int,
    val relation: HarmonicRelation,
    val bpmDeltaPercent: Double,
    val reasons: List<String>
)

data class Suggestion(val track: MixTrack, val affinity: Affinity)

/** Suggested [suggestions] in order, plus the [unranked] candidates that had to be left out. */
data class MixRanking(val suggestions: List<Suggestion>, val unranked: List<MixTrack>)

/**
 * The affinity engine: given the track being mixed away from, rank the candidates that would work
 * next.
 *
 * The rules are the harmonic-mixing ones, and they are deliberately strict — a suggestion the user
 * has to reject costs more than a suggestion never shown. A key that clashes, a tempo outside the
 * tolerance, or a track with no analysis at all is not scored: it lands in [MixRanking.unranked] so
 * the screen can say why the list is short instead of quietly shortening it.
 */
object MixSuggestions {

    private const val UNKNOWN_KEY_PENALTY = 15
    private const val TEMPO_PENALTY_PER_PERCENT = 3.0

    fun rank(
        anchor: MixTrack,
        candidates: List<MixTrack>,
        filters: MixFilters = MixFilters()
    ): MixRanking {
        val pool = candidates.distinctBy { it.id }.filter { it.id != anchor.id }
        val suggestions = pool
            .mapNotNull { candidate -> affinityOf(anchor, candidate, filters)?.let { Suggestion(candidate, it) } }
            .sortedWith(
                compareByDescending<Suggestion> { it.affinity.score }
                    .thenBy { it.affinity.bpmDeltaPercent.let { d -> if (d.isNaN()) Double.MAX_VALUE else abs(d) } }
                    .thenBy { it.track.title }
                    .thenBy { it.track.id }
            )
        val scored = suggestions.mapTo(mutableSetOf()) { it.track.id }
        return MixRanking(suggestions = suggestions, unranked = pool.filterNot { it.id in scored })
    }

    /** The affinity of [candidate] to [anchor], or null when it does not fit under [filters]. */
    fun affinityOf(anchor: MixTrack, candidate: MixTrack, filters: MixFilters = MixFilters()): Affinity? {
        if (anchor.id == candidate.id) return null
        if (!anchor.isAnalysed || !candidate.isAnalysed) return null

        val reasons = mutableListOf<String>()

        val anchorKey = anchor.key
        val candidateKey = candidate.key
        val relation: HarmonicRelation
        val keyPenalty: Int
        when {
            anchorKey != null && candidateKey != null -> {
                relation = anchorKey.relationTo(candidateKey)
                if (relation == HarmonicRelation.NONE && filters.requireHarmonic) return null
                keyPenalty = relation.penalty()
                reasons += "key $candidateKey (${relation.label})"
            }
            filters.requireHarmonic -> return null
            else -> {
                relation = HarmonicRelation.NONE
                keyPenalty = UNKNOWN_KEY_PENALTY
                reasons += "key unknown"
            }
        }

        val anchorBpm = anchor.bpm?.takeIf { it > 0.0 }
        val candidateBpm = candidate.bpm?.takeIf { it > 0.0 }
        var deltaPercent = Double.NaN
        var tempoPenalty = 0.0
        if (anchorBpm != null && candidateBpm != null) {
            val folded = if (filters.allowTempoFolding) Tempo.fold(anchorBpm, candidateBpm) else candidateBpm
            val percent = (folded - anchorBpm) / anchorBpm * 100.0
            if (abs(percent) > filters.bpmTolerancePercent) return null
            deltaPercent = percent
            tempoPenalty = abs(percent) * TEMPO_PENALTY_PER_PERCENT
            reasons += if (folded != candidateBpm) {
                "tempo ${number(candidateBpm)} → ${number(folded)} BPM (half-time)"
            } else {
                "tempo ${number(candidateBpm)} BPM (${signed(percent)})"
            }
        }

        val score = (100.0 - tempoPenalty - keyPenalty).roundToInt().coerceIn(0, 100)
        return Affinity(score = score, relation = relation, bpmDeltaPercent = deltaPercent, reasons = reasons)
    }

    private fun HarmonicRelation.penalty(): Int = when (this) {
        HarmonicRelation.SAME -> 0
        HarmonicRelation.RELATIVE -> 5
        HarmonicRelation.ADJACENT -> 12
        HarmonicRelation.NONE -> 30
    }

    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", value)

    private fun signed(percent: Double): String = String.format(Locale.ROOT, "%+.1f%%", percent)
}
