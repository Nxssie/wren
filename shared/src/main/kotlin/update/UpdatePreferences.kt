package update

import util.AppDirs
import util.Log
import java.io.File

/**
 * What the updater remembers between launches: when it last completed a check, and which
 * version the user dismissed.
 *
 * Only persistence lives here. How long a check stays fresh, and whether a forced check
 * bypasses that, is the checker's policy — this object stores two scalars and reads them back.
 *
 * Both files hold one scalar each, matching the existing `DownloadPreference` pattern. A
 * missing, empty or unreadable file degrades to the fallback instead of throwing, so a
 * read-only or full disk leaves the updater dormant rather than crashing the app on launch.
 * A dismissal is a single value, not a list: a dismissed version is replaced, never appended.
 */
object UpdatePreferences {
    private val lastCheckFile get() = File(AppDirs.config, "update-last-check")
    private val dismissedFile get() = File(AppDirs.config, "update-dismissed-version")

    /** Epoch milliseconds of the last completed check, or 0 when none has been recorded. */
    fun lastCheckAt(): Long = runCatching {
        if (!lastCheckFile.exists()) return 0L
        lastCheckFile.readText().trim().toLongOrNull() ?: 0L
    }.onFailure { Log.w("UpdatePreferences", "could not read the last update check: ${it.message}") }
        .getOrDefault(0L)

    fun recordCheck(atMillis: Long) {
        runCatching {
            AppDirs.config.mkdirs()
            lastCheckFile.writeText(atMillis.toString())
        }.onFailure { Log.w("UpdatePreferences", "could not record the last update check: ${it.message}") }
    }

    /** The version string the user dismissed, or null when they have dismissed nothing. */
    fun dismissedVersion(): String? = runCatching {
        if (!dismissedFile.exists()) return null
        dismissedFile.readText().trim().takeIf { it.isNotEmpty() }
    }.onFailure { Log.w("UpdatePreferences", "could not read the dismissed update version: ${it.message}") }
        .getOrNull()

    fun dismiss(version: String) {
        runCatching {
            AppDirs.config.mkdirs()
            dismissedFile.writeText(version)
        }.onFailure { Log.w("UpdatePreferences", "could not save the dismissed update version: ${it.message}") }
    }
}
