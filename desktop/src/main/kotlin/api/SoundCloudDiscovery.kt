package api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import models.SearchResult
import util.Log
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Serializable
data class GeneratedDiscovery(
    val generatedAt: Long,
    val basisGenres: List<String>,
    val tracks: List<SearchResult>
)

object SoundCloudDiscovery {
    private val configDir = File(System.getProperty("user.home"), ".config/wren")
    private val cacheFile = File(configDir, "discovery.json")
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_TRACKS = 25

    suspend fun current(): GeneratedDiscovery {
        val cached = loadCache()
        if (cached != null && isSameWeek(cached.generatedAt)) return cached
        return refresh()
    }

    suspend fun refresh(): GeneratedDiscovery {
        val result = runCatching { generate() }
            .onFailure { Log.e("SoundCloudDiscovery", "Discovery generation failed", it) }
            .getOrDefault(basicDiscovery())
        saveCache(result)
        return result
    }

    private suspend fun generate(): GeneratedDiscovery {
        val excludeIds = ListeningHistory.playedTrackIdsSince(14)
        val seeds = buildSeeds()
        val basisGenres = ListeningHistory.topGenres(3).map { it.first }

        if (seeds.isEmpty()) {
            // Cold start — no SoundCloud history yet, use trending
            val trending = runCatching { SoundCloud.trendingWeekly(30) }.getOrDefault(emptyList())
            val tracks = trending.filter { it.videoId !in excludeIds }.take(MAX_TRACKS)
            return GeneratedDiscovery(
                generatedAt = System.currentTimeMillis(),
                basisGenres = listOf("trending"),
                tracks = tracks
            )
        }

        // Collect candidates: related tracks from each seed + global trending
        val seedLists = seeds.mapNotNull { seed ->
            runCatching { SoundCloud.relatedTracks(seed.soundcloudId!!, 12) }
                .onFailure { Log.w("SoundCloudDiscovery", "Failed to get related for seed ${seed.soundcloudId}", it) }
                .getOrNull()
        }
        val trending = runCatching { SoundCloud.trendingWeekly(30) }.getOrDefault(emptyList())

        val excludePlusSeeds = excludeIds + seeds.mapNotNull { it.soundcloudId?.toString() } + seeds.map { it.videoId }
        val merged = mergeCandidates(seedLists, trending, excludePlusSeeds)
        val tracks = merged.take(MAX_TRACKS)

        return GeneratedDiscovery(
            generatedAt = System.currentTimeMillis(),
            basisGenres = basisGenres,
            tracks = tracks
        )
    }

    private suspend fun basicDiscovery(): GeneratedDiscovery {
        val trending = runCatching { SoundCloud.trendingWeekly(25) }.getOrDefault(emptyList())
        return GeneratedDiscovery(
            generatedAt = System.currentTimeMillis(),
            basisGenres = listOf("trending"),
            tracks = trending
        )
    }

    private fun buildSeeds(): List<SearchResult> {
        val recent = ListeningHistory.recent(6, setOf(models.Source.SOUNDCLOUD))
        val byGenre = mutableMapOf<String, SearchResult>()
        val byArtist = mutableMapOf<String, SearchResult>()
        for (record in recent) {
            val genre = record.genre
            if (genre != null && genre !in byGenre && byGenre.size < 4) {
                byGenre[genre] = record.toSearchResult()
            }
            val artist = record.artist
            if (artist !in byArtist && byArtist.size < 4) {
                byArtist[artist] = record.toSearchResult()
            }
        }
        // Prefer genre-diverse seeds, fill from artist-diverse
        val result = mutableListOf<SearchResult>()
        result.addAll(byGenre.values)
        for (sr in byArtist.values) {
            if (result.size >= 4) break
            if (sr.videoId !in result.map { it.videoId }) result.add(sr)
        }
        return result
    }

    private fun PlayRecord.toSearchResult() = SearchResult(
        videoId = trackId,
        title = title,
        artist = artist,
        artistId = null,
        duration = "",
        thumbnailUrl = artworkUrl?.replace("-large.", "-t500x500.") ?: "",
        source = models.Source.SOUNDCLOUD,
        soundcloudId = trackId.toLongOrNull(),
        genre = genre
    )

    /** Round-robin interleave across seed lists then append trending to fill. */
    internal fun mergeCandidates(
        seedLists: List<List<SearchResult>>,
        trending: List<SearchResult>,
        excludeIds: Set<String>
    ): List<SearchResult> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SearchResult>()

        fun tryAdd(sr: SearchResult): Boolean {
            val id = sr.videoId
            if (id in seen || sr.soundcloudId?.toString() in excludeIds || id in excludeIds) return false
            seen += id
            result += sr
            return true
        }

        // Round-robin through seed lists
        val iterators = seedLists.map { it.iterator() }
        while (iterators.any { it.hasNext() }) {
            for (iter in iterators) {
                if (iter.hasNext()) tryAdd(iter.next())
            }
        }

        // Fill remaining from trending
        for (sr in trending) {
            if (result.size >= MAX_TRACKS) break
            tryAdd(sr)
        }

        return result
    }

    private fun isSameWeek(timestamp: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(timestamp)
        val date = LocalDate.ofInstant(instant, zone)
        val now = LocalDate.now(zone)
        return date.year == now.year && date.dayOfYear == now.dayOfYear ||
            (date >= now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) &&
                date <= now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)) &&
                now >= date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) &&
                now <= date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))
    }

    private fun loadCache(): GeneratedDiscovery? = runCatching {
        if (!cacheFile.exists()) return null
        json.decodeFromString(GeneratedDiscovery.serializer(), cacheFile.readText())
    }.getOrNull()

    private fun saveCache(discovery: GeneratedDiscovery) {
        configDir.mkdirs()
        runCatching {
            cacheFile.writeText(json.encodeToString(GeneratedDiscovery.serializer(), discovery))
        }.onFailure { Log.e("SoundCloudDiscovery", "Failed to save discovery cache", it) }
    }
}
