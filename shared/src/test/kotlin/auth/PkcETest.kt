package auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class PkcETest {

    @Test
    fun `code verifier should be base64url encoded`() {
        val verifier = generateCodeVerifier()
        assertNotNull(verifier)
        assertFalse(verifier.isEmpty())
        // Should be at least 43 chars (min length per OAuth spec)
        assertTrue(verifier.length >= 43)
        // Should be at most 125 chars (max length per OAuth spec)
        assertTrue(verifier.length <= 125)
        // Should only contain base64url characters (A-Z, a-z, 0-9, -, _)
        assertTrue(verifier.matches(Regex("^[A-Za-z0-9_-]+$")))
        // Should not contain padding characters
        assertFalse(verifier.contains("="))
    }

    @Test
    fun `code verifier should be different each call`() {
        val v1 = generateCodeVerifier()
        val v2 = generateCodeVerifier()
        assertNotEquals(v1, v2)
    }

    @Test
    fun `code challenge should be deterministic for same verifier`() {
        val verifier = generateCodeVerifier()
        val challenge1 = computeCodeChallenge(verifier)
        val challenge2 = computeCodeChallenge(verifier)
        assertEquals(challenge1, challenge2)
    }

    @Test
    fun `code challenge should be different for different verifiers`() {
        val verifier1 = generateCodeVerifier()
        val verifier2 = generateCodeVerifier()
        assertNotEquals(verifier1, verifier2)
        assertNotEquals(computeCodeChallenge(verifier1), computeCodeChallenge(verifier2))
    }

    @Test
    fun `code challenge should match SHA-256 of verifier`() {
        val verifier = generateCodeVerifier()
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.UTF_8))
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(expectedHash)
        val actualChallenge = computeCodeChallenge(verifier)
        assertEquals(expectedChallenge, actualChallenge)
    }

    @Test
    fun `buildAuthUrl should include code challenge and method`() {
        val state = buildAuthUrl("test-client-id")
        assertTrue(state.authUrl.contains("code_challenge="))
        assertTrue(state.authUrl.contains("code_challenge_method=S256"))
    }

    @Test
    fun `buildAuthUrl should include standard OAuth params`() {
        val state = buildAuthUrl("test-client-id")
        assertTrue(state.authUrl.contains("client_id=test-client-id"))
        assertTrue(state.authUrl.contains("response_type=code"))
        assertTrue(state.authUrl.contains("access_type=offline"))
        assertTrue(state.authUrl.contains("prompt=consent"))
    }

    @Test
    fun `auth state should contain both url and verifier`() {
        val state = buildAuthUrl("test-client-id")
        assertNotNull(state.authUrl)
        assertNotNull(state.codeVerifier)
        assertFalse(state.authUrl.isEmpty())
        assertFalse(state.codeVerifier.isEmpty())
    }
}
