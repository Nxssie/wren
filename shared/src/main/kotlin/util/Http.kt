package util

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The default `User-Agent` for callers that set none of their own. OkHttp's default is
 * `okhttp/<version>`, and some origins answer that with a 520 instead of the content — lrclib
 * behind Cloudflare does — which reads as "there is nothing here" unless the status is checked.
 */
private const val APP_USER_AGENT = "wren (https://github.com/Nxssie/wren)"

/**
 * The single HTTP entry point for shared code. OkHttp is used instead of `java.net.http`
 * because the latter only exists on Android 13+ (API 33) and Wren supports API 26.
 * Calls are blocking on purpose — every caller already runs on an IO dispatcher.
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            // A default, never an override: InnerTube identifies the client by user agent, so
            // replacing the ones callers set deliberately would change who YouTube thinks asks.
            if (request.header("User-Agent") != null) chain.proceed(request)
            else chain.proceed(request.newBuilder().header("User-Agent", APP_USER_AGENT).build())
        }
        .build()

    data class Response(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        execute(
            Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .get()
                .build()
        )

    fun post(
        url: String,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): Response = execute(
        Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
    )

    /** Any other verb (PUT/DELETE…); [body] is sent as JSON when present, else empty. */
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response = execute(
        Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, body?.toRequestBody("application/json".toMediaTypeOrNull()) ?: ByteArray(0).toRequestBody(null))
            .build()
    )

    private fun execute(request: Request): Response =
        client.newCall(request).execute().use { Response(it.code, it.body?.string().orEmpty()) }
}
