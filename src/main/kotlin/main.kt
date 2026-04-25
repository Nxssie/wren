import api.warmupStreamConnection
import androidx.compose.ui.window.application
import ui.AppWindow

fun main() {
    warmupStreamConnection()
    application {
        AppWindow(onCloseRequest = ::exitApplication)
    }
}
