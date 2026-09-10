package player

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import models.QueueItem
import models.RepeatMode
import util.AppDirs
import util.Log
import java.io.File

/** Everything needed to put the player back where the user left it. */
@Serializable
data class SavedPlayback(
    val queue: List<QueueItem>,
    val index: Int,
    val positionSec: Double,
    val durationSec: Double = 0.0,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val queueTitle: String? = null,
)

/**
 * Persists [SavedPlayback] as JSON in the state dir. Writes go through a temp file and a
 * rename so a kill mid-write can never leave a half-written queue behind. Callers throttle;
 * this object just writes what it is given.
 */
object PlaybackStateStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(AppDirs.state, "playback.json")

    fun load(): SavedPlayback? = runCatching {
        val f = file
        if (!f.exists()) return null
        json.decodeFromString<SavedPlayback>(f.readText())
            .takeIf { it.queue.isNotEmpty() && it.index in it.queue.indices }
    }.onFailure { Log.w("PlaybackStateStore", "could not restore playback: ${it.message}") }.getOrNull()

    @Synchronized
    fun save(state: SavedPlayback) {
        runCatching {
            val target = file
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json.encodeToString(state))
            if (!tmp.renameTo(target)) {
                target.writeText(json.encodeToString(state))
                tmp.delete()
            }
        }.onFailure { Log.w("PlaybackStateStore", "could not save playback: ${it.message}") }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }
}
