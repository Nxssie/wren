package util

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The single HTTP entry point for shared code. OkHttp is used instead of `java.net.http`
 * because the latter only exists on Android 13+ (API 33) and Wren supports API 26.
 * Calls are blocking on purpose — every caller already runs on an IO dispatcher.
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

    private fun execute(request: Request): Response =
        client.newCall(request).execute().use { Response(it.code, it.body?.string().orEmpty()) }
}
