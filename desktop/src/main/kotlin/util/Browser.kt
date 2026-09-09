package util

import java.awt.Desktop
import java.net.URI

/**
 * Opens [url] in the user's default browser. `java.awt.Desktop` is unsupported on many
 * Linux desktops (Wayland compositors, no GNOME libs in the jlinked runtime), so fall
 * back to the platform opener. Returns false when every strategy failed, so callers
 * can show the link for manual copy instead of failing silently.
 */
fun openInBrowser(url: String): Boolean {
    val os = System.getProperty("os.name").lowercase()
    val openers: List<() -> Unit> = listOfNotNull(
        {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                error("Desktop.browse unsupported")
            }
        },
        System.getenv("BROWSER")?.takeIf { it.isNotBlank() }?.let { b -> { spawn(b, url) } },
        when {
            os.contains("win") -> { { spawn("rundll32", "url.dll,FileProtocolHandler", url) } }
            os.contains("mac") -> { { spawn("open", url) } }
            else -> { { spawn("xdg-open", url) } }
        }
    )
    for (open in openers) {
        val result = runCatching(open)
        if (result.isSuccess) return true
        Log.w("Browser", "Opener failed: ${result.exceptionOrNull()?.message}")
    }
    Log.e("Browser", "Could not open browser for $url")
    return false
}

private fun spawn(vararg cmd: String) {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    // xdg-open exits quickly; a non-zero code (e.g. 3 = no handler) means it did nothing.
    if (p.waitFor() != 0) error("${cmd.first()} exited with ${p.exitValue()}")
}
