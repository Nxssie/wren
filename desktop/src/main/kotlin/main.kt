import api.warmupStreamConnection
import androidx.compose.ui.window.application
import ui.AppWindow
import util.Log

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Log.e("Uncaught", "Uncaught exception on thread '${thread.name}'", throwable)
    }
    Log.i("Main", "Wren starting (pid=${ProcessHandle.current().pid()})")
    warmupStreamConnection()
    application {
        AppWindow(onCloseRequest = ::exitApplication)
    }
}
