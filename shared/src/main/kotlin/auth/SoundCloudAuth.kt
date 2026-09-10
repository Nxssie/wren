package auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import util.Http
import util.Log

private val scAuthJson = Json { ignoreUnknownKeys = true }
private const val SC_AUTH_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

object SoundCloudAuth {
    private var session: SoundCloudSession? = AuthStore.soundcloudSession()

    val isAuthenticated: Boolean get() = session != null
    val username: String? get() = session?.username
    val avatarUrl: String? get() = session?.avatarUrl
    val userId: Long? get() = session?.userId
    val accessToken: String? get() = session?.accessToken

    /**
     * Validate a SoundCloud OAuth token (pasted manually) by fetching the user profile.
     * Tries api-v2 first (undocumented but used by the web player), then legacy api.
     */
    suspend fun connect(token: String): SoundCloudSession =
        connect(SoundCloudOAuth.Tokens(accessToken = token, refreshToken = null, expiresAt = null))

    /** Store tokens from the native PKCE flow after validating them against /me. */
    suspend fun connect(tokens: SoundCloudOAuth.Tokens): SoundCloudSession = withContext(Dispatchers.IO) {
        val me = fetchMe(tokens.accessToken)
            ?: throw IllegalArgumentException("Invalid SoundCloud token — could not fetch user profile")

        val scSession = SoundCloudSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAt,
            userId = me.id,
            username = me.username,
            avatarUrl = me.avatarUrl,
            permalink = me.permalink
        )
        AuthStore.saveSoundCloud(scSession)
        session = scSession
        AuthEvents.notifyChanged()
        scSession
    }

    /**
     * Refresh the access token when it is about to expire. Only sessions created by the
     * PKCE flow carry a refresh token; pasted tokens are left as-is.
     */
    suspend fun ensureValidToken() = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext
        val refresh = s.refreshToken ?: return@withContext
        val expiresAt = s.expiresAt ?: return@withContext
        if (System.currentTimeMillis() / 1000 < expiresAt - 60) return@withContext
        runCatching {
            val refreshed = SoundCloudOAuth.refresh(refresh, api.scClientId())
            val updated = s.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: refresh,
                expiresAt = refreshed.expiresAt,
                obtainedAt = System.currentTimeMillis()
            )
            AuthStore.saveSoundCloud(updated)
            session = updated
        }.onFailure { Log.e("SoundCloudAuth", "Failed to refresh SoundCloud token", it) }
    }

    fun disconnect() {
        session = null
        AuthStore.disconnectSoundCloud()
        AuthEvents.notifyChanged()
    }

    // ── /me fetch (internal for testing) ─────────────────────────────────────

    internal data class MeResult(
        val id: Long,
        val username: String,
        val avatarUrl: String?,
        val permalink: String?
    )

    internal suspend fun fetchMe(token: String): MeResult? = withContext(Dispatchers.IO) {
        // Try api-v2 first (the web player uses this with Authorization: OAuth header)
        fetchMeV2(token) ?: fetchMeLegacy(token)
    }

    private fun fetchMeV2(token: String): MeResult? = runCatching {
        val resp = Http.get(
            "https://api-v2.soundcloud.com/me",
            headers = mapOf("Authorization" to "OAuth $token", "User-Agent" to SC_AUTH_UA),
        )
        if (!resp.isSuccessful) return@runCatching null
        parseMe(resp.body)
    }.onFailure { Log.w("SoundCloudAuth", "api-v2 /me failed", it) }.getOrNull()

    private fun fetchMeLegacy(token: String): MeResult? = runCatching {
        val resp = Http.get(
            "https://api.soundcloud.com/me?oauth_token=$token",
            headers = mapOf("User-Agent" to SC_AUTH_UA),
        )
        if (!resp.isSuccessful) return@runCatching null
        parseMe(resp.body)
    }.onFailure { Log.w("SoundCloudAuth", "legacy /me failed", it) }.getOrNull()

    internal fun parseMe(body: String): MeResult? = runCatching {
        val obj = scAuthJson.parseToJsonElement(body).jsonObject
        MeResult(
            id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
            username = obj["username"]?.jsonPrimitive?.content ?: return null,
            avatarUrl = obj["avatar_url"]?.jsonPrimitive?.contentOrNull,
            permalink = obj["permalink_url"]?.jsonPrimitive?.contentOrNull
        )
    }.getOrNull()
}
