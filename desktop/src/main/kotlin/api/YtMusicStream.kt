package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import util.Log
import java.util.concurrent.TimeUnit

private val streamJson = Json { ignoreUnknownKeys = true }

// Call at app start to pre-establish TCP+TLS so the first real request doesn't pay connection cost.
fun warmupStreamConnection() {
    Thread {
        runCatching {
            java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build()
                .send(
                    java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("https://www.youtube.com/"))
                        .GET()
                        .timeout(java.time.Duration.ofSeconds(4))
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()
                )
        }
    }.also { it.isDaemon = true }.start()
}

private data class CachedUrl(val url: String, val fetchedAt: Long)
private val urlCache = mutableMapOf<String, CachedUrl>()
private const val CACHE_TTL_MS = 4 * 60 * 60 * 1000L // 4 horas

// Cache yt-dlp path
private var ytDlpPath: String? = null
private var ytDlpChecked = false

suspend fun resolveStreamUrl(videoId: String): String? {
    val cached = urlCache[videoId]
    if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
        return cached.url
    }
    return fetchStreamUrl(videoId)?.also { urlCache[videoId] = CachedUrl(it, System.currentTimeMillis()) }
}

private suspend fun fetchStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val ytDlp = findYtDlp() ?: run {
            Log.e("YtMusicStream", "yt-dlp not found — cannot resolve stream for videoId=$videoId")
            return@runCatching null
        }
        val process = ProcessBuilder(
            ytDlp,
            "--dump-json",
            "--no-playlist",
            "--quiet",
            "--no-warnings",
            "--prefer-free-formats",
            "--format", "bestaudio[ext=m4a]/bestaudio/best",
            "https://www.youtube.com/watch?v=$videoId"
        ).redirectErrorStream(false).start()

        val stderr = process.errorStream.bufferedReader()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(10, TimeUnit.SECONDS)

        if (!finished) {
            Log.e("YtMusicStream", "yt-dlp timed out after 10s for videoId=$videoId")
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0 || output.isBlank()) {
            val err = stderr.use { it.readText() }.trim()
            Log.e("YtMusicStream", "yt-dlp exited ${process.exitValue()} for videoId=$videoId: $err")
            return@runCatching null
        }

        // Parse the JSON output to get the stream URL
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(output).jsonObject
        root["url"]?.jsonPrimitive?.content
    }.onFailure { Log.e("YtMusicStream", "Exception resolving stream for videoId=$videoId", it) }
        .getOrNull()
}

private val isWindows = System.getProperty("os.name").lowercase().contains("win")

private fun findYtDlp(): String? {
    if (ytDlpChecked) return ytDlpPath
    ytDlpChecked = true

    val envPath = System.getenv("YTDLP_PATH")
    if (!envPath.isNullOrBlank() && java.io.File(envPath).canExecute()) {
        ytDlpPath = envPath
        return envPath
    }

    // Check common absolute install locations by filesystem lookup — no "which"/"where"
    // subprocess, since "which" doesn't exist on Windows.
    val candidates = if (isWindows) emptyList() else listOf(
        "/snap/bin/yt-dlp",
        "/usr/local/bin/yt-dlp",
        "/usr/bin/yt-dlp"
    )

    for (path in candidates) {
        if (java.io.File(path).canExecute()) {
            ytDlpPath = path
            return path
        }
    }

    // Fall back to PATH resolution: ProcessBuilder/CreateProcess both search PATH for the
    // executable name, so this works cross-platform without any OS-specific lookup tool.
    val names = if (isWindows) listOf("yt-dlp.exe", "yt-dlp") else listOf("yt-dlp")
    for (name in names) {
        val found = runCatching {
            val proc = ProcessBuilder(name, "--version").start()
            val ver = proc.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = proc.waitFor()
            exitCode == 0 && ver.isNotEmpty()
        }.getOrDefault(false)
        if (found) {
            ytDlpPath = name
            return name
        }
    }

    ytDlpPath = null
    return null
}
