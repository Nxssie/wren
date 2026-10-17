package mix

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import util.AppDirs
import util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Tempo/key answers per track, cached in the config directory so a set opened twice only asks the
 * provider once.
 *
 * A hit is kept forever — tempo and key do not drift. A miss expires after [MISS_TTL_MS] so a track
 * that the provider did not know last week gets another chance, while an offline session does not
 * spend every start re-asking for the same unknowns.
 */
object MixAnalysis {

    /** How long an unanswered lookup is trusted before it is retried. */
    val MISS_TTL_MS: Long = TimeUnit.DAYS.toMillis(7)

    @Serializable
    private data class Entry(
        val bpm: Double? = null,
        val camelot: String? = null,
        val provider: String = "",
        val found: Boolean = false,
        val fetchedAt: Long = 0
    )

    @Serializable
    private data class CacheFile(val version: Int = 1, val entries: Map<String, Entry> = emptyMap())

    private val file get() = File(AppDirs.config, "mix-analysis.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun read(): MutableMap<String, Entry> {
        if (!file.exists()) return mutableMapOf()
        return runCatching { json.decodeFromString(CacheFile.serializer(), file.readText()).entries.toMutableMap() }
            .onFailure { Log.w("MixAnalysis", "could not read the analysis cache: ${it.message}") }
            .getOrElse { mutableMapOf() }
    }

    private fun write(entries: Map<String, Entry>) {
        runCatching {
            AppDirs.config.mkdirs()
            val temp = File(AppDirs.config, "mix-analysis.json.tmp")
            temp.writeText(json.encodeToString(CacheFile.serializer(), CacheFile(entries = entries)))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Log.w("MixAnalysis", "could not save the analysis cache: ${it.message}") }
    }

    /** A fresh cached answer for [trackId], or null when there is none or a miss has expired. */
    fun cached(trackId: String, now: Long = System.currentTimeMillis()): BpmKey? =
        entryFor(trackId, now)?.takeIf { it.found }?.let { BpmKey(it.bpm, CamelotKey.parse(it.camelot), it.provider) }

    /** The stored entry while it is still trusted: a hit forever, a miss until it expires. */
    private fun entryFor(trackId: String, now: Long): Entry? {
        val entry = read()[trackId] ?: return null
        if (!entry.found && now - entry.fetchedAt > MISS_TTL_MS) return null
        return entry
    }

    /**
     * The tempo/key of [track]: what the track already carries, else the cache, else one provider
     * call. Null means "unknown" — the caller keeps the track and simply cannot rank it.
     */
    fun resolve(
        track: MixTrack,
        provider: BpmKeyProvider?,
        now: Long = System.currentTimeMillis()
    ): BpmKey? {
        if (track.camelot != null || (track.bpm ?: 0.0) > 0.0) {
            return BpmKey(track.bpm, track.key, provider = "manual")
        }

        cached(track.id, now)?.let { return it }

        // A remembered miss is still an answer: asking again right away would burn quota to learn
        // the same nothing.
        entryFor(track.id, now)?.let { if (!it.found) return null }

        val usable = provider?.takeIf { it.isConfigured } ?: return null

        // A provider that could not answer is not a provider that does not know the track. Caching
        // this as a miss would keep the track unknown for MISS_TTL_MS because of one flaky moment —
        // a yt-dlp that was not installed yet, a network that dropped — so a failure returns without
        // touching the cache and the next attempt asks again.
        val answer = try {
            usable.lookup(track)
        } catch (e: ProviderUnavailableException) {
            Log.w("MixAnalysis", "provider unavailable for ${track.id}: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w("MixAnalysis", "lookup failed for ${track.id}: ${e.message}")
            return null
        }

        val entries = read()
        entries[track.id] = Entry(
            bpm = answer?.bpm,
            camelot = answer?.camelot?.toString(),
            provider = usable.name,
            found = answer != null,
            fetchedAt = now
        )
        write(entries)
        return answer
    }

    /**
     * Asks [provider] again for [track], whatever the track and the cache already say.
     *
     * An earlier attempt can leave half an answer behind — a tempo but no key — and a track with a
     * tempo counts as analysed, so nothing would ever ask again and the missing half would stay
     * missing forever. A failure is reported and remembered nowhere, exactly like [resolve].
     */
    fun refill(track: MixTrack, provider: BpmKeyProvider?): BpmKey? {
        val usable = provider?.takeIf { it.isConfigured } ?: return null
        return try {
            usable.lookup(track)
        } catch (e: ProviderUnavailableException) {
            Log.w("MixAnalysis", "provider unavailable for ${track.id}: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w("MixAnalysis", "lookup failed for ${track.id}: ${e.message}")
            null
        }
    }

    /** Drops the whole cache; the next lookups ask the provider again. */
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Log.w("MixAnalysis", "could not clear the analysis cache: ${it.message}") }
    }
}

/**
 * A provider that could not get what it needed to answer at all — no stream URL, no audio samples,
 * no network. It is deliberately distinct from returning `null`, which means "I looked and there is
 * nothing": only the latter is worth remembering, because only the latter is a fact about the track.
 */
class ProviderUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
