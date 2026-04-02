import androidx.compose.ui.window.application
import ui.AppWindow

fun main() = application {
    AppWindow(onCloseRequest = ::exitApplication)
}
