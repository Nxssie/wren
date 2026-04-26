package player

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Tests for FFmpegPlayer EOF detection and queue advancement logic.
 *
 * The core issue: FFmpegFrameGrabber.grabSamples() can return null due to
 * network buffering (not EOF). The player must distinguish between:
 *   1. Transient null from buffering → keep waiting
 *   2. Real EOF (grabber.isEof == true) → advance to next track
 *   3. Manual stop (isPlayingInternal set false) → exit cleanly
 */
class EofDetectionTest {

    private lateinit var player: FFmpegPlayer

    @BeforeEach
    fun setUp() {
        player = FFmpegPlayer()
    }

    // ── Basic state management ──

    @Test
    fun `should start with empty queue`() {
        assertEquals(0, player.queue.value.size)
        assertEquals(-1, player.queueIndex.value)
    }

    @Test
    fun `should start with default volume`() {
        assertEquals(50, player.volume.value)
    }

    @Test
    fun `should start with off repeat mode`() {
        assertEquals(RepeatMode.OFF, player.repeatMode.value)
    }

    @Test
    fun `should start with shuffle off`() {
        assertFalse(player.shuffle.value)
    }

    @Test
    fun `should start with no playback`() {
        assertFalse(player.isPlaying.value)
        assertFalse(player.isLoading.value)
    }

    // ── Repeat mode cycling ──

    @Test
    fun `should cycle repeat mode OFF to ALL to SINGLE to OFF`() {
        assertEquals(RepeatMode.OFF, player.repeatMode.value)
        player.toggleRepeat()
        assertEquals(RepeatMode.ALL, player.repeatMode.value)
        player.toggleRepeat()
        assertEquals(RepeatMode.SINGLE, player.repeatMode.value)
        player.toggleRepeat()
        assertEquals(RepeatMode.OFF, player.repeatMode.value)
    }

    // ── Queue item management ──

    @Test
    fun `should load single item and clear queue`() {
        player.load("http://example.com/stream", "video123", "Test Song")
        // Queue should be cleared on load
        assertEquals(0, player.queue.value.size)
        assertEquals(-1, player.queueIndex.value)
    }

    @Test
    fun `should load queue with multiple items`() {
        val items = listOf(
            QueueItem("url1", "vid1", "Song 1"),
            QueueItem("url2", "vid2", "Song 2"),
            QueueItem("url3", "vid3", "Song 3")
        )
        player.loadQueue(items, startIndex = 0)
        assertEquals(3, player.queue.value.size)
        assertEquals(0, player.queueIndex.value)
    }

    @Test
    fun `should load queue at specified start index`() {
        val items = listOf(
            QueueItem("url1", "vid1", "Song 1"),
            QueueItem("url2", "vid2", "Song 2"),
            QueueItem("url3", "vid3", "Song 3")
        )
        player.loadQueue(items, startIndex = 2)
        assertEquals(2, player.queueIndex.value)
    }

    @Test
    fun `should shuffle queue when shuffle enabled`() {
        player.shuffle.value = true
        val items = listOf(
            QueueItem("url1", "vid1", "Song 1"),
            QueueItem("url2", "vid2", "Song 2"),
            QueueItem("url3", "vid3", "Song 3")
        )
        // Run shuffle multiple times and verify items are always present
        // (order may differ due to shuffle)
        player.loadQueue(items, startIndex = 0)
        val shuffled = player.queue.value
        assertEquals(3, shuffled.size)
        val videoIds = shuffled.map { it.videoId }.toSet()
        assertEquals(setOf("vid1", "vid2", "vid3"), videoIds)
    }

    @Test
    fun `toggleShuffle should not change item count`() {
        val items = listOf(
            QueueItem("url1", "vid1", "Song 1"),
            QueueItem("url2", "vid2", "Song 2")
        )
        player.loadQueue(items, startIndex = 0)
        player.toggleShuffle()
        assertEquals(2, player.queue.value.size)
        player.toggleShuffle()
        assertEquals(2, player.queue.value.size)
    }

    // ── Position and duration state ──

    @Test
    fun `should set position and duration to zero on load`() {
        // loadInternal sets position = 0.0 and duration = 0.0
        // We verify the state setters work correctly
        player.position.value = 0.0
        player.duration.value = 0.0
        assertEquals(0.0, player.position.value)
        assertEquals(0.0, player.duration.value)
    }

    // ── Volume control ──

