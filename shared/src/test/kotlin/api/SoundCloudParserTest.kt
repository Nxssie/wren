package api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import models.Source
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SoundCloudParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `should parse a valid SoundCloud track`() {
        val raw = """
            {
                "id": 181713573,
                "title": "Burial - Forgive",
                "duration": 187840,
                "full_duration": 187863,
                "permalink_url": "https://soundcloud.com/tooore/burial-forgive",
                "playback_count": 344973,
                "genre": "Burial",
                "streamable": true,
                "policy": "ALLOW",
                "artwork_url": "https://i1.sndcdn.com/artworks-pf2lWW2aSkwXtk73-3YzsiA-large.jpg",
                "user": {
                    "username": "tooore",
                    "avatar_url": "https://i1.sndcdn.com/u-000000000-large.jpg"
                }
            }
        """.trimIndent()
        val obj = json.parseToJsonElement(raw).jsonObject
        val result = parseScTrack(obj)!!

        assertEquals("https://soundcloud.com/tooore/burial-forgive", result.videoId)
        assertEquals("Burial - Forgive", result.title)
        assertEquals("tooore", result.artist)
        assertNull(result.artistId)
        assertEquals("3:07", result.duration)
        assertEquals(Source.SOUNDCLOUD, result.source)
        assertEquals(344973L, result.viewCount)
        assertEquals(181713573L, result.soundcloudId)
        assertEquals("Burial", result.genre)
        // artwork_url: -large → -t500x500
        assertEquals(
            "https://i1.sndcdn.com/artworks-pf2lWW2aSkwXtk73-3YzsiA-t500x500.jpg",
            result.thumbnailUrl
        )
    }

    @Test
    fun `should filter SNIPPET policy tracks`() {
        val raw = """
            {
                "id": 1,
                "title": "Snippet Track",
                "duration": 30000,
                "full_duration": 30000,
                "permalink_url": "https://soundcloud.com/u/snippet",
                "streamable": true,
                "policy": "SNIPPET",
                "user": {"username": "u"}
            }
        """.trimIndent()
        val obj = json.parseToJsonElement(raw).jsonObject
        assertNull(parseScTrack(obj))
    }

    @Test
    fun `should filter BLOCK policy tracks`() {
        val raw = """
            {
                "id": 2,
                "title": "Blocked Track",
                "duration": 60000,
                "full_duration": 60000,
                "permalink_url": "https://soundcloud.com/u/blocked",
                "streamable": true,
                "policy": "BLOCK",
                "user": {"username": "u"}
            }
        """.trimIndent()
        val obj = json.parseToJsonElement(raw).jsonObject
        assertNull(parseScTrack(obj))
    }

    @Test
    fun `should filter non-streamable tracks`() {
        val raw = """
            {
                "id": 3,
                "title": "Unstreamable",
                "duration": 60000,
                "full_duration": 60000,
                "permalink_url": "https://soundcloud.com/u/nope",
                "streamable": false,
                "user": {"username": "u"}
            }
        """.trimIndent()
        val obj = json.parseToJsonElement(raw).jsonObject
        assertNull(parseScTrack(obj))
    }

    @Test
    fun `should allow MONETIZE policy tracks`() {
        val raw = """
            {
                "id": 4,
                "title": "Monetized",
                "duration": 120000,
                "full_duration": 120000,
                "permalink_url": "https://soundcloud.com/u/monetized",
                "playback_count": 999,
                "streamable": true,
                "policy": "MONETIZE",
                "user": {"username": "u"}
            }
        """.trimIndent()
        val obj = json.parseToJsonElement(raw).jsonObject
        assertNotNull(parseScTrack(obj))
    }

    @Test
    fun `should use avatar_url fallback when artwork_url is null`() {
        val raw = """
            {
                "id": 5,
                "title": "No Artwork",
                "duration": 60000,
                "full_duration": 60000,
                "permalink_url": "https://soundcloud.com/u/no-art",
                "streamable": true,
                "user": {"username": "u", "avatar_url": "https://i1.sndcdn.com/avatar-large.jpg"}
            }
        """.trimIndent()
        val obj = json.parseToJsonElement(raw).jsonObject
        val result = parseScTrack(obj)!!
        assertEquals("https://i1.sndcdn.com/avatar-t500x500.jpg", result.thumbnailUrl)
    }

    @Test
    fun `formatMs should format minutes and seconds`() {
        assertEquals("3:07", formatMs(187840))
        assertEquals("0:00", formatMs(0))
        assertEquals("1:00", formatMs(60000))
        assertEquals("12:34", formatMs(754000))
    }

    @Test
    fun `formatMs should format hours`() {
        assertEquals("1:05:30", formatMs(3930000))
        assertEquals("2:30:00", formatMs(9000000))
    }

    @Test
    fun `should parse charts collection (unwrapped track)`() {
        val raw = """
            {
                "collection": [
                    {
                        "track": {
                            "id": 100,
                            "title": "Charted Track",
                            "duration": 240000,
                            "full_duration": 240000,
                            "permalink_url": "https://soundcloud.com/u/charted",
                            "playback_count": 5000,
                            "streamable": true,
                            "policy": "ALLOW",
                            "user": {"username": "u"}
                        }
                    }
                ]
            }
        """.trimIndent()
        val root = json.parseToJsonElement(raw).jsonObject
        val collection = root["collection"]!!.jsonArray
        val result = collection.mapNotNull { parseScTrack(it.jsonObject["track"]!!.jsonObject) }
        assertEquals(1, result.size)
        assertEquals("Charted Track", result[0].title)
    }
}
