package provider

import auth.AuthEvents
import auth.GoogleAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import util.Log
import util.runCatchingExceptCancellation

/**
 * Builds the signed-in library in the background, so the first open of the tab is already served
 * from the cache instead of waiting through every page of it.
 *
 * It rides [AuthEvents] rather than a timer: signing in is exactly when the fetch would otherwise
 * happen in front of the user, and the flow replays the current value at start-up, so the same
 * collector covers both. Only the YouTube library is warmed — doing it for every platform would
 * spend bandwidth at start-up for a tab the user may never open, and the SoundCloud one is cached
 * from its first visit anyway.
 */
object LibraryWarmup {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            AuthEvents.version.collect {
                if (!GoogleAuth.isAuthenticated) return@collect
                runCatchingExceptCancellation { YouTubeProvider.librarySongs() }
                    .onSuccess { Log.i("LibraryWarmup", "warmed the library (${it.size} tracks)") }
                    .onFailure { Log.w("LibraryWarmup", "could not warm the library", it) }
            }
        }
    }
}
