package util

import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal file+console logger. Writing to a file matters here because a
 * desktop-launched AppImage has no attached terminal — stdout/stderr are
 * otherwise lost, which is exactly why past crashes left no trace.
 */
object Log {
    private val stateDir = File(System.getProperty("user.home"), ".local/state/wren")
    private val logFile = File(stateDir, "wren.log")
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val writer: PrintWriter? = runCatching {
        stateDir.mkdirs()
        if (logFile.exists() && logFile.length() > 0) {
            logFile.copyTo(File(stateDir, "wren.log.1"), overwrite = true)
        }
        PrintWriter(logFile.outputStream(), true)
    }.getOrNull()

    @Synchronized
    private fun write(level: String, tag: String, msg: String, throwable: Throwable?) {
        val line = "${LocalDateTime.now().format(timestampFormat)} $level [$tag] $msg"
        println(line)
        writer?.println(line)
        throwable?.let {
            it.printStackTrace()
            it.printStackTrace(writer)
        }
        writer?.flush()
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, throwable: Throwable? = null) = write("W", tag, msg, throwable)
    fun e(tag: String, msg: String, throwable: Throwable? = null) = write("E", tag, msg, throwable)
}
