package api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The titles are the shapes YouTube actually hands out: the noise is what made lrclib's exact
 * lookup miss lyrics that exist, and the kept cases are the ones that name a different recording.
 */
class LyricsTitleTest {

    @Test
    fun `video noise comes off the title`() {
        assertEquals("Red Flags", cleanTitle("Red Flags (Official Video)"))
        assertEquals("Creep", cleanTitle("Creep (Remastered)"))
        assertEquals("Creep", cleanTitle("Creep (Remastered 2011)"))
        assertEquals("Creep", cleanTitle("Creep [HD]"))
        assertEquals("Sweet Child O' Mine", cleanTitle("Sweet Child O' Mine (Official Music Video)"))
        assertEquals("Everlong", cleanTitle("Everlong (Official Audio)"))
        assertEquals("Numb", cleanTitle("Numb [4K]"))
    }

    @Test
    fun `the takes that change the recording stay`() {
        assertEquals("Song (Live)", cleanTitle("Song (Live)"))
        assertEquals("Song (Acoustic)", cleanTitle("Song (Acoustic)"))
        assertEquals("Song (feat. Someone)", cleanTitle("Song (feat. Someone)"))
        assertEquals("Song (Demo)", cleanTitle("Song (Demo)"))
    }

    @Test
    fun `a channel suffix is not part of the song`() {
        assertEquals("Track", cleanTitle("Track - Topic"))
        assertEquals("Radiohead", cleanArtist("Radiohead - Topic"))
        assertEquals("Artist", cleanArtist("ArtistVEVO"))
        assertEquals("Radiohead", cleanArtist("Radiohead"))
    }

    @Test
    fun `a title that is nothing but noise falls back to what was asked`() {
        assertEquals("(Official Video)", cleanTitle("(Official Video)"))
        assertEquals("", cleanArtist(""))
    }

    @Test
    fun `collapsing the removed extras leaves no double spaces`() {
        assertEquals("Song", cleanTitle("Song  (Official Video)  "))
        assertEquals("A B", cleanTitle("A (HD) B"))
    }
}
