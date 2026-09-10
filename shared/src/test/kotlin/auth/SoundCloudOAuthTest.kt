package auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class SoundCloudOAuthTest {

    private val request = SoundCloudOAuth.buildAuthRequest("abcdefghijklmnopqrstuvwxyz012345")

    private fun state(nonce: String) =
        Base64.getEncoder().encodeToString("""{"client_id":"x","nonce":"$nonce"}""".toByteArray())

    @Test
    fun `auth url carries pkce, state and the web player redirect`() {
        assertTrue(request.url.startsWith("https://secure.soundcloud.com/web-auth?"))
        assertTrue(request.url.contains("client_id=abcdefghijklmnopqrstuvwxyz012345"))
        assertTrue(request.url.contains("code_challenge_method=S256"))
        assertTrue(request.url.contains("code_challenge=${computeCodeChallenge(request.codeVerifier)}"))
        assertTrue(request.url.contains("redirect_uri=https%3A%2F%2Fsoundcloud.com%2Fsignin%2Fcallback"))
        assertTrue(request.url.contains("state="))
    }

    @Test
    fun `parseCallback returns the code when the nonce matches`() {
        val url = "${SoundCloudOAuth.REDIRECT_URI}?code=the-code&state=${state(request.nonce)}"
        assertEquals("the-code", SoundCloudOAuth.parseCallback(url, request))
    }

    @Test
    fun `parseCallback rejects a foreign nonce`() {
        val url = "${SoundCloudOAuth.REDIRECT_URI}?code=the-code&state=${state("other")}"
        assertThrows<IllegalStateException> { SoundCloudOAuth.parseCallback(url, request) }
    }

    @Test
    fun `parseCallback surfaces provider errors`() {
        val url = "${SoundCloudOAuth.REDIRECT_URI}?error=access_denied&state=${state(request.nonce)}"
        val e = assertThrows<IllegalStateException> { SoundCloudOAuth.parseCallback(url, request) }
        assertTrue(e.message!!.contains("access_denied"))
    }

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
}
