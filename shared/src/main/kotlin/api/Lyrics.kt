package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URLEncoder
import models.LyricLine
import models.LyricsResult
import util.Http
import util.Log
import util.TtlCache
import kotlin.math.abs

private val lyricsJson = Json { ignoreUnknownKeys = true }
private val lrcRegex = Regex("""\[(\d+):(\d{2})\.(\d+)]\s*(.+)""")

private const val TAG = "Lyrics"
private const val CLIENT_HEADER = "Lrclib-Client"
/** What lrclib asks for so they can reach out before blocking rather than just blocking. */
private const val CLIENT_VALUE = "wren (https://github.com/Nxssie/wren)"
/** How far a search hit's duration may sit from ours before it is a different recording. */
private const val DURATION_TOLERANCE_SEC = 4.0
/** Lyrics do not change, so this only bounds how long a correction waits to be picked up. */
private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

/**
 * First words of a bracketed extra that names the upload rather than the song. Matching the first
 * word keeps "(Live)", "(Acoustic)" and "(feat. …)" — those change the recording, and lrclib
 * indexes them.
 */
private val VIDEO_NOISE = setOf(
    "official", "lyric", "lyrics", "audio", "video", "hd", "hq", "4k", "8k",
    "remaster", "remastered", "visualizer", "music", "explicit", "clean", "stereo", "mono",
)

private val BRACKETED = Regex("[\\[(]([^)\\]]*)[)\\]]")
private val TRAILING_TOPIC = Regex("""\s*[-–—]\s*topic\s*$""", RegexOption.IGNORE_CASE)

/** A resolved lookup, wrapped so "no lyrics" is a cacheable answer rather than a missing value. */
private class CachedLyrics(val result: LyricsResult?)

private val cache = TtlCache<String, CachedLyrics>(CACHE_TTL_MS, maxEntries = 32)

/**
 * Lyrics for one track, or `null` when the source genuinely has none.
 *
 * The title and artist are cleaned first: a music video's title carries "(Official Video)" and
 * friends, and lrclib indexes the song, so asking with the noise is what made it miss.
 *
 * Two lookups, because the exact one is strict: `/api/get` compares track name, artist *and*
 * duration. `/api/search` is fuzzy and catches whatever the exact call still misses; the duration
 * is then checked before the hit is used, so a live or remastered take is not pasted onto the
 * studio recording.
 *
 * A request that failed **throws** rather than answering `null`. "There are no lyrics" and "we
 * could not ask" are different answers, and lrclib hands out 503s under load, so showing the
 * second as the first would be a lie the user cannot tell from the truth.
 */
suspend fun fetchLyrics(title: String, artist: String, durationSec: Double): LyricsResult? {
    val key = lookupKey(title, artist, durationSec)
    cache.peek(key)?.let {
        Log.i(TAG, "cached for '$title' / '$artist': ${it.result?.lines?.size ?: 0} lines")
        return it.result
    }

    val result = withContext(Dispatchers.IO) { resolve(title, artist, durationSec) }
    cache.put(key, CachedLyrics(result))
    return result
}

private fun lookupKey(title: String, artist: String, durationSec: Double): String =
    "${title.lowercase().trim()}|${artist.lowercase().trim()}|${durationSec.toInt()}"

private fun resolve(title: String, artist: String, durationSec: Double): LyricsResult? {
    val track = cleanTitle(title)
    val who = cleanArtist(artist)
    if (track != title || who != artist) Log.i(TAG, "asking as '$track' / '$who'")

    val exact = exactRecord(track, who, durationSec)
    recordToLyrics(exact)?.let {
        Log.i(TAG, "exact hit for '$track' / '$who' (${it.lines.size} lines, synced=${it.synced})")
        return it
    }

    val found = searchRecord(track, who, durationSec)
    recordToLyrics(found)?.let {
        val name = found?.get("trackName")?.jsonPrimitive?.contentOrNull.orEmpty()
        val by = found?.get("artistName")?.jsonPrimitive?.contentOrNull.orEmpty()
        Log.i(TAG, "search hit for '$track' / '$who' -> '$name' / '$by'")
        return it
    }

    Log.i(TAG, "no lyrics for '$track' / '$who'")
    return null
}

