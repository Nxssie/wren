package util

import java.io.File

/**
 * Whether SoundCloud tracks may be saved to disk. Off unless asked for.
 *
 * Saving is the behaviour SoundCloud actually polices, and the cost of it lands on the public
 * client id this app scrapes and depends on — a client that fetches whole files is what gets one
 * rotated or blocked. Keeping your own upload is a different thing from archiving everything you
 * play, so it stays a deliberate choice rather than a default.
 */
object DownloadPreference {
    private val file get() = File(AppDirs.config, "allow-downloads")

    fun load(): Boolean = runCatching {
        if (!file.exists()) return false
        file.readText().trim().toBooleanStrictOrNull() ?: false
    }.onFailure { Log.w("DownloadPreference", "could not read the download preference: ${it.message}") }
        .getOrDefault(false)

    fun save(allow: Boolean) {
        runCatching {
            AppDirs.config.mkdirs()
            file.writeText(allow.toString())
        }.onFailure { Log.w("DownloadPreference", "could not save the download preference: ${it.message}") }
    }
}
