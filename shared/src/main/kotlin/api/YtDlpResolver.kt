package api

import kotlinx.serialization.json.*
import util.Log
import java.util.concurrent.TimeUnit

/**
 * yt-dlp based resolution (desktop). yt-dlp handles YouTube signature deciphering and
 * SoundCloud progressive transcodings, but needs the binary in PATH or a known location.
 */
object YtDlpResolver : StreamResolver {

    override suspend fun resolve(trackKey: String): String? {
        val ytDlp = findYtDlp() ?: run {
            Log.e("YtDlpResolver", "yt-dlp not found — cannot resolve stream for key=$trackKey")
            return null
        }
        // SoundCloud keys are full permalinks; YouTube keys are bare video IDs.
        val target = if (trackKey.startsWith("http")) trackKey else "https://www.youtube.com/watch?v=$trackKey"
        val format = if (isSoundCloud(target))
            "bestaudio[protocol^=http]/bestaudio"
        else
            "bestaudio[ext=m4a]/bestaudio/best"

        return runCatching {
            val process = ProcessBuilder(
                ytDlp,
                "--dump-json",
                "--no-playlist",
                "--quiet",
                "--no-warnings",
                "--prefer-free-formats",
                "--format", format,
                target
            ).redirectErrorStream(false).start()

            val stderr = process.errorStream.bufferedReader()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(10, TimeUnit.SECONDS)

            if (!finished) {
                Log.e("YtDlpResolver", "yt-dlp timed out after 10s for key=$trackKey")
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0 || output.isBlank()) {
                val err = stderr.use { it.readText() }.trim()
                Log.e("YtDlpResolver", "yt-dlp exited ${process.exitValue()} for key=$trackKey: $err")
                return@runCatching null
            }

            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(output).jsonObject
            root["url"]?.jsonPrimitive?.content
        }.onFailure { Log.e("YtDlpResolver", "Exception resolving stream for key=$trackKey", it) }
            .getOrNull()
    }
}

private val isWindows = System.getProperty("os.name").lowercase().contains("win")

private var ytDlpPath: String? = null
private var ytDlpChecked = false

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
