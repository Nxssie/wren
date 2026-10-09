package update

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import util.AppDirs
import java.io.File

class UpdatePreferencesTest {

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

    // ── Last check timestamp ─────────────────────────────────────────────────

    @Test
    fun `a missing check record reads as zero`() {
        assertEquals(0L, UpdatePreferences.lastCheckAt())
    }

    @Test
    fun `an empty check record reads as zero`() {
        UpdatePreferences.recordCheck(1_700_000_000_000L)
        // Truncate whatever the store wrote, without depending on the file's name.
        AppDirs.config.listFiles()!!.forEach { it.writeText("") }

        assertEquals(0L, UpdatePreferences.lastCheckAt())
    }

    @Test
    fun `an unparseable check record reads as zero`() {
        UpdatePreferences.recordCheck(1_700_000_000_000L)
        AppDirs.config.listFiles()!!.forEach { it.writeText("not-a-number") }

        assertEquals(0L, UpdatePreferences.lastCheckAt())
    }

    @Test
    fun `a check record too large for a long reads as zero`() {
        UpdatePreferences.recordCheck(1_700_000_000_000L)
        AppDirs.config.listFiles()!!.forEach { it.writeText("99999999999999999999") }

        assertEquals(0L, UpdatePreferences.lastCheckAt())
    }

    @Test
    fun `records and reads back an exact millisecond value`() {
        val at = 1_700_000_123_456L
        UpdatePreferences.recordCheck(at)
        assertEquals(at, UpdatePreferences.lastCheckAt())
    }

    @Test
    fun `a zero timestamp round-trips`() {
        UpdatePreferences.recordCheck(0L)
        assertEquals(0L, UpdatePreferences.lastCheckAt())
    }

    @Test
    fun `a large timestamp round-trips`() {
        val at = Long.MAX_VALUE
        UpdatePreferences.recordCheck(at)
        assertEquals(at, UpdatePreferences.lastCheckAt())
    }

    @Test
    fun `recording a check overwrites the previous timestamp`() {
        UpdatePreferences.recordCheck(1L)
        UpdatePreferences.recordCheck(2L)
        assertEquals(2L, UpdatePreferences.lastCheckAt())
    }

    // ── Dismissed version ────────────────────────────────────────────────────

    @Test
    fun `a missing dismissal reads as null`() {
        assertNull(UpdatePreferences.dismissedVersion())
    }

    @Test
    fun `an empty dismissal reads as null`() {
        UpdatePreferences.dismiss("0.6.0")
        AppDirs.config.listFiles()!!.forEach { it.writeText("") }

        assertNull(UpdatePreferences.dismissedVersion())
    }

    @Test
    fun `a blank dismissal reads as null`() {
        UpdatePreferences.dismiss("0.6.0")
        AppDirs.config.listFiles()!!.forEach { it.writeText("  \n\t ") }

        assertNull(UpdatePreferences.dismissedVersion())
    }

    @Test
    fun `dismissing a plain release version round-trips it exactly`() {
        UpdatePreferences.dismiss("0.6.0")
        assertEquals("0.6.0", UpdatePreferences.dismissedVersion())
    }

    @Test
    fun `dismissing a describe-suffixed version round-trips it exactly`() {
        UpdatePreferences.dismiss("0.6.0-12-gabc1234")
        assertEquals("0.6.0-12-gabc1234", UpdatePreferences.dismissedVersion())
    }

    @Test
    fun `dismissing again replaces the stored version in place`() {
        repeat(50) { UpdatePreferences.dismiss("0.6.$it") }

        assertEquals("0.6.49", UpdatePreferences.dismissedVersion())

        // One scalar, not a growing list: every dismissal overwrote the previous value.
        val files = AppDirs.config.listFiles()!!.filter { it.isFile }
        assertEquals(1, files.size)
        assertEquals("0.6.49", files.single().readText().trim())
    }

    // ── Storage shape ────────────────────────────────────────────────────────

    @Test
    fun `stores the check time and the dismissal as two separate scalars`() {
        UpdatePreferences.recordCheck(1_700_000_000_000L)
        UpdatePreferences.dismiss("0.6.0")

        val files = AppDirs.config.listFiles()!!.filter { it.isFile }
        assertEquals(2, files.size)
        assertTrue(files.any { it.readText().trim() == "1700000000000" })
        assertTrue(files.any { it.readText().trim() == "0.6.0" })
    }

    @Test
    fun `reads fall back when the config directory does not exist`() {
        AppDirs.init(configDir = File(tempDir, "absent"), stateDir = File(tempDir, "state"))

        assertEquals(0L, UpdatePreferences.lastCheckAt())
        assertNull(UpdatePreferences.dismissedVersion())
    }
}
