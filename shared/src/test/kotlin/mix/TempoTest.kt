package mix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TempoTest {

    @Test
    fun `a tempo near the anchor is left alone`() {
        assertEquals(130.0, Tempo.fold(anchorBpm = 128.0, candidateBpm = 130.0))
        assertEquals(124.0, Tempo.fold(anchorBpm = 128.0, candidateBpm = 124.0))
    }

    @Test
    fun `a half-time tempo folds up to the anchor octave`() {
        assertEquals(128.0, Tempo.fold(anchorBpm = 128.0, candidateBpm = 64.0))
    }

    @Test
    fun `a double-time tempo folds down to the anchor octave`() {
        assertEquals(87.0, Tempo.fold(anchorBpm = 87.0, candidateBpm = 174.0))
    }

    @Test
    fun `a tempo a full octave away folds to the nearest side`() {
        assertEquals(140.0, Tempo.fold(anchorBpm = 140.0, candidateBpm = 70.0))
        assertEquals(70.0, Tempo.fold(anchorBpm = 70.0, candidateBpm = 140.0))
    }

    @Test
    fun `a missing tempo stays missing instead of folding to zero`() {
        assertEquals(0.0, Tempo.fold(anchorBpm = 128.0, candidateBpm = 0.0))
        assertEquals(0.0, Tempo.fold(anchorBpm = 0.0, candidateBpm = 128.0))
    }

    @Test
    fun `the folded tempo always lands within half to double the anchor`() {
        val anchor = 122.0
        for (candidate in listOf(58.0, 71.0, 90.0, 128.0, 160.0, 175.0, 240.0)) {
            val folded = Tempo.fold(anchor, candidate)
            val ok = folded in (anchor / 2.0)..(anchor * 2.0)
            assertEquals(true, ok, "folded $candidate to $folded against $anchor")
        }
    }
}
