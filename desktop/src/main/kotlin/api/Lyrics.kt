package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import models.LyricLine
import models.LyricsResult

private val lyricsClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(6))
    .build()
private val lyricsJson = Json { ignoreUnknownKeys = true }
private val lrcRegex = Regex("""\[(\d+):(\d{2})\.(\d+)]\s*(.+)""")

suspend fun fetchLyrics(title: String, artist: String, durationSec: Double): LyricsResult? =
    withContext(Dispatchers.IO) {
        runCatching {
            val t = URLEncoder.encode(title.take(100), "UTF-8")
            val a = URLEncoder.encode(artist.take(60), "UTF-8")
            val d = durationSec.toInt()
            val url = "https://lrclib.net/api/get?track_name=$t&artist_name=$a&duration=$d"

            val req = HttpRequest.newBuilder(URI.create(url))
                .header("Lrclib-Client", "wren/1.0 (desktop)")
                .GET().build()

            val resp = lyricsClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) return@runCatching null

            val obj = lyricsJson.parseToJsonElement(resp.body()).jsonObject

            val synced = obj["syncedLyrics"]?.jsonPrimitive?.content
            if (!synced.isNullOrBlank()) {
                val lines = parseLrc(synced)
                if (lines.isNotEmpty()) return@runCatching LyricsResult(lines, synced = true)
            }

            val plain = obj["plainLyrics"]?.jsonPrimitive?.content
            if (!plain.isNullOrBlank()) {
                val lines = plain.lines()
                    .filter { it.isNotBlank() }
                    .mapIndexed { i, text -> LyricLine(i * 4000L, text.trim()) }
                return@runCatching LyricsResult(lines, synced = false)
            }

            null
        }.getOrNull()
    }

private fun parseLrc(lrc: String): List<LyricLine> =
    lrc.lines().mapNotNull { line ->
        val m = lrcRegex.find(line.trim()) ?: return@mapNotNull null
        val min  = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val sec  = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val ms   = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        val text = m.groupValues[4].trim()
        if (text.isEmpty()) return@mapNotNull null
        LyricLine(min * 60_000 + sec * 1_000 + ms, text)
    }.sortedBy { it.timeMs }
