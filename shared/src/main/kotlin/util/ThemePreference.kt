package util

import java.io.File

/**
 * Remembers the chosen theme, which is a user preference and not screen state: without it the
 * window (desktop) or the activity (Android) came back on the default after every restart or
 * recreate. Both apps share [AppDirs], so they share the choice.
 *
 * Null means "never chosen", which leaves each app's own default (dark on Android, light on
 * desktop) in place instead of forcing the other one's.
 */
object ThemePreference {
    private val file get() = File(AppDirs.config, "theme")

    fun load(): Boolean? = runCatching {
        if (!file.exists()) return null
        file.readText().trim().toBooleanStrictOrNull()
    }.onFailure { Log.w("ThemePreference", "could not read theme: ${it.message}") }.getOrNull()

    fun save(dark: Boolean) {
        runCatching {
            AppDirs.config.mkdirs()
            file.writeText(dark.toString())
        }.onFailure { Log.w("ThemePreference", "could not save theme: ${it.message}") }
    }
}
