package auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Only the token shape is still ours to parse: the authorization flow is SoundCloud's page's
 * business now, and what reaches us from it is a session, not a code.
 */
class SoundCloudOAuthTest {

    @Test
    fun `parseTokens reads access, refresh and expiry`() {
        val t = SoundCloudOAuth.parseTokens("""{"access_token":"a","refresh_token":"r","expires_in":3600,"scope":"*"}""")
        assertEquals("a", t.accessToken)
        assertEquals("r", t.refreshToken)
        assertNotNull(t.expiresAt)
        assertTrue(t.expiresAt!! > System.currentTimeMillis() / 1000)
    }

    @Test
    fun `parseTokens tolerates a bare access token`() {
        val t = SoundCloudOAuth.parseTokens("""{"access_token":"a"}""")
        assertNull(t.refreshToken)
        assertNull(t.expiresAt)
    }

    @Test
    fun `a response without a token is not a session`() {
        assertThrows<IllegalStateException> { SoundCloudOAuth.parseTokens("""{"scope":"*"}""") }
    }
}
