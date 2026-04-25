package api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class ListenerCountParserTest {

    companion object {
        @JvmStatic
        fun parseCases(): Stream<Arguments> = Stream.of(
            Arguments.of("750 K", 750000L),
            Arguments.of("750 K usuarios mensuales", 750000L),
            Arguments.of("4.03 M", 4030000L),
            Arguments.of("4.03 M usuarios mensuales", 4030000L),
            Arguments.of("945", 945L),
            Arguments.of("945 suscriptores", 945L),
            Arguments.of("1.2 M", 1200000L),
            Arguments.of("2.5 K", 2500L),
            Arguments.of("1000", 1000L),
            Arguments.of("1.5k", 1500L),
            Arguments.of("2M", 2000000L),
            Arguments.of("1M", 1000000L)
        )
    }

    @ParameterizedTest
    @MethodSource("parseCases")
    fun `should parse listener count from various formats`(input: String, expected: Long) {
        assertEquals(expected, parseListenerCount(input))
    }

    @Test
    fun `should parse European comma format`() {
        assertEquals(4030000L, parseListenerCount("4,03 M"))
        assertEquals(4030000L, parseListenerCount("4,03 M usuarios mensuales"))
    }

    @Test
    fun `should parse European comma format with thousands separator`() {
        assertEquals(4030000L, parseListenerCount("4,03M"))
        assertEquals(1200000L, parseListenerCount("1,2M"))
    }

    @Test
    fun `should return null for unrecognised format`() {
        assertNull(parseListenerCount("???"))
    }

    @Test
    fun `should handle empty string`() {
        assertNull(parseListenerCount(""))
    }
}
