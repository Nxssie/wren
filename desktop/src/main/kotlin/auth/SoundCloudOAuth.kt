package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import util.Log
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Native replica of the SoundCloud web player's sign-in: an OAuth 2 authorization-code
 * flow with PKCE against `secure.soundcloud.com`, using the web player's public client id
 * (scraped like the rest of the api-v2 calls) and its registered redirect URI. The
 * resulting token is exactly what the web player stores, so api-v2 accepts it.
 *
 * Only the *authorization page* runs in the embedded browser; the code exchange happens
 * here, so the full soundcloud.com web app is never loaded.
 */
object SoundCloudOAuth {
    private const val TAG = "SoundCloudOAuth"
    private const val AUTH_HOST = "https://secure.soundcloud.com"
    private const val TOKEN_URL = "$AUTH_HOST/oauth/token"
    /** Registered redirect of the web player; we intercept navigation to it instead of serving it. */
    const val REDIRECT_URI = "https://soundcloud.com/signin/callback"
    /** The web player's application id, sent as `app_id` so the auth UI renders the sign-in view. */
    private const val WEB_APP_ID = 46941

    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    data class AuthRequest(
        val url: String,
        val clientId: String,
        val codeVerifier: String,
        val nonce: String
    )

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long?
    )

    fun buildAuthRequest(clientId: String): AuthRequest {
        val verifier = generateCodeVerifier()
        val challenge = computeCodeChallenge(verifier)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val state = base64Url("""{"client_id":"$clientId","nonce":"$nonce"}""")
        val params = listOf(
            "client_id" to clientId,
            "device_id" to UUID.randomUUID().toString().replace("-", ""),
            "theme" to "dark",
            "ui_evo" to "true",
            "app_id" to WEB_APP_ID.toString(),
            "tracking" to "local",
            "redirect_uri" to REDIRECT_URI,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256"
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return AuthRequest("$AUTH_HOST/web-auth?$params#start_view=sign_in", clientId, verifier, nonce)
    }

    /**
     * Parses the intercepted callback URL. Returns the code, or throws when SoundCloud
     * reported an error or the state does not carry our nonce (CSRF / mixed-up attempt).
     */
    fun parseCallback(url: String, request: AuthRequest): String {
        val query = URI.create(url).rawQuery ?: throw IllegalStateException("Callback without parameters")
        val params = query.split("&").associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
        }
        params["error"]?.let { throw IllegalStateException("SoundCloud returned: $it") }
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("No authorization code in callback")
        val state = params["state"] ?: throw IllegalStateException("Missing state in callback")
        val nonce = runCatching {
            json.parseToJsonElement(String(Base64.getDecoder().decode(state))).jsonObject["nonce"]?.jsonPrimitive?.content
        }.getOrNull()
        if (nonce != request.nonce) throw IllegalStateException("State mismatch — please try signing in again")
        return code
    }

    suspend fun exchangeCode(code: String, request: AuthRequest): Tokens = withContext(Dispatchers.IO) {
        postToken(
            "grant_type=authorization_code" +
                "&code=${encode(code)}" +
                "&code_verifier=${encode(request.codeVerifier)}" +
                "&redirect_uri=${encode(REDIRECT_URI)}" +
                "&client_id=${encode(request.clientId)}",
            "?grant_type=authorization_code"
        )
    }

    suspend fun refresh(refreshToken: String, clientId: String): Tokens = withContext(Dispatchers.IO) {
        postToken(
            "grant_type=refresh_token" +
                "&refresh_token=${encode(refreshToken)}" +
                "&client_id=${encode(clientId)}",
            "?grant_type=refresh_token"
        )
    }

    private fun postToken(body: String, query: String): Tokens {
        val req = HttpRequest.newBuilder(URI.create(TOKEN_URL + query))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Accept", "application/json")
            .header("Origin", "https://soundcloud.com")
            .header("Referer", "https://soundcloud.com/")
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            Log.w(TAG, "token endpoint returned ${resp.statusCode()}: ${resp.body().take(300)}")
            throw IllegalStateException("SoundCloud token exchange failed (${resp.statusCode()})")
        }
        return parseTokens(resp.body())
    }

    internal fun parseTokens(body: String): Tokens {
        val obj = json.parseToJsonElement(body).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Token response without access_token")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull
        return Tokens(
            accessToken = access,
            refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
            expiresAt = expiresIn?.let { System.currentTimeMillis() / 1000 + it }
        )
    }

    private fun encode(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun base64Url(v: String) = Base64.getEncoder().encodeToString(v.toByteArray())
}
