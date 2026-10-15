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
    /** The client the session was issued to; writes must name the same one the token belongs to. */
    val clientId: String? get() = session?.clientId

    /**
     * Validate a SoundCloud session by fetching the user profile. Tries api-v2 first
     * (undocumented but used by the web player), then legacy api.
     *
     * [refreshToken] comes from the sign-in page's own cookie, and is what lets the session renew
     * itself instead of expiring into another sign-in.
     */
    suspend fun connect(token: String, refreshToken: String? = null, clientId: String? = null, dataDomeCookie: String? = null): SoundCloudSession =
        connect(SoundCloudOAuth.Tokens(accessToken = token, refreshToken = refreshToken, expiresAt = null, clientId = clientId), dataDomeCookie)

    /** Store tokens after validating them against /me. */
    suspend fun connect(tokens: SoundCloudOAuth.Tokens, dataDomeCookie: String? = null): SoundCloudSession = withContext(Dispatchers.IO) {
        val me = fetchMe(tokens.accessToken)
            ?: throw IllegalArgumentException("Invalid SoundCloud token — could not fetch user profile")

        val scSession = SoundCloudSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAt,
            clientId = tokens.clientId,
            userId = me.id,
            username = me.username,
            avatarUrl = me.avatarUrl,
            permalink = me.permalink,
            dataDomeCookie = dataDomeCookie
        )
        AuthStore.saveSoundCloud(scSession)
        session = scSession
        AuthEvents.notifyChanged()
        scSession
    }

    /**
     * Refresh the session because the API refused the access token.
     *
     * The sign-in page keeps its session in a cookie and never exposes an expiry, so a refusal is
     * the only honest signal that it went stale — better than a lifetime we would have to invent.
     * Returns whether the session now holds a freshly issued token.
     */
    suspend fun refreshNow(): Boolean = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext false
        val refresh = s.refreshToken ?: return@withContext false
        runCatching {
            // The id that issued the grant, not the one scraped for api-v2: SoundCloud refuses a
            // refresh presented by a different client, and the sign-in page is not the same client.
            val refreshed = SoundCloudOAuth.refresh(refresh, s.clientId)
            val updated = s.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: refresh,
                expiresAt = refreshed.expiresAt,
                obtainedAt = System.currentTimeMillis()
            )
            AuthStore.saveSoundCloud(updated)
            session = updated
            // Deliberately no AuthEvents.notifyChanged(): the account has not changed, only the
            // token behind it, and that notification re-keys every screen.
            Log.i("SoundCloudAuth", "refreshed the SoundCloud session")
            true
        }.onFailure { Log.e("SoundCloudAuth", "Failed to refresh SoundCloud token", it) }.getOrDefault(false)
    }

    fun disconnect() {
        session = null
        AuthStore.disconnectSoundCloud()
        AuthEvents.notifyChanged()
    }

    /** Bot-protection verdict currently on file, to be attached to authenticated writes. */
    val dataDomeCookie: String? get() = session?.dataDomeCookie

    fun updateDataDomeCookie(value: String?) {
        val s = session ?: return
        session = s.copy(dataDomeCookie = value)
        AuthStore.saveSoundCloud(session!!)
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
