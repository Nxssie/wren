package com.wren.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import auth.SoundCloudWebSignIn
import kotlinx.coroutines.CompletableDeferred
import util.Log
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Puts a DataDome captcha in front of the user and hands back the verdict cookie it issues.
 *
 * When SoundCloud's bot protection refuses a write with a captcha URL, nothing the app can do on
 * its own will clear it; a person has to. This opens the challenge page on screen, and when the
 * captcha is passed, the answer call it makes carries a fresh `datadome` cookie for
 * soundcloud.com. That request is intercepted so the value can be read out and stored for the
 * app's own writes, and the page's cookie jar is updated as well, for the browser fallback.
 */
object DataDomeChallenge {
    internal const val TAG = "DataDomeChallenge"
    private const val ORIGIN = "https://soundcloud.com/"
    internal const val EXTRA_URL = "challenge_url"

    /** At most one challenge at a time; the activity completes it. */
    private var pending: CompletableDeferred<String?>? = null

    /** Shows [challengeUrl]; the new verdict cookie, or null when the user left without solving it. */
    suspend fun solve(context: Context, challengeUrl: String): String? {
        pending?.complete(null)
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        Log.i(TAG, "showing the captcha")
        context.startActivity(
            Intent(context, DataDomeChallengeActivity::class.java)
                .putExtra(EXTRA_URL, challengeUrl)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return deferred.await().also { pending = null }
    }

    internal fun deliver(cookie: String?) {
        Log.i(TAG, "captcha ${if (cookie == null) "dismissed" else "passed"}")
        pending?.complete(cookie)
    }

    /**
     * The cookie a passed captcha issues, in the body of its answer call. The value is stored on
     * the page's jar too, so the browser fallback sends it on its next write.
     */
    internal fun cookieFromCheckResponse(body: String): String? {
        val match = Regex("""datadome=([^;"\\]+)""").find(body) ?: return null
        return match.groupValues[1].takeIf(String::isNotBlank)
    }

    internal fun storeOnPageJar(value: String) {
        CookieManager.getInstance().setCookie(
            ORIGIN,
            "${SoundCloudWebSignIn.DATA_DOME_KEY}=$value; Domain=.soundcloud.com; Path=/; Secure",
        )
        CookieManager.getInstance().flush()
    }

    internal fun withReferer(challengeUrl: String): String =
        challengeUrl + (if ('?' in challengeUrl) "&" else "?") +
            "referer=" + URLEncoder.encode(ORIGIN, "UTF-8")
}

class DataDomeChallengeActivity : ComponentActivity() {
    private var delivered = false
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(DataDomeChallenge.EXTRA_URL)
        if (url == null) { finish(); return }
        title = "SoundCloud needs a check"

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val target = request.url
                    val isCheck = target.host?.endsWith("captcha-delivery.com") == true &&
                        target.path?.contains("/captcha/check") == true
                    if (!isCheck || request.method != "GET") return null
                    return runCatching { relayCheck(target.toString(), request.requestHeaders) }
                        .onFailure { Log.w(DataDomeChallenge.TAG, "could not relay the captcha answer", it) }
                        .getOrNull()
                }
            }
        }
        setContentView(web)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.loadUrl(DataDomeChallenge.withReferer(url))
    }

    /**
     * Performs the answer call in the page's stead and reads the cookie out of it before handing
     * the same body back to the page, so the captcha widget carries on as if nothing happened.
     */
    private fun relayCheck(url: String, headers: Map<String, String>): WebResourceResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        val code = conn.responseCode
        val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText().orEmpty()
        val mime = conn.contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: "application/json"
        DataDomeChallenge.cookieFromCheckResponse(body)?.let { value ->
            DataDomeChallenge.storeOnPageJar(value)
            deliver(value)
            runOnUiThread { finish() }
        }
        val passthrough = conn.headerFields
            .filterKeys { it != null && !it.equals("set-cookie", ignoreCase = true) }
            .mapKeys { it.key!! }
            .mapValues { it.value.joinToString(", ") }
        return WebResourceResponse(mime, "utf-8", code, conn.responseMessage?.ifBlank { null } ?: "OK", passthrough, ByteArrayInputStream(body.toByteArray()))
    }

    private fun deliver(cookie: String?) {
        if (delivered) return
        delivered = true
        DataDomeChallenge.deliver(cookie)
    }

    override fun onDestroy() {
        deliver(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
