package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import models.LyricLine
import models.LyricsResult
import util.Http

private val lyricsJson = Json { ignoreUnknownKeys = true }
private val lrcRegex = Regex("""\[(\d+):(\d{2})\.(\d+)]\s*(.+)""")

suspend fun fetchLyrics(title: String, artist: String, durationSec: Double): LyricsResult? =
    withContext(Dispatchers.IO) {
        runCatching {
            val t = URLEncoder.encode(title.take(100), "UTF-8")
            val a = URLEncoder.encode(artist.take(60), "UTF-8")
            val d = durationSec.toInt()
            val url = "https://lrclib.net/api/get?track_name=$t&artist_name=$a&duration=$d"

            val resp = Http.get(url, headers = mapOf("Lrclib-Client" to "wren/1.0"))
            if (resp.code != 200) return@runCatching null

            val obj = lyricsJson.parseToJsonElement(resp.body).jsonObject

            val synced = obj["syncedLyrics"]?.jsonPrimitive?.content
            if (!synced.isNullOrBlank()) {
                val lines = parseLrc(synced)
                if (lines.isNotEmpty()) return@runCatching LyricsResult(lines, synced = true)
            }

            val plain = obj["plainLyrics"]?.jsonPrimitive?.content
            if (!plain.isNullOrBlank()) {
                // No timing info: timeMs = 0 so the UI never treats a line as "active"
                val lines = plain.lines()
                    .filter { it.isNotBlank() }
                    .map { text -> LyricLine(0L, text.trim()) }
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
