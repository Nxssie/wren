package api

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.QueueItem
import models.Source
import util.Log
import java.io.File

@Serializable
data class PlayRecord(
    val trackId: String,
    val title: String,
    val artist: String,
    val genre: String? = null,
    val artworkUrl: String? = null,
    val playedAt: Long,
    // Legacy entries predate multi-platform history and were all SoundCloud
    val source: Source = Source.SOUNDCLOUD
)

object ListeningHistory {
    private val configDir = File(System.getProperty("user.home"), ".config/wren")
    private val historyFile = File(configDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_ENTRIES = 500

    @Synchronized
    fun record(item: QueueItem) {
        val entries = load()
        // Skip if the last 3 entries are the same track (avoid repeat spam)
        val recentIds = entries.take(3).map { it.trackId }
        if (item.videoId in recentIds) return

        val record = PlayRecord(
            trackId = item.videoId,
            title = item.title,
            artist = item.artist,
            genre = item.genre,
            artworkUrl = item.artworkUrl,
            playedAt = System.currentTimeMillis(),
            source = item.source
        )
        val updated = (listOf(record) + entries).take(MAX_ENTRIES)
        save(updated)
    }

    /** Most recent plays, optionally restricted to the given sources. */
    fun recent(n: Int, sources: Set<Source>? = null): List<PlayRecord> {
        val all = load()
        return (if (sources == null) all else all.filter { it.source in sources }).take(n)
    }

    fun topGenres(limit: Int): List<Pair<String, Int>> {
        // Genres are only known for SoundCloud tracks
        val entries = load().filter { it.source == Source.SOUNDCLOUD }.take(100)
        return entries.mapNotNull { it.genre }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    fun playedTrackIdsSince(days: Int): Set<String> {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        return load().filter { it.playedAt >= cutoff }.map { it.trackId }.toSet()
    }

    private fun load(): List<PlayRecord> = runCatching {
        if (!historyFile.exists()) return emptyList()
        json.decodeFromString<List<PlayRecord>>(historyFile.readText())
    }.onFailure { Log.e("ListeningHistory", "Failed to load history", it) }.getOrDefault(emptyList())

    private fun save(entries: List<PlayRecord>) {
        configDir.mkdirs()
        runCatching {
            historyFile.writeText(json.encodeToString(ListSerializer(PlayRecord.serializer()), entries))
        }.onFailure { Log.e("ListeningHistory", "Failed to save history", it) }
    }
}
