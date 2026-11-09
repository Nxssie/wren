package mix

import models.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The discover list is what makes a set fillable at all, so these cover the two ways it used to
 * lose the user's candidates: an empty set has no anchor to rank against, and a fresh search result
 * has no tempo or key until it is analysed. Both cases must still offer every candidate.
 */
class MixDiscoveryTest {

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

    private val discovery = listOf(
        track("same", bpm = 128.0, camelot = "8A"),
        track("adjacent", bpm = 128.0, camelot = "9A"),
        track("unanalysed")
    )

    // ── No anchor: an empty set must still be fillable ────────────────────────

    @Test
    fun `without an anchor every candidate is offered and nothing is scored`() {
        val result = MixDiscovery.of(anchor = null, candidates = discovery)

        assertTrue(result.ranked.isEmpty(), "nothing can be ranked without an anchor")
        assertEquals(listOf("same", "adjacent", "unanalysed"), result.unranked.map { it.track.id })
        assertTrue(result.unranked.all { it.affinity == null })
    }

    // ── Candidates that cannot be scored stay available ───────────────────────

    @Test
    fun `a candidate with no analysis is offered instead of dropped`() {
        val result = MixDiscovery.of(anchor, listOf(track("unanalysed")))

        assertTrue(result.ranked.isEmpty())
        assertEquals(listOf("unanalysed"), result.unranked.map { it.track.id })
    }

    @Test
    fun `a candidate outside the filters is offered instead of dropped`() {
        val result = MixDiscovery.of(anchor, listOf(track("clashing", bpm = 60.0, camelot = "3B")))

        assertTrue(result.ranked.isEmpty())
        assertEquals(listOf("clashing"), result.unranked.map { it.track.id })
    }

    // ── Scoreable candidates are ranked, and still offered ────────────────────

    @Test
    fun `candidates that fit are ranked by score and stay in the list`() {
        val result = MixDiscovery.of(
            anchor,
            listOf(track("adjacent", bpm = 128.0, camelot = "9A"), track("same", bpm = 128.0, camelot = "8A"))
        )

        assertEquals(listOf("same", "adjacent"), result.ranked.map { it.track.id })
        assertNotNull(result.ranked.first().affinity)
        assertEquals(listOf("same", "adjacent"), result.all.map { it.track.id }, "ranked rows are still offered")
    }

    @Test
    fun `ranked candidates come before the unranked ones`() {
        val result = MixDiscovery.of(anchor, listOf(track("unanalysed"), track("same", bpm = 128.0, camelot = "8A")))

        assertEquals(listOf("same", "unanalysed"), result.all.map { it.track.id })
        assertNull(result.all.last().affinity)
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    @Test
    fun `the anchor is never offered to itself`() {
        val result = MixDiscovery.of(anchor, listOf(anchor, track("same", bpm = 128.0, camelot = "8A")))

        assertEquals(listOf("same"), result.all.map { it.track.id })
    }

    @Test
    fun `the same candidate from two searches is offered once`() {
        val result = MixDiscovery.of(anchor = null, candidates = listOf(track("dup"), track("dup")))

        assertEquals(listOf("dup"), result.all.map { it.track.id })
    }

    @Test
    fun `an empty search offers nothing`() {
        val result = MixDiscovery.of(anchor, emptyList())

        assertTrue(result.isEmpty)
    }
}
