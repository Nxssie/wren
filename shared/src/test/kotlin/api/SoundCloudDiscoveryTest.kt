package api

import models.SearchResult
import models.Source
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SoundCloudDiscoveryTest {

    private fun track(id: Long, title: String = "Track $id", genre: String? = null) = SearchResult(
        videoId = "https://soundcloud.com/u/track-$id",
        title = title,
        artist = "Artist $id",
        duration = "3:00",
        source = Source.SOUNDCLOUD,
        soundcloudId = id,
        genre = genre
    )

    @Test
    fun `should deduplicate by videoId`() {
        val seed1 = track(1)
        val seed2 = track(2)
        val related1 = listOf(track(3), track(4), track(1)) // track 1 is dup of seed
        val related2 = listOf(track(5), track(4))            // track 4 is dup within related
        val trending = listOf(track(6), track(7))

        val result = SoundCloudDiscovery.mergeCandidates(
            seedLists = listOf(related1, related2),
            trending = trending,
            excludeIds = setOf("1") // exclude seed1's id
        )

        val ids = result.map { it.soundcloudId }
        assertEquals(listOf(3L, 5L, 4L, 6L, 7L), ids)
    }

    @Test
    fun `should exclude ids in excludeIds set`() {
        val related = listOf(track(10), track(11), track(12))
        val trending = listOf(track(13))

        val result = SoundCloudDiscovery.mergeCandidates(
            seedLists = listOf(related),
            trending = trending,
            excludeIds = setOf("11", "13")
        )

        val ids = result.map { it.soundcloudId }
        assertEquals(listOf(10L, 12L), ids)
    }

    @Test
    fun `should interleave round-robin across seed lists`() {
        val list1 = listOf(track(1), track(2), track(3))
        val list2 = listOf(track(10), track(20), track(30))

        val result = SoundCloudDiscovery.mergeCandidates(
            seedLists = listOf(list1, list2),
            trending = emptyList(),
            excludeIds = emptySet()
        )

        // Round-robin: list1[0], list2[0], list1[1], list2[1], list1[2], list2[2]
        assertEquals(listOf(1L, 10L, 2L, 20L, 3L, 30L), result.map { it.soundcloudId })
    }

    @Test
    fun `should fill from trending when seed lists exhausted`() {
        val related = listOf(track(1))
        val trending = listOf(track(100), track(101))

        val result = SoundCloudDiscovery.mergeCandidates(
            seedLists = listOf(related),
            trending = trending,
            excludeIds = emptySet()
        )

        assertEquals(listOf(1L, 100L, 101L), result.map { it.soundcloudId })
    }

    @Test
    fun `should handle empty inputs`() {
        val result = SoundCloudDiscovery.mergeCandidates(
            seedLists = emptyList(),
            trending = emptyList(),
            excludeIds = emptySet()
        )
        assertTrue(result.isEmpty())
    }
}
