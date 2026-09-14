package api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import util.AppDirs
import util.Log
import java.io.File

/**
 * Persists the set of protected track keys, so rows are locked from the first list after a
 * restart instead of only once each listing has been parsed again. Same temp-file-and-rename
 * write as the playback state: a kill mid-write cannot leave a truncated file behind.
 */
internal object ProtectedStreamStore {
    private const val TAG = "ProtectedStreamStore"
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(AppDirs.state, "protected-streams.json")

    fun load(): Set<String> = runCatching {
        val f = file
        if (!f.exists()) return emptySet()
        json.decodeFromString<List<String>>(f.readText()).toSet()
    }.onFailure { Log.w(TAG, "could not restore protected streams: ${it.message}") }.getOrDefault(emptySet())

    @Synchronized
    fun save(keys: Set<String>) {
        runCatching {
            val target = file
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json.encodeToString(keys.sorted()))
            if (!tmp.renameTo(target)) {
                target.writeText(json.encodeToString(keys.sorted()))
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not save protected streams: ${it.message}") }
    }
}
