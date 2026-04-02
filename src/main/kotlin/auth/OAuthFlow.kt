package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val httpClient: HttpClient = HttpClient.newHttpClient()
private val json = Json { ignoreUnknownKeys = true }

private const val SCOPE = "https://www.googleapis.com/auth/youtube"
private const val REDIRECT_PORT = 8765
private const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT"

private val credentialsFile = java.io.File(System.getProperty("user.home"), ".config/wren/oauth.json")

data class OAuthCredentials(val clientId: String, val clientSecret: String)

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

fun loadCredentials(): OAuthCredentials? = runCatching {
    if (!credentialsFile.exists()) return null
    val root = json.parseToJsonElement(credentialsFile.readText()).jsonObject
    val obj = root["installed"]?.jsonObject ?: root["web"]?.jsonObject ?: root
    OAuthCredentials(
        clientId = obj["client_id"]?.jsonPrimitive?.content ?: return null,
        clientSecret = obj["client_secret"]?.jsonPrimitive?.content ?: return null
    )
}.getOrNull()

fun buildAuthUrl(clientId: String): String =
    "https://accounts.google.com/o/oauth2/v2/auth" +
    "?client_id=$clientId" +
    "&redirect_uri=${encode(REDIRECT_URI)}" +
    "&response_type=code" +
    "&scope=${encode(SCOPE)}" +
    "&access_type=offline" +
    "&prompt=consent"

suspend fun waitForAuthCode(): String = withContext(Dispatchers.IO) {
    ServerSocket().use { server ->
        server.reuseAddress = true
        server.bind(InetSocketAddress("localhost", REDIRECT_PORT))
        server.accept().use { socket ->
            val request = socket.getInputStream().bufferedReader().readLine() ?: ""
            // GET /?code=XXX HTTP/1.1
            val code = request.substringAfter("?").substringBefore(" ")
                .split("&").firstOrNull { it.startsWith("code=") }
                ?.removePrefix("code=")
                ?: throw Exception("No se recibió el código de autorización")

            val html = "<html><body style='font-family:sans-serif;text-align:center;padding:60px'>" +
                "<h2>¡Autorización completada!</h2><p>Puedes cerrar esta ventana.</p></body></html>"
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html"
            socket.getOutputStream().write(response.toByteArray())
            code
        }
    }
}

suspend fun exchangeCode(code: String, creds: OAuthCredentials): OAuthTokens = withContext(Dispatchers.IO) {
    val body = "code=${encode(code)}" +
        "&client_id=${creds.clientId}" +
        "&client_secret=${creds.clientSecret}" +
        "&redirect_uri=${encode(REDIRECT_URI)}" +
        "&grant_type=authorization_code"
    val response = post("https://oauth2.googleapis.com/token", body)
    val obj = json.parseToJsonElement(response).jsonObject
    obj["error"]?.jsonPrimitive?.content?.let { err ->
        throw Exception(obj["error_description"]?.jsonPrimitive?.content ?: err)
    }
    OAuthTokens(
        accessToken = obj["access_token"]!!.jsonPrimitive.content,
        refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
            ?: throw Exception("No se recibió refresh_token. Asegúrate de que el scope incluye acceso offline."),
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

private fun post(url: String, body: String): String {
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()
}

private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