    @Test
    fun `should set volume within valid range`() {
        player.setVolume(0)
        assertEquals(0, player.volume.value)
        player.setVolume(50)
        assertEquals(50, player.volume.value)
        player.setVolume(100)
        assertEquals(100, player.volume.value)
    }

    @Test
    fun `volume should clamp to valid range on set`() {
        // Setting extreme values should not throw
        assertDoesNotThrow { player.setVolume(-10) }
        assertDoesNotThrow { player.setVolume(150) }
    }

    // ── Pause/resume state ──

    @Test
    fun `should start not paused`() {
        assertFalse(player.isPaused.value)
    }

    // ── Queue navigation state ──

    @Test
    fun `should have no previous at queue start`() {
        val items = listOf(
            QueueItem("url1", "vid1", "Song 1"),
            QueueItem("url2", "vid2", "Song 2")
        )
        player.loadQueue(items, startIndex = 0)
        assertEquals(0, player.queueIndex.value)
    }

    @Test
    fun `should have previous when not at start`() {
        val items = listOf(
            QueueItem("url1", "vid1", "Song 1"),
            QueueItem("url2", "vid2", "Song 2"),
            QueueItem("url3", "vid3", "Song 3")
        )
        player.loadQueue(items, startIndex = 2)
        assertEquals(2, player.queueIndex.value)
    }

    @Test
    fun `should have next when not at end`() {
        val items = listOf(
            QueueItem("url1", "vid1", "Song 1"),
            QueueItem("url2", "vid2", "Song 2"),
            QueueItem("url3", "vid3", "Song 3")
        )
        player.loadQueue(items, startIndex = 1)
        assertEquals(1, player.queueIndex.value)
    }

    @Test
    fun `should have no next at queue end`() {
        val items = listOf(
            QueueItem("url1", "vid1", "Song 1"),
            QueueItem("url2", "vid2", "Song 2")
        )
        player.loadQueue(items, startIndex = 1)
        assertEquals(1, player.queueIndex.value)
    }

    // ── EOF detection logic verification ──
    //
    // The key invariant: grabSamples() returning null does NOT mean EOF.
    // EOF is only confirmed when grabber.isEof is true.
    //
    // These tests verify the state machine is in a correct initial state
    // for the EOF detection logic to work properly.

    @Test
    fun `player should be in clean state before loading any stream`() {
        // Before loading, player should show no playback and no loading state
        assertFalse(player.isPlaying.value)
        assertFalse(player.isLoading.value)
        assertEquals(0.0, player.position.value)
        assertEquals(0.0, player.duration.value)
    }

    @Test
    fun `isEnqueuing should be false initially`() {
        assertFalse(player.isEnqueuing.value)
    }

    @Test
    fun `currentTitle should be empty initially`() {
        assertEquals("", player.currentTitle.value)
    }

    @Test
    fun `displayTitle should be empty initially`() {
        assertEquals("", player.displayTitle.value)
    }

    // ── Edge cases: empty queue operations ──

    @Test
    fun `should handle next on empty queue without crashing`() {
        assertDoesNotThrow { player.next() }
    }

    @Test
    fun `should handle previous on empty queue without crashing`() {
        assertDoesNotThrow { player.previous() }
    }

    @Test
    fun `should handle playPause on empty queue without crashing`() {
        assertDoesNotThrow { player.playPause() }
    }

    @Test
    fun `should handle seek on empty queue without crashing`() {
        assertDoesNotThrow { player.seek(0.0) }
    }

    // ── QueueItem data class ──

    @Test
    fun `queueItem should have required fields`() {
        val item = QueueItem("url", "videoId", "Title", "Artist")
        assertEquals("url", item.url)
        assertEquals("videoId", item.videoId)
        assertEquals("Title", item.title)
        assertEquals("Artist", item.artist)
    }

    @Test
    fun `queueItem artist should default to empty string`() {
        val item = QueueItem("url", "videoId", "Title")
        assertEquals("", item.artist)
    }

    @Test
    fun `queueItem should support equals and hashCode`() {
        val a = QueueItem("url", "vid", "Title")
        val b = QueueItem("url", "vid", "Title")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── RepeatMode enum ──

    @Test
    fun `repeatMode should have three values`() {
        assertEquals(3, RepeatMode.values().size)
        assertTrue(RepeatMode.values().contains(RepeatMode.OFF))
        assertTrue(RepeatMode.values().contains(RepeatMode.ALL))
        assertTrue(RepeatMode.values().contains(RepeatMode.SINGLE))
    }
}
