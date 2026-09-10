package util

import download.Destination
import download.FileDestination
import java.io.File

/**
 * Where downloads land: `~/Music/Wren` for a normal desktop session, falling back to the
 * app state dir when `~/Music` is not writable (flatpak-style sandboxes, read-only homes).
 */
fun downloadsDir(): File {
    val music = File(System.getProperty("user.home"), "Music/Wren")
    if (music.isDirectory || music.mkdirs()) return music
    return File(AppDirs.state, "downloads").apply { mkdirs() }
}

fun downloadDestination(): Destination = FileDestination(downloadsDir())
