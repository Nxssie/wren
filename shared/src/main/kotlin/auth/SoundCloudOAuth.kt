package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import util.Http
import util.Log
import java.net.URLEncoder

/**
 * The token endpoint, for the sessions the sign-in page hands us.
 *
 * The app no longer drives an authorization flow of its own — see [SoundCloudWebSignIn], which
 * reads the token SoundCloud's own page mints — so what is left here is the refresh and the shape
 * both paths store.
 *
 * [refresh] is unreachable while nothing stores a refresh token: the page keeps the session in a
 * cookie, which carries no expiry to renew. It stays because that is the intended path the moment
 * one is recoverable, and because the alternative is the access token expiring into a re-login.
 */
object SoundCloudOAuth {
    private const val TAG = "SoundCloudOAuth"
    private const val TOKEN_URL = "https://secure.soundcloud.com/oauth/token"

    private val json = Json { ignoreUnknownKeys = true }

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long?,
        /** Who issued it: a refresh has to come from the same client as the grant. */
        val clientId: String? = null
    )

    suspend fun refresh(refreshToken: String, clientId: String?): Tokens = withContext(Dispatchers.IO) {
        postToken(
            "grant_type=refresh_token" +
                "&refresh_token=${encode(refreshToken)}" +
                "&client_id=${encode(clientId.orEmpty())}"
        )
    }

    private fun postToken(body: String): Tokens {
        val resp = Http.post(
            "$TOKEN_URL?grant_type=refresh_token",
            body,
            contentType = "application/x-www-form-urlencoded; charset=UTF-8",
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to "https://soundcloud.com",
                "Referer" to "https://soundcloud.com/",
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            ),
        )
        if (!resp.isSuccessful) {
            Log.w(TAG, "token endpoint returned ${resp.code}: ${resp.body.take(300)}")
            throw IllegalStateException("SoundCloud token exchange failed (${resp.code})")
        }
        return parseTokens(resp.body)
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
}
