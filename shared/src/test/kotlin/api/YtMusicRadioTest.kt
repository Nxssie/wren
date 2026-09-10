package api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class YtMusicRadioTest {

    private val body = """
        {"contents":{"x":{"playlistPanelRenderer":{"contents":[
          {"playlistPanelVideoRenderer":{"videoId":"abc","title":{"runs":[{"text":"Song A"}]},
            "longBylineText":{"runs":[{"text":"Artist A","navigationEndpoint":{"browseEndpoint":{"browseId":"UC1"}}},{"text":" • "},{"text":"1.8B views"}]},
            "lengthText":{"runs":[{"text":"3:24"}]},
            "thumbnail":{"thumbnails":[{"url":"small","width":100},{"url":"big","width":800}]}}},
          {"playlistPanelVideoRenderer":{"videoId":"abc","title":{"runs":[{"text":"Song A dup"}]}}},
          {"playlistPanelVideoRenderer":{"videoId":"def","title":{"runs":[{"text":"Song B"}]}}}
        ]}}}}
    """.trimIndent()

    @Test
    fun `parses items anywhere in the tree, dedupes by videoId`() {
        val r = parseRadio(body, 50)
        assertEquals(listOf("abc", "def"), r.map { it.videoId })
        val a = r[0]
        assertEquals("Song A", a.title)
        assertEquals("Artist A", a.artist)
        assertEquals("UC1", a.artistId)
        assertEquals("3:24", a.duration)
        assertEquals("big", a.thumbnailUrl)
        assertEquals(1_800_000_000L, a.viewCount)
    }

    @Test
    fun `missing fields fall back sanely`() {
        val b = parseRadio(body, 50)[1]
        assertEquals("Unknown", b.artist)
        assertEquals("", b.duration)
        assertTrue(b.thumbnailUrl.contains("def"))
    }

    @Test
    fun `compact counts`() {
        assertEquals(205_000_000L, parseCompactCount("205M views"))
        assertEquals(12_300L, parseCompactCount("12.3K views"))
        assertEquals(42L, parseCompactCount("42 views"))
        assertNull(parseCompactCount("no number"))
    }
}
