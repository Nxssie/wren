import api.warmupStreamConnection
import auth.AuthStore
import auth.OAuthConfig
import androidx.compose.ui.window.application
import provider.LibraryWarmup
import ui.AppWindow
import util.AppDirs
import util.Log
import util.ThemePreference
import java.io.File

fun main() {
    AppDirs.init(
        configDir = File(System.getProperty("user.home"), ".config/wren"),
        stateDir = File(System.getProperty("user.home"), ".local/state/wren"),
    )
    api.loadProtectedStreams()
    // Before the first window exists, so the choice applies to the first frame (no flash).
    ThemePreference.load()?.let { ui.globalDark = it }
    // Same reasoning as the app: the library is a long walk, so warm it while the window opens.
    LibraryWarmup.start()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Log.e("Uncaught", "Uncaught exception on thread '${thread.name}'", throwable)
    }
    OAuthConfig.clientId = BuildConfig.GOOGLE_CLIENT_ID
    OAuthConfig.clientSecret = BuildConfig.GOOGLE_CLIENT_SECRET
    Log.i("Main", "Wren starting (pid=${ProcessHandle.current().pid()})")
    AuthStore.migrateLegacy()
    applyUiScale().let { scale ->
        warmupStreamConnection()
        application {
            AppWindow(uiScale = scale, onCloseRequest = ::exitApplication)
        }
    }
}

/**
 * AWT/Skia render through XWayland, and compositors like Hyprland rescale that buffer by
 * the monitor scale — fractional scaling makes the UI pixelated. AWT on X11 doesn't pick
 * up the compositor scale (and may ignore `sun.java2d.uiScale` set programmatically), so
 * detect the compositor scale ourselves and hand it to Compose (see AppWindow), which
 * sizes the window and lays out the UI at native resolution. Must run before the first
 * AWT window exists (i.e. before the Compose `application` block).
 */
private fun applyUiScale(): Float {
    val scale = detectUiScale() ?: 1f
    if (scale > 1f) {
        System.setProperty("sun.java2d.uiScale.enabled", "true")
        System.setProperty("sun.java2d.uiScale", scale.toString())
    }
    val awtDensity = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
    }.getOrNull()?.takeIf { it > 0f } ?: 1f
    Log.i("Main", "Compositor scale $scale, AWT density $awtDensity")
    return scale
}

private fun detectUiScale(): Float? {
    // 1. Hyprland (HYPRLAND_INSTANCE_SIGNATURE is set for session processes)
    val hyprland = runCatching {
        if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE").isNullOrBlank()) return@runCatching null
        val out = ProcessBuilder("hyprctl", "monitors")
            .start().inputStream.bufferedReader().use { it.readText() }
        Regex("""^\s*scale:\s*([\d.]+)""", RegexOption.MULTILINE)
            .find(out)?.groupValues?.get(1)?.toFloatOrNull()
    }.getOrNull()
    if (hyprland != null && hyprland > 1f) return hyprland

    // 2. GDK_SCALE env (GTK convention, integer)
    val gdk = System.getenv("GDK_SCALE")?.toFloatOrNull()
    if (gdk != null && gdk > 1f) return gdk

    // 3. Xft.dpi from X resources (common in X11 dotfiles; 96 dpi == scale 1)
    val xft = runCatching {
        val out = ProcessBuilder("xrdb", "-query")
            .start().inputStream.bufferedReader().use { it.readText() }
        out.lineSequence()
            .firstOrNull { it.startsWith("Xft.dpi") }
            ?.substringAfter(':')?.trim()?.toFloatOrNull()?.div(96f)
    }.getOrNull()
    return xft?.takeIf { it > 1f }
}
