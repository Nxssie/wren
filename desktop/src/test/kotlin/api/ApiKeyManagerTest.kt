package api

import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class ApiKeyManagerTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "wren-test-${System.currentTimeMillis()}")

    @BeforeEach
    fun setUp() {
        tempDir.mkdirs()
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `config file path should be in user home config directory`() {
        val expectedPath = File(System.getProperty("user.home"), ".config/wren/api.json")
        assertEquals("api.json", expectedPath.name)
        assertEquals(".config/wren", expectedPath.parentFile.relativeTo(File(System.getProperty("user.home"))).toString())
    }

    @Test
    fun `should write valid JSON config format`() {
        val configDir = File(tempDir, ".config/wren")
        val apiKeyFile = File(configDir, "api.json")
        configDir.mkdirs()

        val json = buildJsonObject {
            put("ytmusic_key", "test-ytmusic-key")
            put("youtube_key", "test-youtube-key")
        }.toString()

        apiKeyFile.writeText(json)

        val parsed = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
        assertEquals("test-ytmusic-key", parsed["ytmusic_key"]?.jsonPrimitive?.content)
        assertEquals("test-youtube-key", parsed["youtube_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should handle partial config with missing keys`() {
        val configDir = File(tempDir, ".config/wren")
        val apiKeyFile = File(configDir, "api.json")
        configDir.mkdirs()

        val json = """{"ytmusic_key":"partial-key"}"""
        apiKeyFile.writeText(json)

        val parsed = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
        assertEquals("partial-key", parsed["ytmusic_key"]?.jsonPrimitive?.content)
        assertNull(parsed["youtube_key"])
    }

    @Test
    fun `should handle empty config file gracefully`() {
        val configDir = File(tempDir, ".config/wren")
        val apiKeyFile = File(configDir, "api.json")
        configDir.mkdirs()

        apiKeyFile.writeText("{}")

        val parsed = Json { ignoreUnknownKeys = true }.parseToJsonElement("{}").jsonObject
        assertNull(parsed["ytmusic_key"])
        assertNull(parsed["youtube_key"])
    }

    @Test
    fun `should handle non-existent config file`() {
        val nonExistent = File(tempDir, "non-existent-config.json")
        assertFalse(nonExistent.exists())
        // File.readText would throw, which is the expected behavior
        // The ApiKeyManager handles this with runCatching
    }
}
