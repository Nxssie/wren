package auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import util.AppDirs
import util.Log
import java.io.File
import java.util.UUID

// ── Configurable root ────────────────────────────────────────────────────────

private val configDir: File get() = AppDirs.config

private fun dir(name: String) = File(configDir, name).also { it.mkdirs() }

private val json = Json { ignoreUnknownKeys = true }

// ── Models ───────────────────────────────────────────────────────────────────

@Serializable
data class LocalProfile(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String = "wren",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class GoogleSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val accountName: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class SoundCloudSession(
    val accessToken: String,
    val obtainedAt: Long = System.currentTimeMillis(),
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    /** The client that issued the session; a refresh has to come from the same one. */
    val clientId: String? = null,
    val userId: Long? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val permalink: String? = null,
    /** DataDome's own cookie, minted by the sign-in page. Writes refuse requests without it. */
    val dataDomeCookie: String? = null
)

// ── Store ────────────────────────────────────────────────────────────────────

object AuthStore {

    // ── Profile ──────────────────────────────────────────────────────────────

    private val profileFile get() = File(configDir, "profile.json")

    fun profile(): LocalProfile {
        if (!profileFile.exists()) {
            val p = LocalProfile()
            saveProfile(p)
            return p
        }
        return runCatching {
            json.decodeFromString<LocalProfile>(profileFile.readText())
        }.getOrNull()?.also {
            if (!profileFile.exists()) saveProfile(it)
        } ?: LocalProfile().also { saveProfile(it) }
    }

    private fun saveProfile(p: LocalProfile) {
        configDir.mkdirs()
        profileFile.writeText(json.encodeToString(LocalProfile.serializer(), p))
    }

    fun updateDisplayName(name: String) {
        val p = profile().copy(displayName = name)
        saveProfile(p)
    }

    // ── Google session ───────────────────────────────────────────────────────

    private val googleFile get() = File(dir("sessions"), "google.json")

    fun googleSession(): GoogleSession? {
        if (!googleFile.exists()) return null
        return runCatching {
            json.decodeFromString<GoogleSession>(googleFile.readText())
        }.getOrNull()
    }

    fun saveGoogle(session: GoogleSession) {
        googleFile.writeText(json.encodeToString(GoogleSession.serializer(), session))
        // Desktop-only convenience: yt-dlp reads its YouTube OAuth cache from ~/.cache.
        // Android has neither yt-dlp nor a writable $HOME, so this must never be fatal.
        runCatching { writeYtdlpCache(session) }
            .onFailure { Log.w("AuthStore", "yt-dlp OAuth cache not written (desktop-only path)", it) }
    }

    fun disconnectGoogle() {
        googleFile.delete()
        runCatching { ytdlpCacheFile.delete() }
    }

    // ── SoundCloud session ───────────────────────────────────────────────────

    private val soundcloudFile get() = File(dir("sessions"), "soundcloud.json")

    fun soundcloudSession(): SoundCloudSession? {
        if (!soundcloudFile.exists()) return null
        return runCatching {
            json.decodeFromString<SoundCloudSession>(soundcloudFile.readText())
        }.getOrNull()
    }

    fun saveSoundCloud(session: SoundCloudSession) {
        soundcloudFile.writeText(json.encodeToString(SoundCloudSession.serializer(), session))
    }

    fun disconnectSoundCloud() {
        soundcloudFile.delete()
    }

    // ── Legacy migration ─────────────────────────────────────────────────────

    private val legacyTokensFile get() = File(configDir, "tokens.json")
    private val legacyAccountFile get() = File(configDir, "account_name")
    private val legacyAvatarFile get() = File(configDir, "avatar_url")

    // yt-dlp reads its YouTube OAuth cache from here
    private val ytdlpCacheFile = File(System.getProperty("user.home"), ".cache/yt-dlp/youtube/oauth2.json")

    fun migrateLegacy() {
        if (!legacyTokensFile.exists()) return
        if (googleSession() != null) {
            // Session already migrated — clean up legacy files
            legacyTokensFile.delete()
            legacyAccountFile.delete()
            legacyAvatarFile.delete()
            return
        }
        runCatching {
            val obj = json.parseToJsonElement(legacyTokensFile.readText()).jsonObject
            val session = GoogleSession(
                accessToken = obj["access_token"]!!.jsonPrimitive.content,
                refreshToken = obj["refresh_token"]!!.jsonPrimitive.content,
                expiresAt = obj["expires_at"]!!.jsonPrimitive.content.toLong(),
                accountName = if (legacyAccountFile.exists()) legacyAccountFile.readText().trim().takeIf { it.isNotEmpty() } else null,
                avatarUrl = if (legacyAvatarFile.exists()) legacyAvatarFile.readText().trim().takeIf { it.isNotEmpty() } else null
            )
            saveGoogle(session)
            legacyTokensFile.delete()
            legacyAccountFile.delete()
            legacyAvatarFile.delete()
            Log.i("AuthStore", "Migrated legacy Google tokens to sessions/google.json")
        }.onFailure { Log.e("AuthStore", "Failed to migrate legacy tokens", it) }
    }

    private fun writeYtdlpCache(session: GoogleSession) {
        ytdlpCacheFile.parentFile.mkdirs()
        ytdlpCacheFile.writeText(
            buildJsonObject {
                put("access_token", session.accessToken)
                put("expires", session.expiresAt.toDouble())
                put("refresh_token", session.refreshToken)
                put("token_type", "Bearer")
            }.toString()
        )
    }
}
