package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

object AuthManager {
    private val configDir = File(System.getProperty("user.home"), ".config/wren")
    private val tokensFile = File(configDir, "tokens.json")
    private val accountNameFile = File(configDir, "account_name")
    private val avatarUrlFile = File(configDir, "avatar_url")

    // yt-dlp reads its YouTube OAuth cache from here
    private val ytdlpCacheFile = File(System.getProperty("user.home"), ".cache/yt-dlp/youtube/oauth2.json")

    private val json = Json { ignoreUnknownKeys = true }

    private var tokens: OAuthTokens? = loadTokens()

    val isAuthenticated: Boolean get() = tokens != null
    val accountName: String? get() = if (accountNameFile.exists()) accountNameFile.readText().trim().takeIf { it.isNotEmpty() } else null
    val avatarUrl: String? get() = if (avatarUrlFile.exists()) avatarUrlFile.readText().trim().takeIf { it.isNotEmpty() } else null

    val accessToken: String? get() = tokens?.accessToken

    val authArgs: List<String>
        get() {
            val token = tokens?.accessToken ?: return emptyList()
            return listOf("--add-header", "Authorization:Bearer $token")
        }

    private fun loadTokens(): OAuthTokens? = runCatching {
        if (!tokensFile.exists()) return null
        val obj = json.parseToJsonElement(tokensFile.readText()).jsonObject
        OAuthTokens(
            accessToken = obj["access_token"]!!.jsonPrimitive.content,
            refreshToken = obj["refresh_token"]!!.jsonPrimitive.content,
            expiresAt = obj["expires_at"]!!.jsonPrimitive.long
        )
    }.getOrNull()

    suspend fun saveTokens(newTokens: OAuthTokens) = withContext(Dispatchers.IO) {
        tokens = newTokens
        configDir.mkdirs()
        tokensFile.writeText(
            buildJsonObject {
                put("access_token", newTokens.accessToken)
                put("refresh_token", newTokens.refreshToken)
                put("expires_at", newTokens.expiresAt)
            }.toString()
        )
        writeYtdlpCache(newTokens)
        fetchAndSaveAccountName()
    }

    suspend fun ensureValidToken() = withContext(Dispatchers.IO) {
        val current = tokens ?: return@withContext
        val nowSecs = System.currentTimeMillis() / 1000
        if (nowSecs >= current.expiresAt - 60) {
            runCatching {
                val refreshed = refreshToken(current.refreshToken)
                saveTokens(refreshed)
            }
        } else {
            writeYtdlpCache(current)
        }
    }

    fun logout() {
        tokens = null
        tokensFile.delete()
        accountNameFile.delete()
        avatarUrlFile.delete()
        ytdlpCacheFile.delete()
    }

    private fun writeYtdlpCache(t: OAuthTokens) {
        ytdlpCacheFile.parentFile.mkdirs()
        ytdlpCacheFile.writeText(
            buildJsonObject {
                put("access_token", t.accessToken)
                put("expires", t.expiresAt.toDouble())
                put("refresh_token", t.refreshToken)
                put("token_type", "Bearer")
            }.toString()
        )
    }

    private fun fetchAndSaveAccountName() {
        // Account name fetched via YouTube Data API using the access token
        runCatching {
            val token = tokens?.accessToken ?: return
            val request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true"))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body()
            val obj = json.parseToJsonElement(response).jsonObject
            val snippet = obj["items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("snippet")?.jsonObject
            val name = snippet?.get("title")?.jsonPrimitive?.content
            if (!name.isNullOrEmpty()) accountNameFile.writeText(name)
            val avatar = snippet?.get("thumbnails")?.jsonObject
                ?.get("medium")?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: snippet?.get("thumbnails")?.jsonObject
                    ?.get("default")?.jsonObject?.get("url")?.jsonPrimitive?.content
            if (!avatar.isNullOrEmpty()) avatarUrlFile.writeText(avatar)
        }
    }
}
