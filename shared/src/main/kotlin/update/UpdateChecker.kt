package update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import util.Http
import util.Log

/**
 * What the UI needs to know about the update check.
 *
 * [Idle] is the initial state and the one a throttled check leaves behind: nothing has been
 * asked yet this launch. [Available] is the only state that offers an install, and it carries
 * the whole [UpdateRelease] so the UI can read the APK name, URL and size without a second parse.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Decides whether to ask GitHub for the newest release, and translates the answer into an
 * [UpdateState] the UI can render.
 *
 * Both seams are injected so the policy is testable offline: [fetch] performs the HTTP call and
 * [now] supplies the clock the throttle reads. The daily interval lives here rather than in
 * [UpdatePreferences], which only stores the raw timestamp.
 *
 * [check] serialises overlapping calls through a [Mutex] so a launch check and a manual check
 * racing each other cannot both hit the rate-limited endpoint.
 */
class UpdateChecker(
    private val currentVersion: String?,
    private val now: () -> Long,
    private val fetch: (String) -> Http.Response = { url ->
        Http.get(url, mapOf("Accept" to "application/vnd.github+json"))
    },
    private val checkIntervalMs: Long = 24L * 60 * 60 * 1000,
    private val releasesUrl: String = "https://api.github.com/repos/Nxssie/wren/releases/latest",
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun check(force: Boolean = false) {
        mutex.withLock {
            if (!force && isThrottled()) return

            _state.value = UpdateState.Checking

            val response = try {
                fetch(releasesUrl)
            } catch (cancellation: CancellationException) {
                // Coroutine cancellation is not a network failure; rethrowing keeps the caller's
                // cancellation working instead of disguising it as a Failed check.
                throw cancellation
            } catch (failure: Throwable) {
                _state.value = UpdateState.Failed(
                    "Update check failed: ${failure.message ?: failure::class.simpleName}",
                )
                return
            }

            if (!response.isSuccessful) {
                // Deliberately not recording the check: a 403 from rate limiting or a transient
                // 5xx must retry on the next launch, not wait out a full day.
                _state.value = UpdateState.Failed("GitHub release check failed with HTTP ${response.code}")
                return
            }

            _state.value = resolve(response.body, force)
            UpdatePreferences.recordCheck(now())
        }
    }

    /** True when a completed check is still inside the interval and [check] was not forced. */
    private fun isThrottled(): Boolean {
        val lastCheck = UpdatePreferences.lastCheckAt()
        // Zero is the store's "never recorded" sentinel, so it always permits the fetch.
        if (lastCheck <= 0L) return false
        return now() - lastCheck < checkIntervalMs
    }

    /**
     * Turns a successful payload into a state without touching the network or the clock.
     *
     * A body that [parseLatestRelease] cannot turn into a release — malformed JSON, no usable APK,
     * a draft — is indistinguishable here, and every one of them means "there is nothing to
     * install", so all collapse to [UpdateState.UpToDate]. The warning is the only trace a real
     * upstream break leaves.
     */
    private fun resolve(body: String, force: Boolean): UpdateState {
        val installed = AppVersion.parse(currentVersion)
        if (installed == null) {
            Log.w(TAG, "installed version is missing or unparseable; offering no update")
            return UpdateState.UpToDate
        }

        val release = parseLatestRelease(body)
        if (release == null) {
            Log.w(TAG, "release payload yielded no installable APK; offering no update")
            return UpdateState.UpToDate
        }

        if (!release.version.isNewerThan(installed)) return UpdateState.UpToDate

        // The dismissal is keyed on the raw tag, the exact string the UI showed the user.
        if (!force && release.tag == UpdatePreferences.dismissedVersion()) return UpdateState.UpToDate

        return UpdateState.Available(release)
    }

    private companion object {
        const val TAG = "UpdateChecker"
    }
}
