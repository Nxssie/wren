package api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DurationParserTest {

    @ParameterizedTest
    @ValueSource(strings = [
        "PT0S", "PT5S", "PT30S", "PT1M0S", "PT3M4S",
        "PT15M30S", "PT1H0M0S", "PT1H15M30S", "PT2H30M"
    ])
    fun `should parse ISO 8601 durations without zero padding`(iso: String) {
        val result = parseIsoDuration(iso)
        assertDoesNotThrow {
            // Verify format: either MM:SS or HH:MM:SS
            result.toIntOrNull()
        }
        assertTrue(result.matches(Regex("^\\d+(:\\d{2}){1,2}$")))
    }

    @Test
    fun `should parse full duration with hours`() {
        assertEquals("1:15:30", parseIsoDuration("PT1H15M30S"))
    }

    @Test
    fun `should parse minutes and seconds`() {
        assertEquals("15:30", parseIsoDuration("PT15M30S"))
    }

    @Test
    fun `should parse minutes only`() {
        assertEquals("3:04", parseIsoDuration("PT3M4S"))
    }

    @Test
    fun `should handle empty string`() {
        assertEquals("0:00", parseIsoDuration(""))
    }

    @Test
    fun `should handle unknown format`() {
        assertEquals("0:00", parseIsoDuration("unknown"))
    }
}
