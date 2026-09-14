package api

import models.ArtistResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YtMusicPersonalTest {

    private fun artist(name: String) = ArtistResult(browseId = name, name = name, thumbnailUrl = null, subtitle = "channel")

    private fun play(artist: String, trackId: String) =
        PlayRecord(trackId = trackId, title = "t", artist = artist, playedAt = 0L)

    @Test
    fun `the artists played most are asked for a release first`() {
        val followed = listOf(artist("Adele"), artist("Burial"), artist("Caribou"))

        val ranked = rankFollowedArtists(
            followed,
            playCountsOf(listOf(play("Burial", "1"), play("Burial", "2"), play("Adele", "3"))),
        )

        assertEquals(listOf("Burial", "Adele", "Caribou"), ranked.map { it.name })
    }

    @Test
    fun `an artist the user never played keeps its library order`() {
        val followed = listOf(artist("Adele"), artist("Burial"))

        val ranked = rankFollowedArtists(followed, emptyMap())

        assertEquals(listOf("Adele", "Burial"), ranked.map { it.name })
    }

    @Test
    fun `plays are counted regardless of how the artist name is cased`() {
        val counts = playCountsOf(listOf(play("Burial", "1"), play("BURIAL", "2"), play("", "3")))

        assertEquals(mapOf("burial" to 2), counts)
    }

    @Test
    fun `quick picks take one track from each seed before repeating`() {
        val mixed = roundRobin(
            listOf(
                listOf("a1", "a2", "a3"),
                listOf("b1"),
                listOf("c1", "c2"),
            )
        )

        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), mixed)
    }
}
