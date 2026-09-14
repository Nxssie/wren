package ui

import auth.SoundCloudWebSignIn
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.stage.Modality
import javafx.stage.Stage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import util.Log
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Embedded browser for the SoundCloud sign-in page.
 *
 * The ordinary page, not the authorization endpoint: there is no callback to intercept, so the
 * app waits for the token the page keeps for itself once the user is in and completes with it.
 * The caller validates that token against `/me` before storing it, exactly as the pasted-token
 * path does.
 *
 * Lifecycle notes:
 *  - The JavaFX toolkit can only be started once per JVM, so [ensureToolkit]
 *    guards `Platform.startup` and falls back to `runLater` afterwards.
 *  - The cookie jar is cleared on every open so a previous session can't be
 *    picked up silently (and the user can switch accounts).
 */
object SoundCloudLoginWindow {

    private const val TAG = "SoundCloudLogin"

    /** How often to look for the token once the user is signed in. */
    private const val POLL_MS = 1_500L

    /**
     * Google refuses to sign in inside anything it identifies as an embedded WebView
     * (403 disallowed_useragent), and JavaFX's default UA advertises exactly that.
     * Present as a regular desktop Safari/Chrome build instead.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"

    private val toolkitStarted = AtomicBoolean(false)
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var stage: Stage? = null

    /** The client that ran the sign-in, captured off the authorize URL on the way past. */
    @Volatile private var authClientId: String? = null

    /**
     * Opens the sign-in page. Completes with the SoundCloud token on success, or exceptionally on
     * cancel / error / toolkit failure. Cancelling the returned Deferred closes the window.
     */
    fun open(): Deferred<SoundCloudWebSignIn.Session> {
        val deferred = CompletableDeferred<SoundCloudWebSignIn.Session>()

        // Fresh cookie jar per attempt: no stale session, no silent re-login.
        cookieManager.cookieStore.removeAll()
        CookieHandler.setDefault(cookieManager)
        authClientId = null

        runCatching { ensureToolkit { showStage(deferred) } }
            .onFailure { e ->
                Log.e(TAG, "JavaFX toolkit unavailable", e)
                deferred.completeExceptionally(
                    IllegalStateException("Embedded browser unavailable — paste the token manually", e)
                )
            }
        return deferred
    }

