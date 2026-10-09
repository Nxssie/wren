package update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.AppDirs
import util.Http
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The checker is a policy layer over [UpdatePreferences]: it decides whether to ask GitHub, and
 * what to hand the UI. Both the network call and the clock are injected so no test touches the
 * network or waits on real time, and every test that can record a timestamp points [AppDirs] at
 * its own temp dir — a stale timestamp leaking between tests would silently satisfy a throttle.
 */
class UpdateCheckerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var previousConfig: File
    private lateinit var previousState: File

    @BeforeEach
    fun pointAppDirsAtTempDir() {
        // Save the caller's dirs so the rest of the suite gets them back when this class finishes.
        previousConfig = AppDirs.config
        previousState = AppDirs.state
        AppDirs.init(configDir = tempDir, stateDir = File(tempDir, "state"))
    }

    @AfterEach
    fun restoreAppDirs() {
        AppDirs.init(configDir = previousConfig, stateDir = previousState)
    }

    // ── Throttle ─────────────────────────────────────────────────────────────

    @Test
    fun `a fresh check timestamp suppresses the fetch entirely`() {
        UpdatePreferences.recordCheck(NOW - INTERVAL / 2)
        val calls = AtomicInteger(0)
        val checker = checker(fetch = { calls.incrementAndGet(); Http.Response(200, newerRelease) })

        runBlocking { checker.check() }

        // The assertion is the call count, not the state: "unchanged" alone would also pass if the
        // throttled path silently reached the network and threw the result away.
        assertEquals(0, calls.get())
        assertEquals(UpdateState.Idle, checker.state.value)
    }

    @Test
    fun `a never-recorded timestamp permits exactly one fetch`() {
        val calls = AtomicInteger(0)
        val checker = checker(fetch = { calls.incrementAndGet(); Http.Response(200, newerRelease) })

        runBlocking { checker.check() }

        assertEquals(1, calls.get())
    }

    @Test
    fun `a stale timestamp permits exactly one fetch`() {
        UpdatePreferences.recordCheck(NOW - INTERVAL - 1)
        val calls = AtomicInteger(0)
        val checker = checker(fetch = { calls.incrementAndGet(); Http.Response(200, newerRelease) })

        runBlocking { checker.check() }

        assertEquals(1, calls.get())
    }

    @Test
    fun `a forced check bypasses a fresh timestamp`() {
        UpdatePreferences.recordCheck(NOW)
        val calls = AtomicInteger(0)
        val checker = checker(fetch = { calls.incrementAndGet(); Http.Response(200, newerRelease) })

        runBlocking { checker.check(force = true) }

        assertEquals(1, calls.get())
    }

    // ── Newer, equal and older releases ──────────────────────────────────────

    @Test
    fun `a newer release becomes Available carrying the parsed release`() {
        val body = releaseJson("v0.7.0")
        val checker = checker(fetch = { Http.Response(200, body) })

        runBlocking { checker.check() }

        val state = checker.state.value
        assertInstanceOf(UpdateState.Available::class.java, state)
        assertEquals(parseLatestRelease(body), (state as UpdateState.Available).release)
    }

    @Test
    fun `an equal release version becomes UpToDate`() {
        val checker = checker(fetch = { Http.Response(200, releaseJson("v0.6.0")) })

        runBlocking { checker.check() }

        assertEquals(UpdateState.UpToDate, checker.state.value)
    }

    @Test
    fun `an older release version becomes UpToDate`() {
        val checker = checker(fetch = { Http.Response(200, releaseJson("v0.5.0")) })

        runBlocking { checker.check() }

        assertEquals(UpdateState.UpToDate, checker.state.value)
    }

    // ── 2xx responses that yield nothing installable ─────────────────────────

    @Test
    fun `a malformed body becomes UpToDate and still records the check`() {
        val checker = checker(fetch = { Http.Response(200, "{ not json") })

        runBlocking { checker.check() }

        assertEquals(UpdateState.UpToDate, checker.state.value)
        assertEquals(NOW, UpdatePreferences.lastCheckAt())
    }

    @Test
    fun `a release with no usable APK becomes UpToDate`() {
        val body = releaseJson("v0.7.0", apkName = "wren-0.7.0-unsigned.apk")
        val checker = checker(fetch = { Http.Response(200, body) })

        runBlocking { checker.check() }

        assertEquals(UpdateState.UpToDate, checker.state.value)
    }

    @Test
    fun `a draft release becomes UpToDate`() {
        val checker = checker(fetch = { Http.Response(200, releaseJson("v0.7.0", draft = true)) })

        runBlocking { checker.check() }

        assertEquals(UpdateState.UpToDate, checker.state.value)
    }

    // ── Failures ─────────────────────────────────────────────────────────────

    @Test
    fun `a non-2xx response becomes Failed naming the status code`() {
        val checker = checker(fetch = { Http.Response(503, "") })

        runBlocking { checker.check() }

        val state = checker.state.value
        assertInstanceOf(UpdateState.Failed::class.java, state)
        assertTrue((state as UpdateState.Failed).message.contains("503"), state.message)
    }

    @Test
    fun `a non-2xx response does not record the check so the next check retries`() {
        val calls = AtomicInteger(0)
        val checker = checker(fetch = { calls.incrementAndGet(); Http.Response(503, "") })

        runBlocking { checker.check() }
        assertEquals(0L, UpdatePreferences.lastCheckAt())

        runBlocking { checker.check() }
        assertEquals(2, calls.get())
    }

    @Test
    fun `a fetch that throws becomes Failed without propagating and does not record`() {
        val checker = checker(fetch = { throw RuntimeException("no network") })

        runBlocking { checker.check() }

        val state = checker.state.value
        assertInstanceOf(UpdateState.Failed::class.java, state)
        assertTrue((state as UpdateState.Failed).message.contains("no network"), state.message)
        assertEquals(0L, UpdatePreferences.lastCheckAt())
    }

    // ── Recording the check time ─────────────────────────────────────────────

    @Test
    fun `a successful check records the injected clock time`() {
        val checker = checker(fetch = { Http.Response(200, newerRelease) })

        runBlocking { checker.check() }

        assertEquals(NOW, UpdatePreferences.lastCheckAt())
    }

    // ── Unusable installed version ───────────────────────────────────────────

    @Test
    fun `a missing installed version becomes UpToDate`() {
        val checker = checker(currentVersion = null, fetch = { Http.Response(200, newerRelease) })

        runBlocking { checker.check() }

        assertEquals(UpdateState.UpToDate, checker.state.value)
    }

    @Test
    fun `an unparseable installed version becomes UpToDate`() {
        val checker = checker(currentVersion = "not-a-version", fetch = { Http.Response(200, newerRelease) })

        runBlocking { checker.check() }

        assertEquals(UpdateState.UpToDate, checker.state.value)
    }

    // ── Dismissal ────────────────────────────────────────────────────────────

    @Test
    fun `a dismissed release tag does not become Available on a non-forced check`() {
        UpdatePreferences.dismiss("v0.7.0")
        val checker = checker(fetch = { Http.Response(200, newerRelease) })

        runBlocking { checker.check() }

        assertEquals(UpdateState.UpToDate, checker.state.value)
    }

    @Test
    fun `a forced check bypasses the dismissal`() {
        UpdatePreferences.dismiss("v0.7.0")
        val checker = checker(fetch = { Http.Response(200, newerRelease) })

        runBlocking { checker.check(force = true) }

        assertInstanceOf(UpdateState.Available::class.java, checker.state.value)
    }

    // ── In-flight state and concurrency ──────────────────────────────────────

    @Test
    fun `the state is Checking while the fetch is in flight`() {
        var observed: UpdateState? = null
        var checker: UpdateChecker? = null
        checker = checker(fetch = {
            observed = checker!!.state.value
            Http.Response(200, newerRelease)
        })

        runBlocking { checker.check() }

        assertEquals(UpdateState.Checking, observed)
    }

    @Test
    fun `overlapping checks fetch exactly once`() {
        val calls = AtomicInteger(0)
        val enteredFetch = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val checker = checker(fetch = {
            calls.incrementAndGet()
            enteredFetch.countDown()
            releaseFetch.await(5, TimeUnit.SECONDS)
            Http.Response(200, newerRelease)
        })

        runBlocking {
            val first = launch(Dispatchers.Default) { checker.check() }
            assertTrue(enteredFetch.await(5, TimeUnit.SECONDS))
            val second = launch(Dispatchers.Default) { checker.check() }
            releaseFetch.countDown()
            first.join()
            second.join()
        }

        assertEquals(1, calls.get())
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun checker(
        currentVersion: String? = "0.6.0",
        fetch: (String) -> Http.Response,
    ): UpdateChecker = UpdateChecker(
        currentVersion = currentVersion,
        now = { NOW },
        fetch = fetch,
        checkIntervalMs = INTERVAL,
    )

    private val newerRelease: String get() = releaseJson("v0.7.0")

    /** A `releases/latest` body carrying one APK asset named for [tag]. */
    private fun releaseJson(
        tag: String,
        apkName: String = "wren-${tag.removePrefix("v")}.apk",
        draft: Boolean = false,
    ): String = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": false,
          "body": "Release notes",
          "assets": [
            {
              "name": "$apkName",
              "size": 12345678,
              "browser_download_url": "https://github.com/Nxssie/wren/releases/download/$tag/$apkName"
            }
          ]
        }
    """.trimIndent()

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val INTERVAL = 24L * 60 * 60 * 1000
    }
}
