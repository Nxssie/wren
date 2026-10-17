package mix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import util.Http
import java.net.URLEncoder

/** A tempo/key answer for one track. Either half may be missing; both missing is no answer. */
data class BpmKey(val bpm: Double?, val camelot: CamelotKey?, val provider: String)

/**
 * Where tempo and key come from. Resolved through an interface because the sources are not
 * interchangeable — a commercial metadata lookup covers released tracks, a local analyser would
 * cover rips — and the screen must not care which one answered.
 */
interface BpmKeyProvider {
    val name: String

    /** False when the provider has no credentials yet; [lookup] then returns null without calling out. */
    val isConfigured: Boolean

    fun lookup(artist: String, title: String): BpmKey?
}

/**
 * GetSongBPM's public web API (https://getsongbpm.com/api), the free source that stayed available
 * after TuneBat confirmed it has no public finder API of its own.
 *
 * The key comes from `WREN_GETSONGBPM_KEY`; without it the provider reports itself unconfigured so
 * the UI can say why nothing is being detected instead of pretending the tracks are unknown to
 * everyone. Answers carry `key_of` already in camelot notation, with a musical `key` as fallback.
 */
class GetSongBpmProvider(
    private val apiKey: String? = System.getenv("WREN_GETSONGBPM_KEY"),
    private val fetch: (String) -> String? = { url -> Http.get(url).takeIf { it.isSuccessful }?.body }
) : BpmKeyProvider {

    override val name: String = PROVIDER_NAME
    override val isConfigured: Boolean = !apiKey.isNullOrBlank()

    override fun lookup(artist: String, title: String): BpmKey? {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: return null
        if (title.isBlank()) return null
        val lookup = buildString {
            append("song:").append(encode(title))
            if (artist.isNotBlank()) append("%20").append("artist:").append(encode(artist))
        }
        val url = "https://api.getsong.co/search/?api_key=${encode(key)}&type=both&lookup=$lookup"
        val body = runCatching { fetch(url) }.getOrNull() ?: return null
        return parseSearch(body)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val PROVIDER_NAME = "getsongbpm"

        /** First hit that carries at least one musical fact, read defensively — this API is loose. */
        fun parseSearch(body: String): BpmKey? {
            if (body.isBlank()) return null
            val hits = runCatching {
                Json.parseToJsonElement(body).jsonObject["search"]?.jsonArray
            }.getOrNull() ?: return null

            for (hit in hits) {
                val fields = runCatching { hit.jsonObject }.getOrNull() ?: continue
                val bpm = fields["tempo"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val key = CamelotKey.parse(fields["key_of"]?.jsonPrimitive?.content)
                    ?: CamelotKey.parse(fields["key"]?.jsonPrimitive?.content)
                if (bpm != null || key != null) return BpmKey(bpm, key, PROVIDER_NAME)
            }
            return null
        }
    }
}
