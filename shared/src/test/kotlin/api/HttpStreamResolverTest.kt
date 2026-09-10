package api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpStreamResolverTest {

    @Test
    fun `picks the highest bitrate m4a audio format`() {
        val body = """
            {"streamingData":{"adaptiveFormats":[
              {"itag":139,"mimeType":"audio/mp4; codecs=\"mp4a.40.5\"","bitrate":50152,"url":"https://cdn/low.m4a"},
              {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130677,"url":"https://cdn/best.m4a"},
              {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":136544,"url":"https://cdn/opus.webm"},
              {"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":4334157,"url":"https://cdn/video.mp4"}
            ]}}
        """.trimIndent()

        assertEquals("https://cdn/best.m4a", parsePlayerResponse(body))
    }

    @Test
    fun `skips formats that only carry a signature cipher`() {
        val body = """
            {"streamingData":{"adaptiveFormats":[
              {"itag":140,"mimeType":"audio/mp4","bitrate":130677,"signatureCipher":"s=abc&url=https%3A%2F%2Fcdn%2Fcipher.m4a"},
              {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":136544,"url":"https://cdn/opus.webm"}
            ]}}
        """.trimIndent()

        assertEquals("https://cdn/opus.webm", parsePlayerResponse(body))
    }

    @Test
    fun `falls back to a muxed format when no audio-only format has a url`() {
        val body = """
            {"streamingData":{
              "adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4","bitrate":130677,"signatureCipher":"s=x"}],
              "formats":[{"itag":18,"mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","bitrate":96000,"url":"https://cdn/muxed.mp4"}]
            }}
        """.trimIndent()

        assertEquals("https://cdn/muxed.mp4", parsePlayerResponse(body))
    }

    @Test
    fun `returns null when playback is blocked or streaming data is missing`() {
        assertNull(parsePlayerResponse("""{"playabilityStatus":{"status":"ERROR"}}"""))
        assertNull(parsePlayerResponse("not json"))
        assertNull(parsePlayerResponse("""{"streamingData":{"adaptiveFormats":[]}}"""))
    }
}
