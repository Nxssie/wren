package com.wren.app.util

import util.AppDirs
import util.Log
import java.io.File

/**
 * The theme is a user choice, not screen state, so it has to outlive the process: without
 * this it flipped back to the default on every cold start (and on any activity recreate).
 * Null means the user has never chosen one, which leaves the app's own default in place.
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
