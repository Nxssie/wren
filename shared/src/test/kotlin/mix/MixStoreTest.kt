package mix

import models.SearchResult
import models.Source
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.AppDirs
import java.io.File

class MixStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var previousConfig: File
    private lateinit var previousState: File

    @BeforeEach
    fun pointAppDirsAtTempDir() {
        previousConfig = AppDirs.config
        previousState = AppDirs.state
        AppDirs.init(configDir = tempDir, stateDir = File(tempDir, "state"))
    }

    @AfterEach
    fun restoreAppDirs() {
        AppDirs.init(configDir = previousConfig, stateDir = previousState)
    }

    private fun track(id: String, bpm: Double? = null, camelot: String? = null) = MixTrack(
        id = id,
        videoId = id,
        title = "title $id",
        artist = "artist",
        source = Source.YT_MUSIC,
        bpm = bpm,
        camelot = camelot
    )

    // ── Sets ─────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh install has no sets`() {
        assertEquals(emptyList<MixSet>(), MixStore.sets())
    }

    @Test
    fun `a created set reads back after a reload`() {
        val created = MixStore.create("warmup", now = 1_700_000_000_000L)
        assertEquals("warmup", created.name)
        assertEquals(1_700_000_000_000L, created.createdAt)
        assertEquals(listOf(created), MixStore.sets())
    }

    @Test
    fun `two sets get distinct ids`() {
        val first = MixStore.create("warmup", now = 1L)
        val second = MixStore.create("peak", now = 1L)
        assertTrue(first.id != second.id)
        assertEquals(2, MixStore.sets().size)
    }

    @Test
    fun `renaming keeps the tracks and the order`() {
        val set = MixStore.create("warmup", now = 1L)
        MixStore.addTrack(set.id, track("a"))
        MixStore.rename(set.id, "peak time")
        val reloaded = MixStore.sets().single()
        assertEquals("peak time", reloaded.name)
        assertEquals(listOf("a"), reloaded.tracks.map { it.id })
    }

    @Test
    fun `deleting a set removes only that set`() {
        val first = MixStore.create("warmup", now = 1L)
        val second = MixStore.create("peak", now = 2L)
        MixStore.delete(first.id)
        assertEquals(listOf(second.id), MixStore.sets().map { it.id })
    }

    // ── Tracks in a set ──────────────────────────────────────────────────────

    @Test
    fun `a track added twice appears once`() {
        val set = MixStore.create("warmup", now = 1L)
        MixStore.addTrack(set.id, track("a"))
        MixStore.addTrack(set.id, track("a"))
        assertEquals(listOf("a"), MixStore.sets().single().tracks.map { it.id })
    }

    @Test
    fun `adding to a set that no longer exists changes nothing`() {
        val set = MixStore.create("warmup", now = 1L)
        MixStore.delete(set.id)
        MixStore.addTrack(set.id, track("a"))
        assertEquals(emptyList<MixSet>(), MixStore.sets())
    }

    @Test
    fun `tracks keep the order they were added in`() {
        val set = MixStore.create("warmup", now = 1L)
        listOf("a", "b", "c").forEach { MixStore.addTrack(set.id, track(it)) }
        assertEquals(listOf("a", "b", "c"), MixStore.sets().single().tracks.map { it.id })
    }

    @Test
    fun `removing a track keeps the others`() {
        val set = MixStore.create("warmup", now = 1L)
        listOf("a", "b", "c").forEach { MixStore.addTrack(set.id, track(it)) }
        MixStore.removeTrack(set.id, "b")
        assertEquals(listOf("a", "c"), MixStore.sets().single().tracks.map { it.id })
    }

    @Test
    fun `moving a track reorders it and clamps at the ends`() {
        val set = MixStore.create("warmup", now = 1L)
        listOf("a", "b", "c").forEach { MixStore.addTrack(set.id, track(it)) }

        MixStore.moveTrack(set.id, "c", -1)
        assertEquals(listOf("a", "c", "b"), MixStore.sets().single().tracks.map { it.id })

        MixStore.moveTrack(set.id, "a", -1)
        MixStore.moveTrack(set.id, "b", +1)
        assertEquals(listOf("a", "c", "b"), MixStore.sets().single().tracks.map { it.id })
    }

    // ── Analysis fields ──────────────────────────────────────────────────────

    @Test
    fun `recording analysis keeps the rest of the track intact`() {
        val set = MixStore.create("warmup", now = 1L)
        MixStore.addTrack(set.id, track("a"))

        MixStore.recordAnalysis(set.id, "a", bpm = 128.0, camelot = "8A")

        val stored = MixStore.sets().single().tracks.single()
        assertEquals(128.0, stored.bpm)
        assertEquals(CamelotKey(8, "A"), stored.key)
        assertEquals("title a", stored.title)
        assertEquals(Source.YT_MUSIC, stored.source)
    }

    @Test
    fun `clearing analysis leaves the track without a key`() {
        val set = MixStore.create("warmup", now = 1L)
        MixStore.addTrack(set.id, track("a", bpm = 128.0, camelot = "8A"))

        MixStore.recordAnalysis(set.id, "a", bpm = null, camelot = null)

        val stored = MixStore.sets().single().tracks.single()
        assertNull(stored.bpm)
        assertNull(stored.key)
    }

    // ── Durability ───────────────────────────────────────────────────────────

    @Test
    fun `an unreadable file degrades to no sets instead of throwing`() {
        File(tempDir, "mix-sets.json").writeText("{ this is not json")
        assertEquals(emptyList<MixSet>(), MixStore.sets())
    }

    @Test
    fun `a set survives being read back from disk`() {
        val set = MixStore.create("warmup", now = 1L)
        MixStore.addTrack(set.id, track("a", bpm = 124.0, camelot = "9A"))
        val reloaded = MixStore.sets().single()
        assertEquals(124.0, reloaded.tracks.single().bpm)
        assertEquals(CamelotKey(9, "A"), reloaded.tracks.single().key)
    }

    // ── Model glue ───────────────────────────────────────────────────────────

    @Test
    fun `a search result becomes a track identified by source and id`() {
        val result = SearchResult(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            duration = "3:33",
            thumbnailUrl = "https://example.test/art.jpg",
            source = Source.YT_MUSIC
        )

        val converted = result.toMixTrack()

        assertEquals("YT_MUSIC:dQw4w9WgXcQ", converted.id)
        assertEquals("Never Gonna Give You Up", converted.title)
        assertEquals("Rick Astley", converted.artist)
        assertEquals("https://example.test/art.jpg", converted.artworkUrl)
        assertNotNull(converted.duration)
        assertNull(converted.bpm)
        assertNull(converted.key)
    }
}
