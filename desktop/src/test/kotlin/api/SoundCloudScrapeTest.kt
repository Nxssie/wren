package api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SoundCloudScrapeTest {

    @Test
    fun `should extract asset URLs from HTML`() {
        val html = """
            <script crossorigin src="https://a-v2.sndcdn.com/assets/2-a84b92f5.js"></script>
            <script crossorigin src="https://a-v2.sndcdn.com/assets/55-70f3b3d1.js"></script>
        """.trimIndent()
        val urls = extractAssetUrls(html)
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("2-a84b92f5.js"))
        assertTrue(urls[1].contains("55-70f3b3d1.js"))
    }

    @Test
    fun `should return empty for HTML with no assets`() {
        assertEquals(emptyList<String>(), extractAssetUrls("<html></html>"))
    }

    @Test
    fun `should extract client_id from JS asset`() {
        val js = "var x={};x.client_id=\"Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo\";module.exports=x;"
        assertEquals("Pb72ranhoyt6gw7hM7TkzUItXlMWSNSo", extractClientId(js))
    }

    @Test
    fun `should extract client_id from minified assignment`() {
        val js = """client_id:"AbCdEfGhIjKlMnOpQrStUvWxYz012345"""".trimIndent()
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYz012345", extractClientId(js))
    }

    @Test
    fun `should return null when no client_id in JS`() {
        assertNull(extractClientId("var x = {};"))
    }
}
