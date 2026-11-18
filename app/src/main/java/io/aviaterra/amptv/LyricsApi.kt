package io.aviaterra.amptv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsResult(val lines: List<LyricLine>, val plain: String?, val instrumental: Boolean = false)

class LyricsApi {
    private val cache = mutableMapOf<String, LyricsResult?>()
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun fetch(path: String): String? {
        val c = URL("https://lrclib.net$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "AmpTV/0.6 (https://github.com/diegodangers/AmpTV)")
            return when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                429 -> throw IllegalStateException("LRCLIB rate limited")
                else -> throw IllegalStateException("LRCLIB HTTP ${c.responseCode}")
            }
        } finally { c.disconnect() }
    }
    private fun parse(record: JSONObject): LyricsResult {
        val lines = mutableListOf<LyricLine>()
        val pattern = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\](.*)""")
        record.optString("syncedLyrics", "").lineSequence().forEach { row ->
            val m = pattern.matchEntire(row.trim()) ?: return@forEach
            val fraction = (m.groupValues[3] + "000").take(3).toLong()
            val ms = m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + fraction
            lines.add(LyricLine(ms, m.groupValues[4].trim()))
        }
        return LyricsResult(lines.sortedBy { it.timeMs }, record.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }, record.optBoolean("instrumental"))
    }
    fun lookup(track: NowPlaying): LyricsResult? {
        val key = "${track.title}|${track.artist}|${track.album}|${track.durationMs}"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val seconds = (track.durationMs / 1000).coerceIn(1, 3600)
        val base = "/api/get?track_name=${encode(track.title)}&artist_name=${encode(track.artist)}&duration=$seconds"
        val exact = fetch(base + if (track.album.isNotBlank()) "&album_name=${encode(track.album)}" else "")
            ?: fetch(base)
        var result = exact?.let { parse(JSONObject(it)) }
        if (result == null || result.lines.isEmpty()) {
            val search = fetch("/api/search?track_name=${encode(track.title)}&artist_name=${encode(track.artist)}")
            if (search != null) {
                val array = JSONArray(search)
                val matches = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .filter {
                        it.optString("trackName").equals(track.title, true) &&
                        it.optString("artistName").equals(track.artist, true) &&
                        abs(it.optDouble("duration") - track.durationMs / 1000.0) <= 2.5
                    }
                    .sortedWith(compareByDescending<JSONObject> { it.optString("albumName").equals(track.album, true) }
                        .thenByDescending { it.optString("syncedLyrics").isNotBlank() })
                result = matches.firstOrNull()?.let { parse(it) } ?: result
            }
        }
        synchronized(cache) { cache[key] = result }
        return result
    }
}
