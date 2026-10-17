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

    @Test
    fun `a track with nothing analysed reads as unknown`() {
        assertEquals("unknown", track(Source.YT_MUSIC).copy(bpm = null, camelot = null).analysisLabel)
    }

    @Test
    fun `a track with only a tempo says its key is unknown`() {
        assertEquals("176.3 · ?", track(Source.YT_MUSIC).copy(bpm = 176.3, camelot = null).analysisLabel)
    }

    @Test
    fun `a whole-number tempo is not printed with decimals`() {
        assertEquals("176 · 4A", track(Source.YT_MUSIC).copy(bpm = 176.0, camelot = "4A").analysisLabel)
    }

    @Test
    fun `a track with a key but no tempo still shows both halves`() {
        assertEquals("? · 8B", track(Source.YT_MUSIC).copy(bpm = null, camelot = "8B").analysisLabel)
    }
}
