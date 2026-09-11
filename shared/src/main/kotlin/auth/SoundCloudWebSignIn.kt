package auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Signing in the way the web player does, without SoundCloud's authorization endpoint being
 * involved: the user signs in on the ordinary page and the app reads the token that page stores
 * for itself.
 *
 * The PKCE flow in [SoundCloudOAuth] drives `secure.soundcloud.com/web-auth` with the web
 * player's client id and its registered redirect, and that page is the one DataDome guards — it
 * exists to be used by SoundCloud's own clients, which reach it from a registered one. Signing in
 * normally needs no redirect interception, so there is nothing between the user and their account
 * beyond the page they already know.
 *
 * The token is the same string either way, and it is validated against `/me` before it is stored.
 */
object SoundCloudWebSignIn {

    /** The ordinary sign-in page, not the authorization page. */
    const val SIGN_IN_URL = "https://soundcloud.com/signin"

    /** The origin whose storage holds the token; also the cookie fallback's origin. */
    const val ORIGIN = "https://soundcloud.com"

    /** The key the web player stores its own token under. */
    const val TOKEN_KEY = "oauth_token"

    /**
     * And the key it stores the refresh token under. SoundCloud's own bundle names it:
     * `REFRESH_TOKEN_COOKIE_NAME = "oauth_refresh_token"`, set right after the token exchange.
     */
    const val REFRESH_TOKEN_KEY = "oauth_refresh_token"

    /** Reads the token out of the page's own storage; evaluated in the WebView. */
    val TOKEN_SCRIPT: String = "window.localStorage.getItem('$TOKEN_KEY')"

    /** What the page left behind: the session, the key that renews it, and who issued it. */
    data class Session(val accessToken: String, val refreshToken: String?, val clientId: String?)

    /**
     * The client id that issued the session, read off the authorize URL the page navigates to.
     * A refresh has to be presented by the client that got the grant, and SoundCloud's mobile
     * flow uses a different id from the one the api-v2 scrape returns.
     */
    fun clientIdFromAuthUrl(url: String?): String? = url
        ?.substringAfter("client_id=", "")
        ?.substringBefore('&')
        ?.substringBefore('#')
        ?.takeIf { it.length == 32 && it.all(Char::isLetterOrDigit) }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Unwraps what a WebView hands back from [TOKEN_SCRIPT]. Android's `evaluateJavascript`
     * returns a JSON-encoded value (`"abc"`, or the literal `null`) while JavaFX's
     * `executeScript` returns the string itself, so both shapes are accepted.
     */
    fun tokenFromJsResult(raw: Any?): String? {
        val value = (raw as? String)?.trim().orEmpty()
        if (value.isEmpty() || value == "null") return null
        val decoded = runCatching { json.parseToJsonElement(value).jsonPrimitive.contentOrNull }.getOrNull()
        return (decoded ?: value.removeSurrounding("\"")).takeIf { it.isNotBlank() }
    }

    /**
     * One cookie out of a jar, for the times the token is served as a cookie rather than kept in
     * storage. Give it the raw `Cookie` header for [ORIGIN].
     */
    fun tokenFromCookies(cookies: String?, name: String = TOKEN_KEY): String? = cookies
        ?.split(';')
        ?.mapNotNull { pair ->
            val cookieName = pair.substringBefore('=', "").trim()
            if (cookieName != name) null else pair.substringAfter('=', "").trim().ifBlank { null }
        }
        ?.firstOrNull()
}
