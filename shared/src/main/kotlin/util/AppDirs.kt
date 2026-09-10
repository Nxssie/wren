package util

import java.io.File

/**
 * Per-platform writable directories, resolved lazily so file-backed stores keep working
 * without a hard init order. Each entry point calls [init] once at startup: desktop with
 * the XDG layout under `$HOME`, Android with app-private storage.
 */
object AppDirs {
    private var configRoot: File? = null
    private var stateRoot: File? = null

    fun init(configDir: File, stateDir: File) {
        configRoot = configDir
        stateRoot = stateDir
    }

    val config: File get() = configRoot ?: File(System.getProperty("user.home"), ".config/wren")
    val state: File get() = stateRoot ?: File(System.getProperty("user.home"), ".local/state/wren")
}
