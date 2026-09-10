package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.*
import util.AppDirs
import util.Http
import util.Log
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.Random

private val json = Json { ignoreUnknownKeys = true }

private const val SCOPE = "https://www.googleapis.com/auth/youtube"
// Google "Desktop app" clients accept any loopback port, so each attempt binds an
// ephemeral one and puts it in the redirect URI. A fixed port broke with
// "Address already in use" whenever a previous attempt was still waiting.
private const val DEFAULT_REDIRECT_PORT = 8765
private const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000
private fun redirectUri(port: Int) = "http://localhost:$port"

/** The listener of the attempt in flight, closed when a new attempt starts (single-flight). */
private var pendingCallback: ServerSocket? = null
private val pendingLock = Any()

private val credentialsFile get() = java.io.File(AppDirs.config, "oauth.json")

data class OAuthCredentials(val clientId: String, val clientSecret: String)

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

data class AuthState(
    val authUrl: String,
    val codeVerifier: String,
    val redirectPort: Int = DEFAULT_REDIRECT_PORT
)

private val random = Random()

fun generateCodeVerifier(): String {
    val bytes = ByteArray(64)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun computeCodeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(verifier.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
}

fun buildAuthUrl(clientId: String, redirectPort: Int = DEFAULT_REDIRECT_PORT): AuthState {
    val codeVerifier = generateCodeVerifier()
    val codeChallenge = computeCodeChallenge(codeVerifier)
    val url =
        "https://accounts.google.com/o/oauth2/v2/auth" +
        "?client_id=$clientId" +
        "&redirect_uri=${encode(redirectUri(redirectPort))}" +
        "&response_type=code" +
        "&scope=${encode(SCOPE)}" +
        "&access_type=offline" +
        "&prompt=consent" +
        "&code_challenge=${encode(codeChallenge)}" +
        "&code_challenge_method=S256"
    return AuthState(url, codeVerifier, redirectPort)
}

/**
 * OAuth client to use, in priority order:
 *  1. `~/.config/wren/oauth.json` — user-supplied override (own Cloud project)
 *  2. The client bundled at build time (see `generateBuildConfig` in build.gradle.kts)
 * Returns null when neither is available, i.e. a source build without env vars.
 */
fun loadCredentials(): OAuthCredentials? = loadCredentialsFile() ?: bundledCredentials()

/** True when Google login is possible at all (bundled or user-provided client). */
val hasGoogleCredentials: Boolean get() = loadCredentials() != null

private fun bundledCredentials(): OAuthCredentials? =
    OAuthCredentials(OAuthConfig.clientId, OAuthConfig.clientSecret)
        .takeIf { it.clientId.isNotBlank() && it.clientSecret.isNotBlank() }

private fun loadCredentialsFile(): OAuthCredentials? = runCatching {
    if (!credentialsFile.exists()) return null
    val root = json.parseToJsonElement(credentialsFile.readText()).jsonObject
    val obj = root["installed"]?.jsonObject ?: root["web"]?.jsonObject ?: root
    OAuthCredentials(
        clientId = obj["client_id"]?.jsonPrimitive?.content ?: return null,
        clientSecret = obj["client_secret"]?.jsonPrimitive?.content ?: return null
    )
}.getOrNull()

/**
 * Full Google sign-in: bind the loopback listener first (ephemeral port), build the
 * auth URL for that port, hand it to [openBrowser], wait for the redirect, exchange the code.
 * Cancelling the calling coroutine closes the listener; starting a new attempt closes
 * the previous one, so a forgotten browser tab can never block the next login.
 */
suspend fun runGoogleLogin(creds: OAuthCredentials, openBrowser: (String) -> Unit): OAuthTokens {
    val server = withContext(Dispatchers.IO) {
        ServerSocket().apply {
            reuseAddress = true
            soTimeout = CALLBACK_TIMEOUT_MS
            bind(InetSocketAddress("localhost", 0))
        }
    }
    synchronized(pendingLock) {
        pendingCallback?.let { runCatching { it.close() } }
        pendingCallback = server
    }
    try {
        val authState = buildAuthUrl(creds.clientId, server.localPort)
        openBrowser(authState.authUrl)
        val code = waitForAuthCode(server)
        return exchangeCode(code, creds, authState.codeVerifier, server.localPort)
    } finally {
        synchronized(pendingLock) { if (pendingCallback === server) pendingCallback = null }
        runCatching { server.close() }
    }
}

/** Blocks on accept() in IO, but closes the socket on cancellation so the coroutine really stops. */
private suspend fun waitForAuthCode(server: ServerSocket): String = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { server.close() } }
        try {
            server.accept().use { socket ->
                val request = socket.getInputStream().bufferedReader().readLine() ?: ""
                // GET /?code=XXX HTTP/1.1
                val query = request.substringAfter("?", "").substringBefore(" ")
                val params = query.split("&").associate { it.substringBefore("=") to it.substringAfter("=", "") }
                val error = params["error"]
                val code = params["code"]

                val (title, body) = when {
                    code != null -> "Authorization complete!" to "You can close this window."
                    else -> "Authorization failed" to (error ?: "no authorization code received")
                }
                val html = "<html><body style='font-family:sans-serif;text-align:center;padding:60px'>" +
                    "<h2>$title</h2><p>$body</p></body></html>"
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.toByteArray().size}\r\n\r\n$html"
                socket.getOutputStream().write(response.toByteArray())

                if (code != null) cont.resume(java.net.URLDecoder.decode(code, "UTF-8"))
                else cont.resumeWithException(Exception("Google returned: ${error ?: "no authorization code"}"))
            }
        } catch (e: java.net.SocketTimeoutException) {
            cont.resumeWithException(Exception("Timed out waiting for the browser authorization"))
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(
                if (server.isClosed) Exception("Login cancelled") else e
            )
        }
    }
}

