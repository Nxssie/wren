package mix

import models.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MixSuggestionsTest {

    private fun track(
        id: String,
        bpm: Double? = null,
        camelot: String? = null
    ) = MixTrack(
        id = id,
        videoId = id,
        title = "track $id",
        artist = "artist",
        source = Source.YT_MUSIC,
        bpm = bpm,
        camelot = camelot
    )

    private val anchor = track("anchor", bpm = 128.0, camelot = "8A")

    // ── Ranking order ────────────────────────────────────────────────────────

    @Test
    fun `the same key at the same tempo is the top suggestion`() {
        val ranking = MixSuggestions.rank(
            anchor,
            listOf(track("same", bpm = 128.0, camelot = "8A"), track("adjacent", bpm = 128.0, camelot = "9A"))
        )

        assertEquals(listOf("same", "adjacent"), ranking.suggestions.map { it.track.id })
        assertEquals(100, ranking.suggestions.first().affinity.score)
    }

    @Test
    fun `harmonic relations rank same above relative above adjacent`() {
        val ranking = MixSuggestions.rank(
            anchor,
            listOf(
                track("adjacent", bpm = 128.0, camelot = "7A"),
                track("relative", bpm = 128.0, camelot = "8B"),
                track("same", bpm = 128.0, camelot = "8A")
            )
        )

        assertEquals(listOf("same", "relative", "adjacent"), ranking.suggestions.map { it.track.id })
        val scores = ranking.suggestions.map { it.affinity.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `a closer tempo wins between two harmonic candidates`() {
        val ranking = MixSuggestions.rank(
            anchor,
            listOf(track("far", bpm = 132.0, camelot = "8A"), track("near", bpm = 129.0, camelot = "8A"))
        )

        assertEquals(listOf("near", "far"), ranking.suggestions.map { it.track.id })
    }

    // ── Key rules ────────────────────────────────────────────────────────────

    @Test
    fun `a clashing key is left out by default`() {
        val ranking = MixSuggestions.rank(anchor, listOf(track("clash", bpm = 128.0, camelot = "3A")))

        assertTrue(ranking.suggestions.isEmpty())
        assertEquals(listOf("clash"), ranking.unranked.map { it.id })
    }

    @Test
    fun `a clashing key is allowed but penalised when harmonics are optional`() {
        val ranking = MixSuggestions.rank(
            anchor,
            listOf(track("clash", bpm = 128.0, camelot = "3A"), track("same", bpm = 128.0, camelot = "8A")),
            MixFilters(requireHarmonic = false)
        )

        assertEquals(listOf("same", "clash"), ranking.suggestions.map { it.track.id })
        assertTrue(ranking.suggestions.last().affinity.score < ranking.suggestions.first().affinity.score)
    }

    @Test
    fun `a candidate without a key cannot be harmonic and is left out`() {
        val ranking = MixSuggestions.rank(anchor, listOf(track("nokey", bpm = 128.0)))

        assertTrue(ranking.suggestions.isEmpty())
        assertEquals(listOf("nokey"), ranking.unranked.map { it.id })
    }

    @Test
    fun `a candidate without a key ranks below a harmonic one when harmonics are optional`() {
        val ranking = MixSuggestions.rank(
            anchor,
            listOf(track("nokey", bpm = 128.0), track("same", bpm = 128.0, camelot = "8A")),
            MixFilters(requireHarmonic = false)
        )

        assertEquals(listOf("same", "nokey"), ranking.suggestions.map { it.track.id })
    }

    // ── Tempo rules ──────────────────────────────────────────────────────────

    @Test
    fun `a tempo outside the tolerance is left out`() {
        val ranking = MixSuggestions.rank(anchor, listOf(track("fast", bpm = 140.0, camelot = "8A")))

        assertTrue(ranking.suggestions.isEmpty())
    }

    @Test
    fun `tempo tolerance is configurable`() {
        val candidate = track("fast", bpm = 140.0, camelot = "8A")

        assertTrue(MixSuggestions.rank(anchor, listOf(candidate)).suggestions.isEmpty())
        assertEquals(
            listOf("fast"),
            MixSuggestions.rank(anchor, listOf(candidate), MixFilters(bpmTolerancePercent = 10.0))
                .suggestions.map { it.track.id }
        )
    }

    @Test
    fun `half-time and double-time candidates mix by folding`() {
        val slowAnchor = track("anchor", bpm = 87.0, camelot = "8A")
        val ranking = MixSuggestions.rank(slowAnchor, listOf(track("double", bpm = 174.0, camelot = "8A")))

        assertEquals(listOf("double"), ranking.suggestions.map { it.track.id })
    }

    @Test
    fun `folding can be turned off`() {
        val slowAnchor = track("anchor", bpm = 87.0, camelot = "8A")
        val ranking = MixSuggestions.rank(
            slowAnchor,
            listOf(track("double", bpm = 174.0, camelot = "8A")),
            MixFilters(allowTempoFolding = false)
        )

        assertTrue(ranking.suggestions.isEmpty())
    }

    @Test
    fun `a candidate with only a key still ranks`() {
        val ranking = MixSuggestions.rank(anchor, listOf(track("keyonly", camelot = "8A")))

        assertEquals(listOf("keyonly"), ranking.suggestions.map { it.track.id })
        assertNull(ranking.suggestions.single().affinity.bpmDeltaPercent.takeIf { it > 0 })
    }

    @Test
    fun `a track with no analysis at all is unranked`() {
        val ranking = MixSuggestions.rank(anchor, listOf(track("nothing"), track("same", bpm = 128.0, camelot = "8A")))

        assertEquals(listOf("same"), ranking.suggestions.map { it.track.id })
        assertEquals(listOf("nothing"), ranking.unranked.map { it.id })
    }

    @Test
    fun `the anchor is never its own suggestion`() {
        val ranking = MixSuggestions.rank(anchor, listOf(anchor, track("same", bpm = 128.0, camelot = "8A")))

        assertEquals(listOf("same"), ranking.suggestions.map { it.track.id })
        assertTrue(ranking.unranked.none { it.id == anchor.id })
    }

    // ── Explanations ─────────────────────────────────────────────────────────

    @Test
    fun `a suggestion explains both halves of the match`() {
        val ranking = MixSuggestions.rank(anchor, listOf(track("relative", bpm = 129.0, camelot = "8B")))
        val affinity = ranking.suggestions.single().affinity

        assertEquals(HarmonicRelation.RELATIVE, affinity.relation)
        assertNotNull(affinity.reasons.firstOrNull { it.contains("8B") })
        assertNotNull(affinity.reasons.firstOrNull { it.contains("0.8%") })
    }

    @Test
    fun `a half-time match says so`() {
        val ranking = MixSuggestions.rank(
            track("anchor", bpm = 87.0, camelot = "8A"),
            listOf(track("double", bpm = 174.0, camelot = "8A"))
        )

        assertNotNull(ranking.suggestions.single().affinity.reasons.firstOrNull { it.contains("half") })
    }

    @Test
    fun `ranking is stable for equal scores`() {
        val candidates = listOf(track("b", bpm = 128.0, camelot = "8A"), track("a", bpm = 128.0, camelot = "8A"))

        val first = MixSuggestions.rank(anchor, candidates).suggestions.map { it.track.id }
        val second = MixSuggestions.rank(anchor, candidates.reversed()).suggestions.map { it.track.id }

        assertEquals(first, second)
    }
}
