package auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SoundCloudAuthTest {

    private val validMeJson = """
        {
            "id": 12345678,
            "username": "nxssie",
            "avatar_url": "https://i1.sndcdn.com/u-00000-large.jpg",
            "permalink_url": "https://soundcloud.com/nxssie",
            "kind": "user"
        }
    """.trimIndent()

    private val minimalMeJson = """
        {
            "id": 99,
            "username": "testuser"
        }
    """.trimIndent()

    @Test
    fun `should parse valid me response`() {
        val result = SoundCloudAuth.parseMe(validMeJson)!!
        assertEquals(12345678L, result.id)
        assertEquals("nxssie", result.username)
        assertEquals("https://i1.sndcdn.com/u-00000-large.jpg", result.avatarUrl)
        assertEquals("https://soundcloud.com/nxssie", result.permalink)
    }

    @Test
    fun `should parse minimal me response`() {
        val result = SoundCloudAuth.parseMe(minimalMeJson)!!
        assertEquals(99L, result.id)
        assertEquals("testuser", result.username)
        assertNull(result.avatarUrl)
        assertNull(result.permalink)
    }

    @Test
    fun `should return null for invalid JSON`() {
        assertNull(SoundCloudAuth.parseMe("not json"))
    }

    @Test
    fun `should return null for missing required fields`() {
        assertNull(SoundCloudAuth.parseMe("""{"avatar_url": "x"}"""))
    }
}
