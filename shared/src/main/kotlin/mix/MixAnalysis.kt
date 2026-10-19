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

    /** A fresh cached answer for [trackId] from [provider], or null when there is none or a miss has expired. */
    fun cached(trackId: String, provider: String, now: Long = System.currentTimeMillis()): BpmKey? =
        entryFor(trackId, provider, now)?.takeIf { it.found }
            ?.let { BpmKey(it.bpm, CamelotKey.parse(it.camelot), it.provider) }

    /**
     * Cache key: the provider is part of it, because two providers answer different things about the
     * same track. A metadata service that has never heard of a rip says nothing about what an
     * analyser finds in its audio, and an old answer from one must not shadow the other.
     */
    private fun cacheKey(trackId: String, provider: String): String = "$provider:$trackId"

    /** The stored entry while it is still trusted: a hit forever, a miss until it expires. */
    private fun entryFor(trackId: String, provider: String, now: Long): Entry? {
        val entry = read()[cacheKey(trackId, provider)] ?: return null
        if (!entry.found && now - entry.fetchedAt > MISS_TTL_MS) return null
        return entry
    }

    /**
     * The cheap path: what the track already carries, else the cache, else one provider call.
     *
     * Null means "unknown" — the caller keeps the track and simply cannot rank it. A track that
     * answers for both halves is never looked at again; one that is missing a half still is, since
     * an earlier attempt can leave a tempo without a key behind.
     */
    fun resolve(
        track: MixTrack,
        provider: BpmKeyProvider?,
        now: Long = System.currentTimeMillis()
    ): BpmKey? {
        if (isComplete(track)) return BpmKey(track.bpm, track.key, provider = MANUAL_PROVIDER)

        val usable = provider?.takeIf { it.isConfigured } ?: return carried(track)

        cached(track.id, usable.name, now)?.let { return merge(track, it) }

        // A remembered miss is still an answer: asking again right away would pay for the same
        // download to learn the same nothing.
        if (entryFor(track.id, usable.name, now) != null) return carried(track)

        return when (val attempt = ask(usable, track)) {
            is Attempt.Failed -> carried(track)
            is Attempt.Answered -> {
                remember(track.id, usable.name, attempt.answer, now)
                merge(track, attempt.answer) ?: carried(track)
            }
        }
    }

    /**
     * Asks [provider] again for [track], whatever the track and the cache already say, and records
     * what comes back.
     *
     * This is the explicit re-ask behind "detect tempo + key"; [resolve] is what every other path
     * uses. Recording the answer is the point: a forced re-ask that left nothing behind would make
     * the next cheap path pay for the same download again.
     */
    fun refill(
        track: MixTrack,
        provider: BpmKeyProvider?,
        now: Long = System.currentTimeMillis()
    ): BpmKey? {
        val usable = provider?.takeIf { it.isConfigured } ?: return null
        return when (val attempt = ask(usable, track)) {
            is Attempt.Failed -> null
            is Attempt.Answered -> {
                remember(track.id, usable.name, attempt.answer, now)
                merge(track, attempt.answer)
            }
        }
    }

    /** Drops the whole cache; the next lookups ask the provider again. */
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Log.w("MixAnalysis", "could not clear the analysis cache: ${it.message}") }
    }

    private fun isComplete(track: MixTrack): Boolean =
        (track.bpm ?: 0.0) > 0.0 && track.camelot != null

    /** What the track already answers on its own; the ranking can use it even when it is partial. */
    private fun carried(track: MixTrack): BpmKey? =
        if (track.isAnalysed) BpmKey(track.bpm, track.key, provider = MANUAL_PROVIDER) else null

    /**
     * The provider's answer wins where it has one, and the track fills the gaps: a forced re-ask
     * exists to replace what the track already said, not to lose the half the provider left out.
     */
    private fun merge(track: MixTrack, answer: BpmKey?): BpmKey? {
        if (answer == null) return null
        return BpmKey(answer.bpm ?: track.bpm, answer.camelot ?: track.key, answer.provider)
    }

    private sealed class Attempt {
        data class Answered(val answer: BpmKey?) : Attempt()
        object Failed : Attempt()
    }

    /**
     * One provider call, separating "this track is unknown to the provider" from "the provider could
     * not answer at all".
     *
     * Only the first is a fact about the track and worth remembering: caching a failure would keep
     * the track unknown for [MISS_TTL_MS] because of one flaky moment — a yt-dlp that was not
     * installed yet, a network that dropped.
     */
    private fun ask(provider: BpmKeyProvider, track: MixTrack): Attempt = try {
        Attempt.Answered(provider.lookup(track))
    } catch (e: ProviderUnavailableException) {
        Log.w("MixAnalysis", "provider unavailable for ${track.id}: ${e.message}")
        Attempt.Failed
    } catch (e: Exception) {
        Log.w("MixAnalysis", "lookup failed for ${track.id}: ${e.message}")
        Attempt.Failed
    }

    private fun remember(trackId: String, provider: String, answer: BpmKey?, now: Long) {
        val entries = read()
        entries[cacheKey(trackId, provider)] = Entry(
            bpm = answer?.bpm,
            camelot = answer?.camelot?.toString(),
            provider = provider,
            found = answer != null,
            fetchedAt = now
        )
        write(entries)
    }

    private const val MANUAL_PROVIDER = "manual"
}

/**
 * A provider that could not get what it needed to answer at all — no stream URL, no audio samples,
 * no network. It is deliberately distinct from returning `null`, which means "I looked and there is
 * nothing": only the latter is worth remembering, because only the latter is a fact about the track.
 */
class ProviderUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
