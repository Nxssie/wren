package api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The refusal that names a captcha, told apart from any other 403 body. */
class SoundCloudChallengeTest {

    @Test
    fun `the captcha url is read off the refusal`() {
        val body = """{"url":"https://geo.captcha-delivery.com/captcha/?initialCid=AHrlqAAAAAMAz7N6&cid=7Dk~5lMC&hash=A55FBF&t=fe&s=17434"}"""

        assertEquals(
            "https://geo.captcha-delivery.com/captcha/?initialCid=AHrlqAAAAAMAz7N6&cid=7Dk~5lMC&hash=A55FBF&t=fe&s=17434",
            SoundCloud.dataDomeChallengeUrl(body)
        )
    }

    @Test
    fun `escaped slashes are unescaped`() {
        val body = """{"url":"https:\/\/geo.captcha-delivery.com\/captcha\/?cid=x"}"""
        assertEquals("https://geo.captcha-delivery.com/captcha/?cid=x", SoundCloud.dataDomeChallengeUrl(body))
    }

    @Test
    fun `a refusal that is not a captcha yields nothing`() {
        assertNull(SoundCloud.dataDomeChallengeUrl("{}"))
        assertNull(SoundCloud.dataDomeChallengeUrl(""))
        assertNull(SoundCloud.dataDomeChallengeUrl("""{"errors":[{"error_message":"forbidden"}]}"""))
        assertNull(SoundCloud.dataDomeChallengeUrl("""{"url":"https://soundcloud.com/somewhere"}"""))
        assertNull(SoundCloud.dataDomeChallengeUrl("""{"url":"javascript:alert(1)//captcha-delivery.com"}"""))
    }

    @Test
    fun `a block is told apart from a captcha`() {
        assertTrue(SoundCloud.isDataDomeBlock("https://geo.captcha-delivery.com/captcha/?initialCid=A&cid=B&t=bv&s=1"))
        assertFalse(SoundCloud.isDataDomeBlock("https://geo.captcha-delivery.com/captcha/?initialCid=A&cid=B&t=fe&s=1"))
        assertFalse(SoundCloud.isDataDomeBlock("https://geo.captcha-delivery.com/captcha/?cid=t=bv"))
    }
}
