package api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SoundCloudCollectionTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `playlist maps to playlist id with upscaled artwork`() {
        val c = SoundCloud.parseCollection(obj("""{"kind":"playlist","id":42,"title":"Mix","track_count":30,
            "artwork_url":"https://i1.sndcdn.com/artworks-x-large.jpg","user":{"username":"dj"}}"""))!!
        assertEquals("playlist:42", c.id)
        assertEquals("Mix", c.title)
        assertEquals(30, c.trackCount)
        assertEquals("dj", c.subtitle)
        assertEquals("https://i1.sndcdn.com/artworks-x-t500x500.jpg", c.artworkUrl)
    }

    @Test
    fun `system playlist maps to its urn and counts embedded stubs`() {
        val c = SoundCloud.parseCollection(obj("""{"kind":"system-playlist","urn":"soundcloud:system-playlists:trending:trap",
            "title":"Trap","description":"Hot now","calculated_artwork_url":"https://x/a-large.jpg","tracks":[{"id":1},{"id":2}]}"""))!!
        assertEquals("system:soundcloud:system-playlists:trending:trap", c.id)
        assertEquals(2, c.trackCount)
        assertEquals("Hot now", c.subtitle)
    }

    @Test
    fun `unknown kinds are ignored`() {
        assertNull(SoundCloud.parseCollection(obj("""{"kind":"track","id":1,"title":"x"}""")))
    }
}
