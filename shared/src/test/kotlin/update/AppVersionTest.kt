package update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AppVersionTest {

    private val parseable = listOf(
        "0.6.0",
        "v0.6.0",
        "0.6.0-12-gabc1234",
        "0.6.0-dirty",
        "0.6.0-12-gabc1234-dirty",
        "0.6.0-alpha",
        "0.6.0-beta",
        "0.6.1",
        "0.7",
        "0.7.0",
    )

    @Test
    fun `parses a plain release tag`() {
        assertEquals(AppVersion(0, 6, 0, null, null), AppVersion.parse("0.6.0"))
    }

    @Test
    fun `tolerates a leading v`() {
        assertEquals(AppVersion(0, 6, 0, null, null), AppVersion.parse("v0.6.0"))
        assertEquals(AppVersion(0, 6, 0, null, null), AppVersion.parse("V0.6.0"))
    }

    @Test
    fun `treats a missing patch as zero`() {
        assertEquals(AppVersion(0, 7, 0, null, null), AppVersion.parse("0.7"))
        assertEquals(AppVersion.parse("0.7"), AppVersion.parse("0.7.0"))
    }

    @Test
    fun `keeps the describe suffix and the commit count`() {
        assertEquals(AppVersion(0, 6, 0, "12-gabc1234", 12), AppVersion.parse("0.6.0-12-gabc1234"))
    }

    @Test
    fun `parses a bare dirty suffix`() {
        assertEquals(AppVersion(0, 6, 0, "dirty", null), AppVersion.parse("0.6.0-dirty"))
    }

    @Test
    fun `parses a dirty suffix after the commit count`() {
        assertEquals(AppVersion(0, 6, 0, "12-gabc1234-dirty", 12), AppVersion.parse("0.6.0-12-gabc1234-dirty"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "dirty", "v", "1.2.x", "latest", "1.2.3.4"])
    fun `returns null for unparseable input`(raw: String) {
        assertNull(AppVersion.parse(raw))
    }

    @Test
    fun `returns null for a null input`() {
        assertNull(AppVersion.parse(null))
    }

    @Test
    fun `a describe suffix outranks its own tag`() {
        // versionCode counts commits, so a build past the tag is ahead of it, not a pre-release.
        assertTrue(AppVersion.parse("0.6.0-12-gabc1234")!!.isNewerThan(AppVersion.parse("0.6.0")))
        assertTrue(AppVersion.parse("0.6.0-dirty")!!.isNewerThan(AppVersion.parse("0.6.0")))
    }

    @Test
    fun `compares suffixed versions by commit count`() {
        assertTrue(AppVersion.parse("0.6.0-12-gabc1234")!! > AppVersion.parse("0.6.0-5-gdef5678")!!)
    }

    @Test
    fun `falls back to the suffix text without a commit count`() {
        assertTrue(AppVersion.parse("0.6.0-beta")!! > AppVersion.parse("0.6.0-alpha")!!)
    }

    @Test
    fun `a newer release outranks an older one`() {
        assertTrue(AppVersion.parse("0.7.0")!! > AppVersion.parse("0.6.0")!!)
        assertTrue(AppVersion.parse("0.7.0")!! > AppVersion.parse("0.6.12")!!)
    }

    @Test
    fun `identical strings compare equal`() {
        assertEquals(0, AppVersion.parse("0.6.0")!!.compareTo(AppVersion.parse("0.6.0")!!))
        assertEquals(AppVersion.parse("v0.6.0"), AppVersion.parse("0.6.0"))
        assertEquals(AppVersion.parse("0.7"), AppVersion.parse("0.7.0"))
    }

    @Test
    fun `comparison is consistent with equality and never throws`() {
        val versions = parseable.map { AppVersion.parse(it)!! }

        for (left in versions) {
            for (right in versions) {
                assertEquals(left == right, left.compareTo(right) == 0)
                assertFalse(left.isNewerThan(right) && right.isNewerThan(left))
            }
        }
    }

    @Test
    fun `reports a strictly newer version`() {
        assertTrue(AppVersion.parse("0.7.0")!!.isNewerThan(AppVersion.parse("0.6.0")!!))
        assertFalse(AppVersion.parse("0.6.0")!!.isNewerThan(AppVersion.parse("0.6.0")!!))
        assertFalse(AppVersion.parse("0.6.0")!!.isNewerThan(AppVersion.parse("0.7.0")!!))
    }

    @Test
    fun `an unknown installed version never triggers an update`() {
        assertFalse(AppVersion.parse("0.6.0")!!.isNewerThan(null))
    }
}
