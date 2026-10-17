package mix

import models.Source
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.AppDirs
import java.io.File
import java.util.concurrent.TimeUnit

class MixAnalysisTest {

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

    private fun track(bpm: Double? = null, camelot: String? = null) = MixTrack(
        id = "YT_MUSIC:abc",
        videoId = "abc",
        title = "Strobe",
        artist = "deadmau5",
        source = Source.YT_MUSIC,
        bpm = bpm,
        camelot = camelot
    )

    private class FakeProvider(
        private val answer: BpmKey?,
        override val isConfigured: Boolean = true
    ) : BpmKeyProvider {
        var calls = 0
        override val name = "fake"
        override fun lookup(artist: String, title: String): BpmKey? {
            calls++
            return answer
        }
    }

    // ── Resolution ───────────────────────────────────────────────────────────

    @Test
    fun `a hit is fetched once and then served from the cache`() {
        val provider = FakeProvider(BpmKey(bpm = 128.0, camelot = CamelotKey(8, "A"), provider = "fake"))
        val now = 1_700_000_000_000L

        val first = MixAnalysis.resolve(track(), provider, now = now)
        val second = MixAnalysis.resolve(track(), provider, now = now + 1_000L)

        assertEquals(128.0, first?.bpm)
        assertEquals(CamelotKey(8, "A"), second?.camelot)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `a miss is remembered and not retried before it expires`() {
        val provider = FakeProvider(answer = null)
        val now = 1_700_000_000_000L

        assertNull(MixAnalysis.resolve(track(), provider, now = now))
        assertNull(MixAnalysis.resolve(track(), provider, now = now + TimeUnit.HOURS.toMillis(1)))
        assertEquals(1, provider.calls)
    }

    @Test
    fun `a miss is retried once it expires`() {
        val provider = FakeProvider(answer = null)
        val now = 1_700_000_000_000L

        assertNull(MixAnalysis.resolve(track(), provider, now = now))
        assertNull(MixAnalysis.resolve(track(), provider, now = now + TimeUnit.DAYS.toMillis(8)))
        assertEquals(2, provider.calls)
    }

    @Test
    fun `a track that already carries analysis is never looked up`() {
        val provider = FakeProvider(BpmKey(bpm = 100.0, camelot = CamelotKey(3, "B"), provider = "fake"))

        val answer = MixAnalysis.resolve(track(bpm = 128.0, camelot = "8A"), provider, now = 1L)

        assertEquals(128.0, answer?.bpm)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `an unconfigured provider leaves the track unknown and stores nothing`() {
        val provider = FakeProvider(answer = null, isConfigured = false)

        assertNull(MixAnalysis.resolve(track(), provider, now = 1L))
        assertNull(MixAnalysis.cached("YT_MUSIC:abc", now = 2L))
        assertEquals(0, provider.calls)
    }

    @Test
    fun `a provider that could not answer is asked again instead of being cached as a miss`() {
        var calls = 0
        val unavailable = object : BpmKeyProvider {
            override val name = "unavailable"
            override val isConfigured = true
            override fun lookup(artist: String, title: String): BpmKey? {
                calls++
                throw ProviderUnavailableException("no audio decoded")
            }
        }

        assertNull(MixAnalysis.resolve(track(), unavailable))
        assertNull(MixAnalysis.resolve(track(), unavailable))

        assertEquals(2, calls, "a failure is not a fact about the track, so it must not be remembered")
    }

    @Test
    fun `no provider at all leaves the track unknown`() {
        assertNull(MixAnalysis.resolve(track(), provider = null, now = 1L))
    }

    // ── Cache durability ─────────────────────────────────────────────────────

    @Test
    fun `the cache survives a restart`() {
        MixAnalysis.resolve(
            track(),
            FakeProvider(BpmKey(bpm = 122.0, camelot = CamelotKey(4, "A"), provider = "fake")),
            now = 1_700_000_000_000L
        )

        val served = MixAnalysis.resolve(track(), provider = null, now = 1_700_000_000_500L)

        assertEquals(122.0, served?.bpm)
        assertEquals(CamelotKey(4, "A"), served?.camelot)
    }

    @Test
    fun `a corrupted cache degrades to empty instead of throwing`() {
        File(tempDir, "mix-analysis.json").writeText("{ nope")

        assertNull(MixAnalysis.cached("YT_MUSIC:abc", now = 1L))
        assertNotNull(
            MixAnalysis.resolve(
                track(),
                FakeProvider(BpmKey(bpm = 128.0, camelot = CamelotKey(8, "A"), provider = "fake")),
                now = 2L
            )
        )
    }

    @Test
    fun `clearing forgets every entry`() {
        MixAnalysis.resolve(
            track(),
            FakeProvider(BpmKey(bpm = 128.0, camelot = CamelotKey(8, "A"), provider = "fake")),
            now = 1_700_000_000_000L
        )
        MixAnalysis.clear()
        assertNull(MixAnalysis.cached("YT_MUSIC:abc", now = 1_700_000_000_000L))
    }
}
