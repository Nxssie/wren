package player

import androidx.compose.runtime.mutableStateOf
import api.resolveStreamUrl
import api.warmupStreamConnection
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

data class QueueItem(val url: String, val videoId: String, val title: String)

class MpvPlayer {
    private val socketPath = "/tmp/native-player-mpv.sock"
    private var process: Process? = null
    private var channel: SocketChannel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    val isPlaying = mutableStateOf(false)
    val isLoading = mutableStateOf(false)
    val position = mutableStateOf(0.0)
    val duration = mutableStateOf(0.0)
    val volume = mutableStateOf(50)
    val currentTitle = mutableStateOf("")   // stores videoId as current track key
    val displayTitle = mutableStateOf("")   // human-readable title for UI
    val queue = mutableStateOf<List<QueueItem>>(emptyList())
    val queueIndex = mutableStateOf(-1)

    fun start() {
        File(socketPath).delete()
        warmupStreamConnection()

        process = ProcessBuilder(
            listOf("mpv", "--no-video", "--idle=yes",
                "--input-ipc-server=$socketPath",
                "--volume=50", "--really-quiet",
                "--cache-pause-initial=no",
                "--demuxer-readahead-secs=5")
        ).start()

        scope.launch {
            delay(800)
            connectSocket()
            observeProperties()
            readLoop()
        }
    }

    private fun connectSocket() {
        repeat(15) {
            runCatching {
                val addr = UnixDomainSocketAddress.of(socketPath)
                val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
                ch.connect(addr)
                channel = ch
                return
            }
            Thread.sleep(200)
        }
    }

    private fun observeProperties() {
        send("""{"command":["observe_property",1,"time-pos"]}""")
        send("""{"command":["observe_property",2,"duration"]}""")
        send("""{"command":["observe_property",3,"pause"]}""")
        send("""{"command":["observe_property",4,"volume"]}""")
    }

    private suspend fun readLoop() {
        val buf = ByteBuffer.allocate(8192)
        val sb = StringBuilder()

        while (scope.isActive) {
            try {
                buf.clear()
                val n = channel?.read(buf) ?: break
                if (n <= 0) { delay(30); continue }

                sb.append(String(buf.array(), 0, n))
                var nl: Int
                while (sb.indexOf("\n").also { nl = it } >= 0) {
                    handleLine(sb.substring(0, nl))
                    sb.delete(0, nl + 1)
                }
            } catch (_: Exception) {
                delay(100)
            }
        }
    }

    private fun handleLine(line: String) {
        runCatching {
            val obj = json.parseToJsonElement(line).jsonObject
            when (obj["event"]?.jsonPrimitive?.content) {
                "property-change" -> when (obj["name"]?.jsonPrimitive?.content) {
                    "time-pos" -> position.value = obj["data"]?.jsonPrimitive?.doubleOrNull ?: return
                    "duration" -> {
                        val d = obj["data"]?.jsonPrimitive?.doubleOrNull ?: return
                        duration.value = d
                        if (d > 0) isLoading.value = false
                    }
                    "pause" -> isPlaying.value = !(obj["data"]?.jsonPrimitive?.booleanOrNull ?: true)
                    "volume" -> volume.value = (obj["data"]?.jsonPrimitive?.doubleOrNull ?: return).toInt()
                }
                "end-file" -> {
                    if (obj["reason"]?.jsonPrimitive?.content == "eof") next()
                }
            }
        }
    }

    private fun send(cmd: String) {
        runCatching {
            channel?.write(ByteBuffer.wrap("$cmd\n".toByteArray()))
        }
    }

    fun load(url: String, videoId: String, title: String = "") {
        queue.value = emptyList()
        queueIndex.value = -1
        loadInternal(url, videoId, title)
    }

    fun loadQueue(items: List<QueueItem>, startIndex: Int = 0) {
        queue.value = items
        queueIndex.value = startIndex
        val item = items[startIndex]
        loadInternal(item.url, item.videoId, item.title)
        prefetchAt(items, startIndex + 1, count = 4)
    }

    fun next() {
        val q = queue.value
        val idx = queueIndex.value + 1
        if (idx < q.size) {
            queueIndex.value = idx
            val item = q[idx]
            loadInternal(item.url, item.videoId, item.title)
            prefetchAt(q, idx + 1)
        }
    }

    fun previous() {
        val q = queue.value
        val idx = queueIndex.value - 1
        if (idx >= 0) {
            queueIndex.value = idx
            val item = q[idx]
            loadInternal(item.url, item.videoId, item.title)
            prefetchAt(q, idx - 1)
        }
    }

    private fun prefetchAt(items: List<QueueItem>, index: Int, count: Int = 1) {
        items.subList(index.coerceAtLeast(0), (index + count).coerceAtMost(items.size))
            .forEach { item -> scope.launch { resolveStreamUrl(item.videoId) } }
    }

    private fun loadInternal(url: String, videoId: String, title: String = "") {
        currentTitle.value = videoId
        displayTitle.value = title
        isPlaying.value = true
        isLoading.value = true
        duration.value = 0.0
        position.value = 0.0
        scope.launch {
            val streamUrl = resolveStreamUrl(videoId) ?: url
            val escaped = streamUrl.replace("\"", "\\\"")
            send("""{"command":["loadfile","$escaped"]}""")
        }
    }

    fun playPause() {
        send("""{"command":["cycle","pause"]}""")
    }

    fun seek(seconds: Double) {
        send("""{"command":["seek",$seconds,"absolute"]}""")
    }

    fun setVolume(vol: Int) {
        send("""{"command":["set_property","volume",$vol]}""")
        volume.value = vol
    }

    fun stop() {
        scope.cancel()
        process?.destroy()
        File(socketPath).delete()
    }
}
