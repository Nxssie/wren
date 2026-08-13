package api

import kotlinx.serialization.json.*
import java.io.File

object ApiKeyManager {
    private val configDir = File(System.getProperty("user.home"), ".config/wren")
    private val apiKeyFile = File(configDir, "api.json")

    private val json = Json { ignoreUnknownKeys = true }

    // Fallback values — these are YouTube's undocumented InnerTube keys
    // They work but may break without notice. Users should provide their own keys.
    private const val FALLBACK_YTMUSIC_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val FALLBACK_YOUTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    val ytMusicKey: String get() = loadConfig()?.ytmusicKey ?: FALLBACK_YTMUSIC_KEY
    val youtubeKey: String get() = loadConfig()?.youtubeKey ?: FALLBACK_YOUTUBE_KEY

    private fun loadConfig(): ApiKeysConfig? = runCatching {
        if (!apiKeyFile.exists()) return null
        val root = json.parseToJsonElement(apiKeyFile.readText()).jsonObject
        ApiKeysConfig(
            ytmusicKey = root["ytmusic_key"]?.jsonPrimitive?.content,
            youtubeKey = root["youtube_key"]?.jsonPrimitive?.content
        )
    }.getOrNull()

    data class ApiKeysConfig(
        val ytmusicKey: String?,
        val youtubeKey: String?
    )

    fun saveKeys(ytMusicKey: String, youtubeKey: String) {
        configDir.mkdirs()
        apiKeyFile.writeText(
            buildJsonObject {
                put("ytmusic_key", ytMusicKey)
                put("youtube_key", youtubeKey)
            }.toString()
        )
    }

    fun resetToDefaults() {
        if (apiKeyFile.exists()) apiKeyFile.delete()
    }
}
