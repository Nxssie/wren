package auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.AppDirs
import java.io.File

class AuthStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun withTempConfig(block: () -> Unit) {
        AppDirs.init(configDir = tempDir, stateDir = File(tempDir, "state"))
        block()
    }

    @Test
    fun `should create and load profile`() = withTempConfig {
        val p = AuthStore.profile()
        assertNotNull(p.id)
        assertEquals("wren", p.displayName)
        assertTrue(p.createdAt > 0)
        // Reload — same id
        val p2 = AuthStore.profile()
        assertEquals(p.id, p2.id)
    }

    @Test
    fun `should update display name`() = withTempConfig {
        AuthStore.updateDisplayName("my_wren")
        assertEquals("my_wren", AuthStore.profile().displayName)
    }

    @Test
    fun `should save and load google session`() = withTempConfig {
        val session = GoogleSession("tok", "ref", 1000, "TestUser", "https://avatar.png")
        AuthStore.saveGoogle(session)
        val loaded = AuthStore.googleSession()!!
        assertEquals("tok", loaded.accessToken)
        assertEquals("TestUser", loaded.accountName)
        assertEquals("https://avatar.png", loaded.avatarUrl)
    }

    @Test
    fun `should save and load soundcloud session`() = withTempConfig {
        val session = SoundCloudSession("sc_tok", userId = 42, username = "nxssie", permalink = "https://soundcloud.com/nxssie")
        AuthStore.saveSoundCloud(session)
        val loaded = AuthStore.soundcloudSession()!!
        assertEquals("sc_tok", loaded.accessToken)
        assertEquals(42L, loaded.userId)
        assertEquals("nxssie", loaded.username)
    }

    @Test
    fun `should keep the bot-protection cookie with the soundcloud session`() = withTempConfig {
        AuthStore.saveSoundCloud(SoundCloudSession("sc_tok", userId = 42, dataDomeCookie = "verdict~123"))
        assertEquals("verdict~123", AuthStore.soundcloudSession()!!.dataDomeCookie)

        // A session written before the cookie existed loads with none rather than failing.
        AuthStore.saveSoundCloud(SoundCloudSession("sc_tok", userId = 42))
        assertNull(AuthStore.soundcloudSession()!!.dataDomeCookie)
    }

    @Test
    fun `should disconnect google`() = withTempConfig {
        AuthStore.saveGoogle(GoogleSession("tok", "ref", 1000))
        AuthStore.disconnectGoogle()
        assertNull(AuthStore.googleSession())
    }

    @Test
    fun `should disconnect soundcloud`() = withTempConfig {
        AuthStore.saveSoundCloud(SoundCloudSession("tok"))
        AuthStore.disconnectSoundCloud()
        assertNull(AuthStore.soundcloudSession())
    }

    @Test
    fun `should migrate legacy tokens`() = withTempConfig {
        // Write legacy files
        File(tempDir, "tokens.json").writeText("""{"access_token":"legacy_tok","refresh_token":"legacy_ref","expires_at":9999}""")
        File(tempDir, "account_name").writeText("Legacy User")
        File(tempDir, "avatar_url").writeText("https://legacy.png")

        AuthStore.migrateLegacy()

        // Legacy files deleted
        assertFalse(File(tempDir, "tokens.json").exists())
        assertFalse(File(tempDir, "account_name").exists())
        assertFalse(File(tempDir, "avatar_url").exists())

        // Session created
        val session = AuthStore.googleSession()!!
        assertEquals("legacy_tok", session.accessToken)
        assertEquals("legacy_ref", session.refreshToken)
        assertEquals("Legacy User", session.accountName)
        assertEquals("https://legacy.png", session.avatarUrl)
    }

    @Test
    fun `should not migrate when session already exists`() = withTempConfig {
        AuthStore.saveGoogle(GoogleSession("existing", "ref", 1000))
        File(tempDir, "tokens.json").writeText("""{"access_token":"should_not_migrate","refresh_token":"x","expires_at":1}""")

        AuthStore.migrateLegacy()

        assertEquals("existing", AuthStore.googleSession()!!.accessToken)
        // Legacy file deleted anyway
        assertFalse(File(tempDir, "tokens.json").exists())
    }
}
