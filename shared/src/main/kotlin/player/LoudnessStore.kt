package player

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import util.AppDirs
import util.Log
import java.io.File

/**
 * Remembers each track's measured level, so a track is normalised from the first second of
 * the *next* play. Measurements are refreshed as a track is listened to further, which means
 * they sharpen over time and never move the gain of the track currently playing — that is
 * what makes a reactive AGC audible, and why the gain is looked up once per track instead.
 */
object LoudnessStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(AppDirs.state, "loudness.json")

    /**
     * Levels are swapped wholesale rather than mutated, so a reader on the audio thread sees a
     * consistent snapshot without ever taking the lock a disk write holds.
     */
    @Volatile private var levels: Map<String, LoudnessMeasurement>? = null
    private val writeLock = Any()

    /** Loads the file now, so the audio path never waits on disk for the first lookup. */
    fun warmUp() {
        levels()
    }

    /** Gain to apply to [videoId], or null while the track has never been measured. */
    fun gain(videoId: String): Float? = levels()[videoId]?.let { Loudness.gain(it) }

    fun measurement(videoId: String): LoudnessMeasurement? = levels()[videoId]

    fun record(videoId: String, measurement: LoudnessMeasurement) {
        synchronized(writeLock) {
            val updated = levels() + (videoId to measurement)
            levels = updated
            write(updated)
        }
    }

    fun clear() {
        synchronized(writeLock) {
            levels = null
            runCatching { file.delete() }
        }
    }

    private fun levels(): Map<String, LoudnessMeasurement> {
        levels?.let { return it }
        synchronized(writeLock) {
            return levels ?: load().also { levels = it }
        }
    }

    private fun load(): Map<String, LoudnessMeasurement> = runCatching {
        val f = file
        if (!f.exists()) return@runCatching emptyMap()
        json.decodeFromString<Map<String, LoudnessMeasurement>>(f.readText())
    }.onFailure { Log.w("LoudnessStore", "could not read levels: ${it.message}") }
        .getOrElse { emptyMap() }

    /** Temp file + rename, same as [PlaybackStateStore]: a kill mid-write keeps the old levels. */
    private fun write(levels: Map<String, LoudnessMeasurement>) {
        runCatching {
            val target = file
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json.encodeToString(levels))
            if (!tmp.renameTo(target)) {
                target.writeText(json.encodeToString(levels))
                tmp.delete()
            }
        }.onFailure { Log.w("LoudnessStore", "could not save levels: ${it.message}") }
    }
}
