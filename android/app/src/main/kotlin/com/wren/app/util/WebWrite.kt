package com.wren.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import api.SoundCloud
import auth.SoundCloudWebSignIn
import kotlinx.coroutines.suspendCancellableCoroutine
import util.Log
import kotlin.coroutines.resume

/**
 * Performs a SoundCloud write from a real page instead of from OkHttp.
 *
 * SoundCloud guards the like endpoints with DataDome, which turns down a request that does not
 * look like the web player's — same cookies, same engine, same origin. So when the plain call
 * comes back 403, the same request is repeated here: a hidden WebView opens soundcloud.com and
 * its own JavaScript issues the fetch, with the session's cookies attached.
 */
object WebWrite {
    private const val TAG = "WebWrite"
    private const val ORIGIN = "https://soundcloud.com/"
    private const val TIMEOUT_MS = 20_000L
    private const val RETRY_MS = 2_500

    /** Runs [method] against [url] in the page; null when the page never answered. */
    suspend fun perform(context: Context, method: String, url: String, token: String): SoundCloud.WebWriteOutcome? =
        suspendCancellableCoroutine { cont ->
            val manager = CookieManager.getInstance().apply { setAcceptCookie(true) }
            val main = Handler(Looper.getMainLooper())
            var web: WebView? = null
            var settled = false
            var sent = false

            fun verdict(): String? = manager.getCookie(ORIGIN)
                ?.let { SoundCloudWebSignIn.tokenFromCookies(it, SoundCloudWebSignIn.DATA_DOME_KEY) }

            fun finish(outcome: SoundCloud.WebWriteOutcome?) {
                if (settled) return
                settled = true
                main.removeCallbacksAndMessages(null)
                web?.destroy()
                runCatching { if (cont.isActive) cont.resume(outcome) }
            }

            // The page reports back by calling into this bridge rather than by returning a value:
            // `evaluateJavascript` answers with the promise object, not with what it resolved to.
            val bridge = object {
                @JavascriptInterface
                fun onResult(status: Int) {
                    Log.i(TAG, "browser answered $method with $status")
                    main.post { finish(SoundCloud.WebWriteOutcome(status, verdict())) }
                }
            }

            Log.i(TAG, "sending $method from the browser")
            main.post {
                @SuppressLint("SetJavaScriptEnabled")
                val created = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(bridge, "WrenWrite")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            // Fires once per navigation, and the player redirects on load; one write.
                            if (sent) return
                            sent = true
                            Log.i(TAG, "page ready, issuing the fetch")
                            // The verdict cookie is issued by the page's own scripts, so the fetch
                            // goes out on "finished" rather than as soon as loading starts.
                            view.evaluateJavascript(script(method, url, token), null)
                        }
                    }
                    loadUrl(ORIGIN)
                }
                web = created
                main.postDelayed({ finish(null) }, TIMEOUT_MS)
            }

            cont.invokeOnCancellation { main.post { finish(null) } }
        }

    /**
     * The write as the web player issues it. `credentials` is what carries the verdict cookie and
     * the Authorization header is the same OAuth token the rest of the app uses. A refusal is
     * retried once: the page's own scripts, and the cookie they earn, settle a moment after load.
     */
    private fun script(method: String, url: String, token: String): String {
        val authorization = js("OAuth $token")
        return """
            (function () {
                console.log('WrenWrite: sending ' + ${js(method)});
                function send(left) {
                    fetch(${js(url)}, {
                        method: ${js(method)},
                        headers: { Authorization: $authorization },
                        credentials: 'include'
                    }).then(function (response) {
                        console.log('WrenWrite: answered ' + response.status);
                        if (response.status === 403 && left > 0) {
                            setTimeout(function () { send(left - 1); }, $RETRY_MS);
                        } else {
                            WrenWrite.onResult(response.status);
                        }
                    }).catch(function (error) {
                        console.log('WrenWrite: failed ' + error);
                        WrenWrite.onResult(-1);
                    });
                }
                send(1);
            })();
        """.trimIndent()
    }

    private fun js(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