/**
 * Drops the bracketed extras that name the upload rather than the song, and a trailing channel
 * suffix. "Red Flags (Official Video)" becomes "Red Flags", which is what lrclib indexes.
 */
internal fun cleanTitle(raw: String): String = BRACKETED
    .replace(raw) { match ->
        val first = match.groupValues[1].trim().substringBefore(' ').lowercase().trimEnd('.', '!')
        if (first in VIDEO_NOISE) " " else match.value
    }
    .replace(TRAILING_TOPIC, "")
    .replace(Regex("""\s{2,}"""), " ")
    .trim()
    .ifBlank { raw.trim() }

/** "Radiohead - Topic" and "RadioheadVEVO" are the channel, not the artist. */
internal fun cleanArtist(raw: String): String = raw
    .replace(TRAILING_TOPIC, "")
    .removeSuffix("VEVO")
    .trim()
    .ifBlank { raw.trim() }

/** One `/api/get` record. A 404 is the source answering "none", not a failure. */
private fun exactRecord(title: String, artist: String, durationSec: Double): JsonObject? {
    val url = "https://lrclib.net/api/get" +
        "?track_name=${encode(title.take(100))}" +
        "&artist_name=${encode(artist.take(60))}" +
        "&duration=${durationSec.toInt()}"
    val response = Http.get(url, headers = mapOf(CLIENT_HEADER to CLIENT_VALUE))
    if (response.code == 404) return null
    failIfNotSuccessful("get", response)
    return runCatching { lyricsJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        ?: throw IOException("lrclib: get returned an unparseable body")
}

/**
 * The closest `/api/search` hit whose duration agrees with ours, or null when nothing is close
 * enough to be the same recording. An unknown duration cannot be checked, so the first word
 * match is taken — the lyrics are better than none, and the song is playing either way.
 */
private fun searchRecord(title: String, artist: String, durationSec: Double): JsonObject? {
    val query = encode("$title $artist".take(160))
    val response = Http.get(
        "https://lrclib.net/api/search?q=$query",
        headers = mapOf(CLIENT_HEADER to CLIENT_VALUE),
    )
    failIfNotSuccessful("search", response)

    val hits = runCatching { lyricsJson.parseToJsonElement(response.body).jsonArray }.getOrNull()
        ?: throw IOException("lrclib: search returned an unparseable body")
    val candidates = hits.mapNotNull { it as? JsonObject }
        .filter { it["instrumental"]?.jsonPrimitive?.booleanOrNull != true }
    if (candidates.isEmpty()) return null
    if (durationSec <= 0.0) return candidates.first()

    return candidates.minByOrNull { distanceFrom(it, durationSec) }
        ?.takeIf { distanceFrom(it, durationSec) <= DURATION_TOLERANCE_SEC }
}

private fun failIfNotSuccessful(what: String, response: Http.Response) {
    if (response.isSuccessful) return
    Log.w(TAG, "$what returned ${response.code}: ${response.body.take(200).replace('\n', ' ')}")
    throw IOException("lrclib: $what returned ${response.code}")
}

private fun distanceFrom(record: JsonObject, durationSec: Double): Double {
    val duration = record["duration"]?.jsonPrimitive?.doubleOrNull ?: return Double.MAX_VALUE
    return abs(duration - durationSec)
}

private fun recordToLyrics(record: JsonObject?): LyricsResult? {
    record ?: return null

    val synced = record["syncedLyrics"]?.jsonPrimitive?.contentOrNull
    if (!synced.isNullOrBlank()) {
        val lines = parseLrc(synced)
        if (lines.isNotEmpty()) return LyricsResult(lines, synced = true)
    }

    val plain = record["plainLyrics"]?.jsonPrimitive?.contentOrNull
    if (!plain.isNullOrBlank()) {
        // No timing info: timeMs = 0 so the UI never treats a line as "active"
        val lines = plain.lines().filter { it.isNotBlank() }.map { LyricLine(0L, it.trim()) }
        if (lines.isNotEmpty()) return LyricsResult(lines, synced = false)
    }

    return null
}

private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

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
