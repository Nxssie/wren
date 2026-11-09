package mix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CamelotKeyTest {

    // ── Parsing Camelot notation ──────────────────────────────────────────────

    @Test
    fun `camelot notation parses as written`() {
        assertEquals(CamelotKey(8, "A"), CamelotKey.parse("8A"))
        assertEquals(CamelotKey(11, "B"), CamelotKey.parse("11B"))
    }

    @Test
    fun `camelot notation tolerates case and surrounding space`() {
        assertEquals(CamelotKey(8, "A"), CamelotKey.parse("  8a "))
    }

    @Test
    fun `camelot notation rejects numbers outside the wheel`() {
        assertNull(CamelotKey.parse("13A"))
        assertNull(CamelotKey.parse("0B"))
    }

    // ── Parsing musical notation ──────────────────────────────────────────────

    @Test
    fun `minor keys map to the A side of the wheel`() {
        assertEquals(CamelotKey(8, "A"), CamelotKey.parse("Am"))
        assertEquals(CamelotKey(8, "A"), CamelotKey.parse("a min"))
        assertEquals(CamelotKey(4, "A"), CamelotKey.parse("Fm"))
    }

    @Test
    fun `major keys map to the B side of the wheel`() {
        assertEquals(CamelotKey(8, "B"), CamelotKey.parse("C"))
        assertEquals(CamelotKey(8, "B"), CamelotKey.parse("Cmaj"))
        assertEquals(CamelotKey(9, "B"), CamelotKey.parse("G major"))
    }

    @Test
    fun `sharp and flat spellings land on the same slot`() {
        assertEquals(CamelotKey(11, "A"), CamelotKey.parse("F#m"))
        assertEquals(CamelotKey(11, "A"), CamelotKey.parse("Gbm"))
        assertEquals(CamelotKey(1, "A"), CamelotKey.parse("A♭m"))
        assertEquals(CamelotKey(1, "A"), CamelotKey.parse("G#m"))
        assertEquals(CamelotKey(2, "B"), CamelotKey.parse("F#"))
    }

    @Test
    fun `unreadable notation is unknown rather than a guess`() {
        assertNull(CamelotKey.parse(null))
        assertNull(CamelotKey.parse(""))
        assertNull(CamelotKey.parse("H"))
        assertNull(CamelotKey.parse("Am7"))
        assertNull(CamelotKey.parse("unknown"))
    }

    // ── Harmonic relation ────────────────────────────────────────────────────

    @Test
    fun `the same key is the same key`() {
        assertEquals(HarmonicRelation.SAME, CamelotKey(8, "A").relationTo(CamelotKey(8, "A")))
    }

    @Test
    fun `the other side of the same number is the relative key`() {
        assertEquals(HarmonicRelation.RELATIVE, CamelotKey(8, "A").relationTo(CamelotKey(8, "B")))
        assertEquals(HarmonicRelation.RELATIVE, CamelotKey(8, "B").relationTo(CamelotKey(8, "A")))
    }

    @Test
    fun `one step around the wheel on the same side is adjacent`() {
        assertEquals(HarmonicRelation.ADJACENT, CamelotKey(8, "A").relationTo(CamelotKey(9, "A")))
        assertEquals(HarmonicRelation.ADJACENT, CamelotKey(8, "A").relationTo(CamelotKey(7, "A")))
    }

    @Test
    fun `the wheel wraps at twelve`() {
        assertEquals(HarmonicRelation.ADJACENT, CamelotKey(12, "A").relationTo(CamelotKey(1, "A")))
        assertEquals(HarmonicRelation.ADJACENT, CamelotKey(1, "B").relationTo(CamelotKey(12, "B")))
    }

    @Test
    fun `anything further away clashes`() {
        assertEquals(HarmonicRelation.NONE, CamelotKey(8, "A").relationTo(CamelotKey(3, "A")))
        assertEquals(HarmonicRelation.NONE, CamelotKey(8, "A").relationTo(CamelotKey(9, "B")))
    }

    @Test
    fun `every stored key is a valid slot`() {
        for (number in 1..12) for (letter in listOf("A", "B")) {
            val key = CamelotKey(number, letter)
            assertEquals("$number$letter", key.toString())
            assertEquals(HarmonicRelation.SAME, key.relationTo(CamelotKey.parse(key.toString())!!))
        }
    }
}
