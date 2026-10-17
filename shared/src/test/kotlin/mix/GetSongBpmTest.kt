package mix

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GetSongBpmTest {

    @Test
    fun `a full answer gives tempo and wheel slot`() {
        val answer = GetSongBpmProvider.parseSearch(
            """
            {"search":[{"id":"abc","title":"Strobe","artist":{"name":"deadmau5"},
              "tempo":"128","key_of":"8A","open_key":8,"key":"Am"}]}
            """.trimIndent()
        )

        assertEquals(128.0, answer?.bpm)
        assertEquals(CamelotKey(8, "A"), answer?.camelot)
        assertEquals("getsongbpm", answer?.provider)
    }

    @Test
    fun `a musical key without a wheel slot is still usable`() {
        val answer = GetSongBpmProvider.parseSearch(
            """{"search":[{"title":"x","artist":{"name":"y"},"tempo":"124","key":"C#m"}]}"""
        )

        assertEquals(CamelotKey(12, "A"), answer?.camelot)
        assertEquals(124.0, answer?.bpm)
    }

    @Test
    fun `a key without tempo still carries the key`() {
        val answer = GetSongBpmProvider.parseSearch(
            """{"search":[{"title":"x","artist":{"name":"y"},"key_of":"5B"}]}"""
        )

        assertNull(answer?.bpm)
        assertEquals(CamelotKey(5, "B"), answer?.camelot)
    }

    @Test
    fun `no hits and unreadable bodies are unknown rather than an error`() {
        assertNull(GetSongBpmProvider.parseSearch("""{"search":[]}"""))
        assertNull(GetSongBpmProvider.parseSearch("""{"search":[{"title":"x","artist":{"name":"y"}}]}"""))
        assertNull(GetSongBpmProvider.parseSearch("not json at all"))
        assertNull(GetSongBpmProvider.parseSearch(""))
    }

    @Test
    fun `a provider without a key is not configured and never calls out`() {
        var calls = 0
        val provider = GetSongBpmProvider(apiKey = null, fetch = { calls++; "{}" })

        assertFalse(provider.isConfigured)
        assertNull(provider.lookup("deadmau5", "Strobe"))
        assertEquals(0, calls)
    }

    @Test
    fun `a configured provider asks for song and artist and parses the answer`() {
        var requested: String? = null
        val provider = GetSongBpmProvider(
            apiKey = "test-key",
            fetch = { url ->
                requested = url
                """{"search":[{"tempo":"174","key_of":"9B"}]}"""
            }
        )

        val answer = provider.lookup("Noisia", "Stigma")

        assertTrue(provider.isConfigured)
        assertEquals(174.0, answer?.bpm)
        assertEquals(CamelotKey(9, "B"), answer?.camelot)
        val url = requested!!
        assertTrue(url.contains("api_key=test-key"), url)
        assertTrue(url.contains("song:Stigma"), url)
        assertTrue(url.contains("artist:Noisia"), url)
    }

    @Test
    fun `a failed request is unknown, not an exception`() {
        val provider = GetSongBpmProvider(apiKey = "test-key", fetch = { null })
        assertNull(provider.lookup("deadmau5", "Strobe"))
    }

    @Test
    fun `a title with spaces and accents survives into the query`() {
        var requested = ""
        val provider = GetSongBpmProvider(apiKey = "k", fetch = { url -> requested = url; null })

        provider.lookup("Björk", "Jóga (Live)")

        assertTrue(requested.contains("song:J%C3%B3ga"), requested)
        assertTrue(requested.contains("artist:Bj%C3%B6rk"), requested)
        assertFalse(requested.contains(" "), "spaces must be encoded: $requested")
    }
}