    fun close() {
        if (!toolkitStarted.get()) return
        runCatching { Platform.runLater { stage?.close() } }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun ensureToolkit(onReady: () -> Unit) {
        if (toolkitStarted.compareAndSet(false, true)) {
            try {
                Platform.startup {
                    Platform.setImplicitExit(false)
                    onReady()
                }
            } catch (e: IllegalStateException) {
                // Another component started the toolkit first — that's fine.
                Platform.runLater(onReady)
            }
        } else {
            Platform.runLater(onReady)
        }
    }

    /** Runs on the FX thread. */
    private fun showStage(deferred: CompletableDeferred<SoundCloudWebSignIn.Session>) {
        // Only one login window at a time.
        stage?.close()

        val webView = WebView()
        val engine = webView.engine
        configureEngine(engine, "main")

        val s = Stage().apply {
            title = "Sign in to SoundCloud"
            scene = Scene(webView, 520.0, 720.0)
            setOnHidden {
                if (stage === this) stage = null
                deferred.completeExceptionally(IllegalStateException("Login cancelled"))
            }
        }
        stage = s

        // "Continue with Google/Apple/Facebook" opens a popup via window.open(). Without a
        // handler JavaFX returns null and the main view goes blank, so host the popup in
        // its own window. Storage is per origin, so the main view still sees the token.
        engine.setCreatePopupHandler { features ->
            Log.d(TAG, "popup requested (menu=${features.hasMenu()}, toolbar=${features.hasToolbar()})")
            val popupView = WebView()
            configureEngine(popupView.engine, "popup")
            Stage().apply {
                title = "Sign in"
                initOwner(s)
                initModality(Modality.NONE)
                scene = Scene(popupView, 480.0, 680.0)
                // The provider closes the popup itself when done; mirror that on the FX side.
                popupView.engine.onVisibilityChanged = javafx.event.EventHandler { ev ->
                    if (!ev.data) close()
                }
                show()
            }
            popupView.engine
        }

        s.show()
        s.toFront()
        engine.load(SoundCloudWebSignIn.SIGN_IN_URL)

        val poller = CoroutineScope(Dispatchers.Default).launch { pollForToken(engine, deferred) }

        // Tear down *this* attempt only — a newer window may already own `stage`.
        deferred.invokeOnCompletion {
            poller.cancel()
            Platform.runLater { if (s.isShowing) s.close() }
        }
    }

    /** Waits for the page to keep a session, then hands it over; the caller validates it. */
    private suspend fun pollForToken(
        engine: WebEngine,
        deferred: CompletableDeferred<SoundCloudWebSignIn.Session>,
    ) {
        while (currentCoroutineContext().isActive) {
            delay(POLL_MS)
            // Storage first for the desktop web player, the jar for the session it serves.
            val access = readScript(engine) ?: cookie(SoundCloudWebSignIn.TOKEN_KEY)
            if (access != null) {
                val refresh = cookie(SoundCloudWebSignIn.REFRESH_TOKEN_KEY)
                val dataDome = cookie(SoundCloudWebSignIn.DATA_DOME_KEY)
                Log.i(
                    TAG,
                    "session found (access ${access.length} chars, refresh " +
                        "${if (refresh == null) "absent" else "${refresh.length} chars"}, " +
                        "client ${authClientId ?: "unknown"}, " +
                        "datadome ${if (dataDome == null) "absent" else "present"})"
                )
                deferred.complete(SoundCloudWebSignIn.Session(access, refresh, authClientId, dataDome))
                return
            }
        }
    }

    /** `executeScript` must run on the FX thread, so this hops there and waits. */
    private suspend fun readScript(engine: WebEngine): String? = suspendCancellableCoroutine { cont ->
        Platform.runLater {
            val raw = runCatching { engine.executeScript(SoundCloudWebSignIn.TOKEN_SCRIPT) }.getOrNull()
            if (cont.isActive) cont.resume(SoundCloudWebSignIn.tokenFromJsResult(raw))
        }
    }

    /**
     * The JavaFX jar is a real cookie store, so it can be asked by name rather than parsed. It is
     * where the page keeps both halves of the session.
     */
    private fun cookie(name: String): String? = cookieManager.cookieStore.cookies
        .filter { it.name == name }
        .mapNotNull { it.value?.takeIf(String::isNotBlank) }
        .firstOrNull()

    /** Shared setup for the main view and any popup: UA, diagnostics. */
    private fun configureEngine(engine: WebEngine, name: String) {
        engine.isJavaScriptEnabled = true
        engine.userAgent = USER_AGENT

        engine.locationProperty().addListener { _, _, location ->
            SoundCloudWebSignIn.clientIdFromAuthUrl(location)?.let { authClientId = it }
            Log.d(TAG, "[$name] navigate: ${location?.substringBefore('?')}")
        }
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            when (state) {
                // The title is what tells a challenge apart from a sign-in not yet completed.
                Worker.State.SUCCEEDED -> Log.i(TAG, "[$name] loaded: ${engine.location} title=\"${engine.title}\"")
                Worker.State.FAILED -> Log.w(TAG, "[$name] load failed for ${engine.location}", engine.loadWorker.exception)
                else -> {}
            }
        }
        engine.onError = javafx.event.EventHandler { ev -> Log.w(TAG, "[$name] webkit error: ${ev.message}") }
        engine.onAlert = javafx.event.EventHandler { ev -> Log.d(TAG, "[$name] page alert: ${ev.data}") }
        engine.onStatusChanged = javafx.event.EventHandler { ev -> if (!ev.data.isNullOrBlank()) Log.d(TAG, "[$name] status: ${ev.data}") }
    }
}
