package com.wren.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import auth.SoundCloudAuth
import auth.SoundCloudWebSignIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import util.Log
import util.connectMessage
import kotlin.coroutines.resume

private const val TAG = "SoundCloudLogin"

/** How often to look for the token the page stores once the user is signed in. */
private const val TOKEN_POLL_MS = 1_500L

/**
 * SoundCloud's ordinary sign-in page in a WebView we control, rather than the authorization page
 * the PKCE flow drives: the sign-in needs no redirect interception, so the app simply reads the
 * token the page keeps for itself once the user is in.
 *
 * Mirrors desktop's SoundCloudLoginWindow, including popup hosting for "Continue with
 * Google/Apple", which SoundCloud opens with `window.open()`.
 */
@Composable
fun SoundCloudLoginScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun deliver(session: SoundCloudWebSignIn.Session) {
        if (busy) return
        busy = true
        scope.launch {
            // Validation is the same call the pasted-token path uses, so a session that does not
            // work is rejected here rather than stored and discovered later.
            runCatching { SoundCloudAuth.connect(session.accessToken, session.refreshToken, session.clientId) }
                .onSuccess { onDone() }
                .onFailure {
                    Log.w(TAG, "the session from the page was refused", it)
                    error = it.connectMessage()
                    busy = false
                }
        }
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TextPrimary)
            }
            Text(
                "sign_in_to_soundcloud;",
                color = TextSecondary,
                fontFamily = FontMono,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = PsIrisCyan,
                    strokeWidth = 2.dp,
                )
            }
        }

        error?.let {
            Text(
                it,
                color = PsSignalDanger,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Text(
            "// sign_in_as_usual — the_app_reads_the_session; google_or_apple_popups_may_be_refused;",
            color = PsSteel400,
            fontFamily = FontMono,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (busy) {
            // The token was found and is being validated; the page is dead weight from here.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("finishing sign-in;", color = TextSecondary, fontFamily = FontMono, fontSize = 12.sp)
                }
            }
        } else {
            SoundCloudWebView(context = context, onToken = ::deliver, onCancel = onDone)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SoundCloudWebView(
    context: Context,
    onToken: (SoundCloudWebSignIn.Session) -> Unit,
    onCancel: () -> Unit,
) {
    val deliver by rememberUpdatedState(onToken)
    val cancel by rememberUpdatedState(onCancel)
    val popups = remember { mutableStateListOf<WebView>() }
    val holder = remember {
        CookieManager.getInstance().apply {
            // DataDome pins its verdict to a cookie, so a challenge from a previous attempt
            // would follow every retry. Nothing else in the app needs WebView cookies.
            removeAllCookies(null)
            flush()
            setAcceptCookie(true)
        }
        WebLoginWebViews(context, popups)
    }

    BackHandler(enabled = true) {
        if (holder.main.canGoBack()) holder.main.goBack() else cancel()
    }

    DisposableEffect(holder) { onDispose { holder.destroy() } }

    // Polled rather than driven by a callback: there is no redirect to hook any more, and the
    // page stores the token asynchronously as whatever it does after the user is signed in.
    LaunchedEffect(holder) {
        while (isActive) {
            delay(TOKEN_POLL_MS)
            // Both cookies come from the same exchange, and the jar belongs to the origin the flow
            // actually used, which is m.soundcloud.com rather than soundcloud.com.
            val jars = listOf("https://soundcloud.com", "https://m.soundcloud.com")
                .map { CookieManager.getInstance().getCookie(it) }
            fun cookie(name: String) = jars.firstNotNullOfOrNull { SoundCloudWebSignIn.tokenFromCookies(it, name) }

            val access = holder.main.storedToken() ?: cookie(SoundCloudWebSignIn.TOKEN_KEY)
            if (access != null) {
                val refresh = cookie(SoundCloudWebSignIn.REFRESH_TOKEN_KEY)
                Log.i(
                    TAG,
                    "session found (access ${access.length} chars, refresh " +
                        "${if (refresh == null) "absent" else "${refresh.length} chars"}, " +
                        "client ${holder.authClientId ?: "unknown"})"
                )
                deliver(SoundCloudWebSignIn.Session(access, refresh, holder.authClientId))
                return@LaunchedEffect
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { holder.main }, modifier = Modifier.fillMaxSize())
        popups.forEach { popup ->
            key(popup) {
                AndroidView(factory = { popup }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** `evaluateJavascript` answers on the main thread, so wait for it rather than blocking one. */
private suspend fun WebView.storedToken(): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(SoundCloudWebSignIn.TOKEN_SCRIPT) { raw ->
        if (cont.isActive) cont.resume(SoundCloudWebSignIn.tokenFromJsResult(raw))
    }
}

/**
 * The sign-in page plus any provider popups. There is no callback to catch now, so this only has
 * to keep the popups alive and report what the page turned out to be — which is what tells a
 * challenge apart from a sign-in that simply has not been completed yet.
 */
private class WebLoginWebViews(
    private val context: Context,
    private val popups: MutableList<WebView>,
) {
    val main: WebView = create()

    /** The client that ran the sign-in, captured off the authorize URL on the way past. */
    var authClientId: String? = null
        private set

    fun destroy() {
        popups.forEach { runCatching { it.destroy() } }
        popups.clear()
        runCatching { main.destroy() }
    }

    private fun create(): WebView = WebView(context).apply {
        // Without explicit match-parent params the WebView measures like wrap_content and
        // resolves `100vh` to 0px, which collapses DataDome's captcha overlay to nothing:
        // the challenge runs invisibly and SoundCloud only shows "Something unexpected".
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        // Keep the true mobile Chrome UA and drop only the "wv" marker that flags an embedded
        // WebView: the device check compares the UA with the real engine and device, and Google's
        // popup refuses anything advertising itself as embedded.
        settings.userAgentString = browserUserAgent(settings.userAgentString)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                SoundCloudWebSignIn.clientIdFromAuthUrl(url)?.let { authClientId = it }
                // The query is left off on purpose: the callback carries an authorization code.
                Log.i(TAG, "loaded ${url?.substringBefore('?')} — title=\"${view?.title}\"")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val popup = create()
                popups.add(popup)
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                // SoundCloud closes its own popups with window.close(); Android WebView
                // stays on screen forever unless we act here, and the WebView handed to
                // us is usually not the instance we created — drop the newest one.
                if (popups.isNotEmpty()) {
                    val popup = popups.removeAt(popups.lastIndex)
                    runCatching { popup.destroy() }
                }
            }
        }

        loadUrl(SoundCloudWebSignIn.SIGN_IN_URL)
    }

    private companion object {
        fun browserUserAgent(defaultUa: String): String =
            defaultUa.replace("; wv", "").replace(Regex("""\s*Version/\d+(\.\d+)*"""), "")
    }
}
