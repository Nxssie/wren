package mix

import models.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MixModelsTest {

    private fun track(source: Source, videoId: String = "abc") = MixTrack(
        id = "${source.name}:$videoId",
        videoId = videoId,
        title = "title",
        artist = "artist",
        source = source,
        artworkUrl = "https://example.test/art.jpg",
        bpm = 128.0,
        camelot = "8A"
    )

    @Test
    fun `each source yields the url the queue expects`() {
        assertEquals("https://music.youtube.com/watch?v=abc", track(Source.YT_MUSIC).url)
        assertEquals("https://www.youtube.com/watch?v=abc", track(Source.YOUTUBE).url)
        assertEquals("abc", track(Source.SOUNDCLOUD).url)
    }

    @Test
    fun `a set track becomes a queue item with its analysis left behind`() {
        val item = track(Source.YT_MUSIC).toQueueItem()

        assertEquals("https://music.youtube.com/watch?v=abc", item.url)
        assertEquals("abc", item.videoId)
        assertEquals("title", item.title)
        assertEquals("artist", item.artist)
        assertEquals("https://example.test/art.jpg", item.artworkUrl)
        assertEquals(Source.YT_MUSIC, item.source)
    }

    @Test
    fun `a track is analysable once it has a tempo or a key`() {
        assertEquals(true, track(Source.YT_MUSIC).isAnalysed)
        assertEquals(true, track(Source.YT_MUSIC).copy(bpm = null).isAnalysed)
        assertEquals(true, track(Source.YT_MUSIC).copy(camelot = null).isAnalysed)
        assertEquals(false, track(Source.YT_MUSIC).copy(bpm = null, camelot = null).isAnalysed)
        assertNull(track(Source.YT_MUSIC).copy(camelot = null).key)
    }
}
