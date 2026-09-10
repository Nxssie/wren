package ui

import auth.SoundCloudOAuth
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javafx.stage.Modality
import javafx.stage.Stage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import util.Log
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded browser for the SoundCloud authorization page only.
 *
 * Loads the PKCE authorization URL from [SoundCloudOAuth.buildAuthRequest] and watches
 * the WebView's location. When SoundCloud redirects to its registered callback
 * (`soundcloud.com/signin/callback?code=…`) we grab the code and close the window
 * before the web player ever loads; the token exchange happens natively afterwards.
 *
 * Lifecycle notes:
 *  - The JavaFX toolkit can only be started once per JVM, so [ensureToolkit]
 *    guards `Platform.startup` and falls back to `runLater` afterwards.
 *  - The cookie jar is cleared on every open so a previous session can't be
 *    picked up silently (and the user can switch accounts).
 */
object SoundCloudLoginWindow {

    private const val TAG = "SoundCloudLogin"
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

    /**
     * Opens the authorization page. Completes with the authorization code on success,
     * or exceptionally on cancel / error / toolkit failure. Cancelling the returned
     * Deferred closes the window.
     */
    fun open(request: SoundCloudOAuth.AuthRequest): Deferred<String> {
        val deferred = CompletableDeferred<String>()

        // Fresh cookie jar per attempt: no stale session, no silent re-login.
        cookieManager.cookieStore.removeAll()
        CookieHandler.setDefault(cookieManager)

        runCatching { ensureToolkit { showStage(request, deferred) } }
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
    private fun showStage(request: SoundCloudOAuth.AuthRequest, deferred: CompletableDeferred<String>) {
        // Only one login window at a time.
        stage?.close()

        val webView = WebView()
        val engine = webView.engine
        configureEngine(engine, "main", request, deferred)

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
        // its own window whose engine is watched for the callback exactly like the main one.
        engine.setCreatePopupHandler { features ->
            Log.d(TAG, "popup requested (menu=${features.hasMenu()}, toolbar=${features.hasToolbar()})")
            val popupView = WebView()
            configureEngine(popupView.engine, "popup", request, deferred)
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
        engine.load(request.url)

        // Tear down *this* attempt only — a newer window may already own `stage`.
        deferred.invokeOnCompletion {
            Platform.runLater { if (s.isShowing) s.close() }
        }
    }

    /** Shared setup for the main view and any popup: UA, callback interception, diagnostics. */
    private fun configureEngine(
        engine: WebEngine,
        name: String,
        request: SoundCloudOAuth.AuthRequest,
        deferred: CompletableDeferred<String>
    ) {
        engine.isJavaScriptEnabled = true
        engine.userAgent = USER_AGENT

        // Intercept the redirect to the callback: complete *before* closing the stage,
        // because closing fires onHidden, which would otherwise report "cancelled".
        engine.locationProperty().addListener { _, _, location ->
            Log.d(TAG, "[$name] navigate: $location")
            if (location != null && location.startsWith(SoundCloudOAuth.REDIRECT_URI)) {
                runCatching { SoundCloudOAuth.parseCallback(location, request) }
                    .onSuccess { deferred.complete(it) }
                    .onFailure { deferred.completeExceptionally(it) }
            }
        }
        engine.loadWorker.stateProperty().addListener { _, _, state ->
            when (state) {
                Worker.State.SUCCEEDED -> Log.d(TAG, "[$name] loaded: ${engine.location} title=\"${engine.title}\"")
                Worker.State.FAILED -> Log.w(TAG, "[$name] load failed for ${engine.location}", engine.loadWorker.exception)
                else -> {}
            }
        }
        engine.onError = javafx.event.EventHandler { ev -> Log.w(TAG, "[$name] webkit error: ${ev.message}") }
        engine.onAlert = javafx.event.EventHandler { ev -> Log.d(TAG, "[$name] page alert: ${ev.data}") }
        engine.onStatusChanged = javafx.event.EventHandler { ev -> if (!ev.data.isNullOrBlank()) Log.d(TAG, "[$name] status: ${ev.data}") }
    }
}