suspend fun exchangeCode(
    code: String,
    creds: OAuthCredentials,
    codeVerifier: String,
    redirectPort: Int = DEFAULT_REDIRECT_PORT
): OAuthTokens = withContext(Dispatchers.IO) {
    val body = "code=${encode(code)}" +
        "&client_id=${creds.clientId}" +
        "&client_secret=${creds.clientSecret}" +
        "&redirect_uri=${encode(redirectUri(redirectPort))}" +
        "&grant_type=authorization_code" +
        "&code_verifier=${encode(codeVerifier)}"
    val response = post("https://oauth2.googleapis.com/token", body)
    val obj = json.parseToJsonElement(response).jsonObject
    obj["error"]?.jsonPrimitive?.content?.let { err ->
        throw Exception(obj["error_description"]?.jsonPrimitive?.content ?: err)
    }
    OAuthTokens(
        accessToken = obj["access_token"]!!.jsonPrimitive.content,
        refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
            ?: throw Exception("No refresh_token received. Make sure the scope includes offline access."),
        expiresAt = System.currentTimeMillis() / 1000 + (obj["expires_in"]?.jsonPrimitive?.long ?: 3600)
    )
}

suspend fun refreshToken(refreshToken: String): OAuthTokens = withContext(Dispatchers.IO) {
    val creds = loadCredentials() ?: throw Exception("NO_CREDENTIALS")
    val body = "client_id=${creds.clientId}" +
        "&client_secret=${creds.clientSecret}" +
        "&refresh_token=$refreshToken" +
        "&grant_type=refresh_token"
    val response = post("https://oauth2.googleapis.com/token", body)
    val obj = json.parseToJsonElement(response).jsonObject
    obj["error"]?.jsonPrimitive?.content?.let { err ->
        throw Exception(obj["error_description"]?.jsonPrimitive?.content ?: err)
    }
    OAuthTokens(
        accessToken = obj["access_token"]!!.jsonPrimitive.content,
        refreshToken = obj["refresh_token"]?.jsonPrimitive?.content ?: refreshToken,
        expiresAt = System.currentTimeMillis() / 1000 + (obj["expires_in"]?.jsonPrimitive?.long ?: 3600)
    )
}

private fun post(url: String, body: String): String =
    Http.post(url, body, contentType = "application/x-www-form-urlencoded").body

private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
