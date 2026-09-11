package auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The two shapes the token comes back in: Android's `evaluateJavascript` JSON-encodes what it
 * returns, JavaFX's `executeScript` does not, and the cookie jar needs digging through.
 */
class SoundCloudWebSignInTest {

    @Test
    fun `android hands back a json-encoded string`() {
        assertEquals("1-abcDEF_123", SoundCloudWebSignIn.tokenFromJsResult("\"1-abcDEF_123\""))
    }

    @Test
    fun `javafx hands back the bare string`() {
        assertEquals("1-abcDEF_123", SoundCloudWebSignIn.tokenFromJsResult("1-abcDEF_123"))
    }

    @Test
    fun `a missing token is not a token`() {
        assertNull(SoundCloudWebSignIn.tokenFromJsResult("null"))
        assertNull(SoundCloudWebSignIn.tokenFromJsResult(null))
        assertNull(SoundCloudWebSignIn.tokenFromJsResult(""))
        assertNull(SoundCloudWebSignIn.tokenFromJsResult("   "))
        assertNull(SoundCloudWebSignIn.tokenFromJsResult("\"\""))
    }

    @Test
    fun `the token is found among other cookies`() {
        val jar = "datadome=abc; oauth_token=1-xyz; _ga=GA1.2"
        assertEquals("1-xyz", SoundCloudWebSignIn.tokenFromCookies(jar))
    }

    @Test
    fun `a jar without the token yields nothing`() {
        assertNull(SoundCloudWebSignIn.tokenFromCookies("datadome=abc; _ga=GA1.2"))
        assertNull(SoundCloudWebSignIn.tokenFromCookies(null))
        assertNull(SoundCloudWebSignIn.tokenFromCookies("oauth_token=; _ga=1"))
    }

    @Test
    fun `neither a prefixed nor a suffixed name is mistaken for the token`() {
        assertNull(SoundCloudWebSignIn.tokenFromCookies("xoauth_token=a; oauth_token_expires=b"))
    }

    @Test
    fun `the refresh token is read by its own name`() {
        val jar = "oauth_token=access; oauth_refresh_token=refresh; datadome=x"

        assertEquals("access", SoundCloudWebSignIn.tokenFromCookies(jar))
        assertEquals("refresh", SoundCloudWebSignIn.tokenFromCookies(jar, SoundCloudWebSignIn.REFRESH_TOKEN_KEY))
    }

    @Test
    fun `the client that issued the session is read off the authorize url`() {
        val url = "https://secure.soundcloud.com/web-auth?redirect_uri=x%3A%2F%2Fy&code_challenge=z" +
            "&client_id=KKzJxmw11tYpCs6T24P4uUYhqmjalG6M&device_id=1&app_id=65097"

        assertEquals("KKzJxmw11tYpCs6T24P4uUYhqmjalG6M", SoundCloudWebSignIn.clientIdFromAuthUrl(url))
    }

    @Test
    fun `an id that is not a client id is not mistaken for one`() {
        assertNull(SoundCloudWebSignIn.clientIdFromAuthUrl("https://soundcloud.com/signin"))
        assertNull(SoundCloudWebSignIn.clientIdFromAuthUrl("https://x/?client_id=short"))
        assertNull(SoundCloudWebSignIn.clientIdFromAuthUrl(null))
    }

    @Test
    fun `the script reads the key the web player stores`() {
        assertTrue(SoundCloudWebSignIn.TOKEN_SCRIPT.contains(SoundCloudWebSignIn.TOKEN_KEY))
    }
}
