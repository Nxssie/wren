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
    fun `the script reads the key the web player stores`() {
        assertTrue(SoundCloudWebSignIn.TOKEN_SCRIPT.contains(SoundCloudWebSignIn.TOKEN_KEY))
    }
}
