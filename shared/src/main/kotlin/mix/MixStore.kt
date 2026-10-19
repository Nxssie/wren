package mix

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import util.AppDirs
import util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The sets the user has built, persisted as one JSON file under the config directory.
 *
 * Every mutator returns the whole list after the change so a caller can render straight from the
 * result instead of re-reading, and writes go through a temp file plus a rename so a crash mid
 * write cannot leave the file truncated — that file is the only copy of the sets.
 *
 * Reads are unconditional: no in-memory cache, because the file is small and two windows reading
 * the same sets must never disagree.
 */
object MixStore {

    @Serializable
    private data class MixStoreFile(val version: Int = 1, val sets: List<MixSet> = emptyList())

    private val file get() = File(AppDirs.config, "mix-sets.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun sets(): List<MixSet> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(MixStoreFile.serializer(), file.readText()).sets }
            .onFailure { Log.w("MixStore", "could not read the sets: ${it.message}") }
            .getOrDefault(emptyList())
    }

    fun create(name: String, now: Long = System.currentTimeMillis()): MixSet {
        val cleaned = name.trim().ifEmpty { "untitled" }
        val set = MixSet(id = UUID.randomUUID().toString(), name = cleaned, createdAt = now)
        write(sets() + set)
        return set
    }

    fun rename(setId: String, name: String): List<MixSet> =
        update(setId) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun delete(setId: String): List<MixSet> {
        val remaining = sets().filterNot { it.id == setId }
        write(remaining)
        return remaining
    }

    fun addTrack(setId: String, track: MixTrack): List<MixSet> =
        update(setId) { set ->
            if (set.tracks.any { it.id == track.id }) set else set.copy(tracks = set.tracks + track)
        }

    fun removeTrack(setId: String, trackId: String): List<MixSet> =
        update(setId) { set -> set.copy(tracks = set.tracks.filterNot { it.id == trackId }) }

    /** Moves [trackId] by [offset] places, clamped so the ends do not wrap. */
    fun moveTrack(setId: String, trackId: String, offset: Int): List<MixSet> = update(setId) { set ->
        val from = set.tracks.indexOfFirst { it.id == trackId }
        if (from < 0) return@update set
        val to = (from + offset).coerceIn(0, set.tracks.lastIndex)
        if (to == from) return@update set
        val reordered = set.tracks.toMutableList().apply { add(to, removeAt(from)) }
        set.copy(tracks = reordered)
    }

    /** Writes (or clears) the tempo and key of one track after a lookup or a manual edit. */
    fun recordAnalysis(setId: String, trackId: String, bpm: Double?, camelot: String?): List<MixSet> =
        update(setId) { set ->
            val tracks = set.tracks.map { track ->
                if (track.id != trackId) track else track.copy(bpm = bpm, camelot = camelot)
            }
            set.copy(tracks = tracks)
        }

    private fun update(setId: String, transform: (MixSet) -> MixSet): List<MixSet> {
        val current = sets()
        if (current.none { it.id == setId }) return current
        val updated = current.map { if (it.id == setId) transform(it) else it }
        // Nothing changed: rewriting the file would only risk a torn write for no new fact.
        if (updated == current) return current
        write(updated)
        return updated
    }

    private fun write(sets: List<MixSet>) {
        val payload = json.encodeToString(MixStoreFile.serializer(), MixStoreFile(sets = sets))
        runCatching {
            AppDirs.config.mkdirs()
            val temp = File(AppDirs.config, "mix-sets.json.tmp")
            temp.writeText(payload)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Log.w("MixStore", "could not save the sets: ${it.message}") }
    }
}
