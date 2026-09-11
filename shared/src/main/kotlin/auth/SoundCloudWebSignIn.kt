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

    /** Reads the token out of the page's own storage; evaluated in the WebView. */
    val TOKEN_SCRIPT: String = "window.localStorage.getItem('$TOKEN_KEY')"

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
     * The cookie jar as a fallback, for the times the token is served as a cookie rather than
     * kept in storage. Give it the raw `Cookie` header for [ORIGIN].
     */
    fun tokenFromCookies(cookies: String?): String? = cookies
        ?.split(';')
        ?.mapNotNull { pair ->
            val name = pair.substringBefore('=', "").trim()
            if (name != TOKEN_KEY) null else pair.substringAfter('=', "").trim().ifBlank { null }
        }
        ?.firstOrNull()
}
