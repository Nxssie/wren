package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import util.Http
import util.Log

object GoogleAuth {
    private var session: GoogleSession? = AuthStore.googleSession()

    val isAuthenticated: Boolean get() = session != null
    val accountName: String? get() = session?.accountName
    val avatarUrl: String? get() = session?.avatarUrl
    val accessToken: String? get() = session?.accessToken

    val authArgs: List<String>
        get() {
            val token = session?.accessToken ?: return emptyList()
            return listOf("--add-header", "Authorization:Bearer $token")
        }

    suspend fun connect(tokens: OAuthTokens) = withContext(Dispatchers.IO) {
        val sc = GoogleSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAt
        )
        AuthStore.saveGoogle(sc)
        session = sc
        fetchAndSaveAccountName()
        AuthEvents.notifyChanged()
    }

    suspend fun ensureValidToken() = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext
        val nowSecs = System.currentTimeMillis() / 1000
        if (nowSecs >= s.expiresAt - 60) {
            runCatching {
                val refreshed = refreshToken(s.refreshToken)
                val newSession = GoogleSession(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken,
                    expiresAt = refreshed.expiresAt,
                    accountName = s.accountName,
                    avatarUrl = s.avatarUrl
                )
                AuthStore.saveGoogle(newSession)
                session = newSession
            }.onFailure { Log.e("GoogleAuth", "Failed to refresh OAuth token", it) }
        }
    }

    fun disconnect() {
        session = null
        AuthStore.disconnectGoogle()
        AuthEvents.notifyChanged()
    }

    private fun fetchAndSaveAccountName() {
        runCatching {
            val token = session?.accessToken ?: return
            val response = Http.get(
                "https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true",
                headers = mapOf("Authorization" to "Bearer $token"),
            )
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(response.body).jsonObject
            val snippet = obj["items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("snippet")?.jsonObject
            val name = snippet?.get("title")?.jsonPrimitive?.content
            val avatar = snippet?.get("thumbnails")?.jsonObject
                ?.get("medium")?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: snippet?.get("thumbnails")?.jsonObject
                    ?.get("default")?.jsonObject?.get("url")?.jsonPrimitive?.content
            val updated = session?.copy(
                accountName = name?.takeIf { it.isNotEmpty() },
                avatarUrl = avatar?.takeIf { it.isNotEmpty() }
            ) ?: return
            AuthStore.saveGoogle(updated)
            session = updated
        }.onFailure { Log.e("GoogleAuth", "Failed to fetch account name/avatar", it) }
    }
}
