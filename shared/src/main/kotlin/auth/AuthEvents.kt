package auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bumped whenever any provider connects or disconnects, so Compose UI can
 * observe auth changes without the auth objects depending on Compose.
 */
object AuthEvents {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun notifyChanged() {
        _version.value++
    }
}
