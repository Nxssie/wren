package download

import api.resolveStreamUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import models.QueueItem
import models.Source
import okhttp3.Request
import okhttp3.Response
import util.Http
import util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves a track to disk. Stream URLs come from [resolveStreamUrl], so each platform keeps
 * using its own resolver (yt-dlp on desktop, InnerTube/SoundCloud HTTP on Android) and the
 * download itself is a plain ranged-free copy — no ffmpeg or extra binary needed.
 *
 * State is keyed by the track key (SoundCloud permalink / YouTube video id) so any row in
 * the UI can render the progress of the download it started.
 */
object DownloadManager {

    sealed interface State {
        data class Downloading(val progress: Float) : State
        data class Done(val path: String) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun enqueue(item: QueueItem, destination: Destination) {
        val key = item.url
        if (!active.add(key)) return
        _states.update { it + (key to State.Downloading(0f)) }
        scope.launch {
            runCatching { download(item, destination) }
                .onSuccess { path -> _states.update { it + (key to State.Done(path)) } }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "download failed for ${item.title}", error)
                    _states.update { it + (key to State.Failed(error.message ?: "download failed")) }
                }
            active.remove(key)
        }
    }

    fun dismiss(key: String) {
        _states.update { it - key }
    }

    private suspend fun download(item: QueueItem, destination: Destination): String {
        val streamUrl = resolveStreamUrl(item.url)
            ?: error("could not resolve a stream for ${item.title}")

        val request = Request.Builder().url(streamUrl)
            .header("User-Agent", if (item.source == Source.SOUNDCLOUD) SC_UA else ANDROID_UA)
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("stream returned HTTP ${response.code}")
            val ext = extensionFor(response)
            val output = destination.open(fileName(item, ext))
            try {
                val total = response.body?.contentLength() ?: -1L
                var written = 0L
                response.body?.byteStream()?.use { input ->
                    output.stream.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val progress = (written.toFloat() / total).coerceIn(0f, 1f)
                                _states.update { it + (item.url to State.Downloading(progress)) }
                            }
                        }
                        out.flush()
                    }
                } ?: error("empty response body")
                output.commit()
                Log.i(TAG, "downloaded ${File(output.path).name} (${written / 1024} KiB)")
                return output.path
            } catch (e: Throwable) {
                output.abort()
                throw e
            }
        }
    }

    private fun extensionFor(response: Response): String {
        val mime = response.body?.contentType()?.toString()?.substringBefore(';')?.trim()?.lowercase()
        mimeToExtension[mime]?.let { return it }
        val path = response.request.url.encodedPath.lowercase()
        return listOf("m4a", "mp3", "opus", "ogg", "webm", "aac", "wav")
            .firstOrNull { path.endsWith(".$it") } ?: "m4a"
    }
}

private const val TAG = "DownloadManager"

private const val SC_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
private const val ANDROID_UA =
    "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12; GB) gzip"

private val mimeToExtension = mapOf(
    "audio/mp4" to "m4a",
    "audio/m4a" to "m4a",
    "audio/x-m4a" to "m4a",
    "audio/mpeg" to "mp3",
    "audio/mp3" to "mp3",
    "audio/webm" to "weba",
    "audio/ogg" to "ogg",
    "application/ogg" to "ogg",
    "audio/opus" to "opus",
    "audio/aac" to "aac",
)

private val illegalChars = Regex("[\\\\/:*?\"<>|\\x00-\\x1f]")

internal fun fileName(item: QueueItem, ext: String): String {
    val base = listOf(item.artist, item.title)
        .filter { it.isNotBlank() }
        .joinToString(" - ")
        .replace(illegalChars, "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
        .take(150)
    return "${base.ifEmpty { "track" }}.$ext"
}
