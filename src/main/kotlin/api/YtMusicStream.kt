package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val streamClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(java.time.Duration.ofSeconds(5))
    .build()

// Call at app start to pre-establish TCP+TLS so the first real request doesn't pay connection cost.
fun warmupStreamConnection() {
    Thread {
        runCatching {
            streamClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("https://www.youtube.com/"))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(4))
                    .build(),
                HttpResponse.BodyHandlers.discarding()
            )
        }
    }.also { it.isDaemon = true }.start()
}
private val streamJson = Json { ignoreUnknownKeys = true }

private data class CachedUrl(val url: String, val fetchedAt: Long)
private val urlCache = mutableMapOf<String, CachedUrl>()
private const val CACHE_TTL_MS = 4 * 60 * 60 * 1000L // 4 horas

suspend fun resolveStreamUrl(videoId: String): String? {
    val cached = urlCache[videoId]
    if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
        return cached.url
    }
    return fetchStreamUrl(videoId)?.also { urlCache[videoId] = CachedUrl(it, System.currentTimeMillis()) }
}

private fun buildPlayerRequest(videoId: String): HttpRequest {
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "ANDROID")
                put("clientVersion", "19.29.37")
                put("androidSdkVersion", 30)
                put("hl", "en")
                put("gl", "US")
            }
        }
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
    }.toString()

    return HttpRequest.newBuilder()
        .uri(URI.create("https://www.youtube.com/youtubei/v1/player?prettyPrint=false"))
        .header("Content-Type", "application/json")
        .header("User-Agent", "com.google.android.youtube/19.29.37 (Linux; U; Android 11) gzip")
        .header("X-YouTube-Client-Name", "3")
        .header("X-YouTube-Client-Version", "19.29.37")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
}

private suspend fun fetchStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val response = streamClient.send(buildPlayerRequest(videoId), HttpResponse.BodyHandlers.ofInputStream())

        // Read the response in chunks and stop as soon as we hit "videoDetails" —
        // streamingData.adaptiveFormats appears before it, so we skip the remaining ~70% of the body.
        val sb = StringBuilder()
        response.body().bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(8192)
            while (sb.length < 512 * 1024) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
                if (sb.contains("\"videoDetails\"")) break
            }
        }

        extractAudioUrl(sb)
    }.getOrNull()
}

private fun extractAudioUrl(sb: StringBuilder): String? {
    val cutoff = sb.indexOf("\"videoDetails\"").takeIf { it > 0 }
    val jsonStr = if (cutoff != null) repairJson(sb.substring(0, cutoff).trimEnd(',', ' ')) else sb.toString()

    return runCatching {
        val root = streamJson.parseToJsonElement(jsonStr).jsonObject
        root["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.filter { it["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true }
            ?.maxByOrNull { it["bitrate"]?.jsonPrimitive?.intOrNull ?: 0 }
            ?.get("url")?.jsonPrimitive?.content
    }.getOrNull()
}

// Closes open JSON braces/brackets so the truncated partial response is parseable.
private fun repairJson(partial: String): String {
    val closers = ArrayDeque<Char>()
    var inString = false
    var escape = false

    for (c in partial) {
        if (escape) { escape = false; continue }
        if (c == '\\' && inString) { escape = true; continue }
        if (c == '"') { inString = !inString; continue }
        if (inString) continue
        when (c) {
            '{' -> closers.addLast('}')
            '[' -> closers.addLast(']')
            '}', ']' -> if (closers.isNotEmpty()) closers.removeLast()
        }
    }

    // If we stopped mid-string, back up to the last safe boundary and retry
    if (inString) {
        val lastQuote = partial.lastIndexOf('"')
        if (lastQuote > 0) return repairJson(partial.substring(0, lastQuote).trimEnd(',', ':', ' '))
    }

    return partial + closers.reversed().joinToString("")
}
