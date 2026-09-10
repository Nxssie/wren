package com.wren.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
import api.scClientId
import auth.SoundCloudAuth
import auth.SoundCloudOAuth
import kotlinx.coroutines.launch

/**
 * SoundCloud's PKCE sign-in page in a WebView we control: the flow redirects to
 * `soundcloud.com/signin/callback?code=…`, which only a WebView can intercept (a Custom
 * Tab would just load it and lose the code). Mirrors desktop's SoundCloudLoginWindow,
 * including popup hosting for "Continue with Google/Apple", which SoundCloud opens with
 * `window.open()`.
 */
@Composable
fun SoundCloudLoginScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<SoundCloudOAuth.AuthRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { SoundCloudOAuth.buildAuthRequest(scClientId()) }
            .onSuccess { request = it }
            .onFailure { error = it.message?.take(140) ?: "Could not reach SoundCloud" }
    }

    fun deliver(code: String, req: SoundCloudOAuth.AuthRequest) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { SoundCloudAuth.connect(SoundCloudOAuth.exchangeCode(code, req)) }
                .onSuccess { onDone() }
                .onFailure {
                    error = it.message?.take(140) ?: "Login failed"
                    busy = false
                    finishing = false
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
            "// google_or_apple_popups_can_be_refused_by_the_provider — email_and_password_works;",
            color = PsSteel400,
            fontFamily = FontMono,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        val activeRequest = request
        if (activeRequest == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error == null) CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
            }
        } else if (finishing) {
            // The callback was caught: the authorization page must not stay visible
            // while the token exchange runs in the background.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PsIrisCyan, strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("finishing sign-in;", color = TextSecondary, fontFamily = FontMono, fontSize = 12.sp)
                }
            }
        } else {
            SoundCloudWebView(
                context = context,
                request = activeRequest,
                onCallbackUrl = { url ->
                    finishing = true
                    busy = true
                    val code = runCatching { SoundCloudOAuth.parseCallback(url, activeRequest) }.getOrNull()
                    if (code == null) {
                        error = "Authorization failed — please try again"
                        busy = false
                        finishing = false
                    } else {
                        deliver(code, activeRequest)
                    }
                },
                onCancel = onDone,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SoundCloudWebView(
    context: Context,
    request: SoundCloudOAuth.AuthRequest,
    onCallbackUrl: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val callback by rememberUpdatedState(onCallbackUrl)
    val cancel by rememberUpdatedState(onCancel)
    val popups = remember { mutableStateListOf<WebView>() }
    val holder = remember(request) {
        CookieManager.getInstance().setAcceptCookie(true)
        WebLoginWebViews(context, request, { url -> callback(url) }, popups)
    }

    BackHandler(enabled = true) {
        if (holder.main.canGoBack()) holder.main.goBack() else cancel()
    }

    DisposableEffect(holder) { onDispose { holder.destroy() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { holder.main }, modifier = Modifier.fillMaxSize())
        popups.forEach { popup ->
            key(popup) {
                AndroidView(factory = { popup }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** The authorization page plus any provider popups, sharing one callback interceptor. */
private class WebLoginWebViews(
    private val context: Context,
    private val request: SoundCloudOAuth.AuthRequest,
    private val onCallbackUrl: (String) -> Unit,
    private val popups: MutableList<WebView>,
) {
    private var delivered = false

    val main: WebView = create()

    fun destroy() {
        popups.forEach { runCatching { it.destroy() } }
        popups.clear()
        runCatching { main.destroy() }
    }

    private fun create(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        // Same UA as desktop's SoundCloudLoginWindow: Google blocks anything that
        // advertises itself as an embedded WebView, and the mobile-Chrome UA routes
        // SoundCloud's web-auth to a phone flow whose sign-in breaks mid-submit.
        settings.userAgentString = DESKTOP_UA
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean =
                req?.url?.toString()?.let { handle(it) } ?: false

            // JS-driven redirects (provider popups post back to the opener) can bypass
            // shouldOverrideUrlLoading, so the already-loaded location counts too.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                url?.let { handle(it) }
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

        loadUrl(request.url)
    }

    /** True when the URL was our callback and must not be loaded as a page. */
    private fun handle(url: String): Boolean {
        if (!url.startsWith(SoundCloudOAuth.REDIRECT_URI)) return false
        // Whatever page shows the callback (main or popup) is dead weight from here on —
        // otherwise it stays blank white and looks like a hang.
        popups.forEach { runCatching { it.destroy() } }
        popups.clear()
        if (!delivered) {
            delivered = true
            onCallbackUrl(url)
        }
        return true
    }

    private companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
    }
}
