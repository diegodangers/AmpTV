package io.aviaterra.amptv

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsResult(val lines: List<LyricLine>, val plain: String?, val instrumental: Boolean = false)

class LyricsApi(context: Context) {
    private val cacheDir = File(context.applicationContext.cacheDir, "lrclib").apply { mkdirs() }
    private val maxBytes = 50L * 1024 * 1024
    private val dayMs = 24L * 60 * 60 * 1000
    private fun cacheFile(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.json")
    }
    private fun readCache(key: String): LyricsResult? {
        val file = cacheFile(key)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val expires = json.optLong("expiresAt")
            if (System.currentTimeMillis() >= expires) {
                file.delete()
                null
            } else if (json.optBoolean("missing")) {
                LyricsResult(emptyList(), null)
            } else parse(json.getJSONObject("result"))
        } catch (_: Exception) { file.delete(); null }
    }
    private fun hasValidMissing(key: String): Boolean {
        val file = cacheFile(key)
        if (!file.isFile) return false
        return try {
            val json = JSONObject(file.readText())
            json.optBoolean("missing") && System.currentTimeMillis() < json.optLong("expiresAt")
        } catch (_: Exception) { false }
    }
    private fun writeCache(key: String, result: LyricsResult?) {
        try {
            val days = when {
                result == null -> 1L
                result.lines.isNotEmpty() -> 30L
                result.plain != null || result.instrumental -> 7L
                else -> 1L
            }
            val payload = JSONObject().put("expiresAt", System.currentTimeMillis() + days * dayMs)
                .put("missing", result == null)
            if (result != null) {
                val lines = JSONArray()
                result.lines.forEach { lines.put(JSONObject().put("timeMs", it.timeMs).put("text", it.text)) }
                payload.put("result", JSONObject().put("lines", lines)
                    .put("plain", result.plain ?: JSONObject.NULL)
                    .put("instrumental", result.instrumental))
            }
            val file = cacheFile(key)
            val temp = File(cacheDir, file.name + ".tmp")
            temp.writeText(payload.toString())
            if (!temp.renameTo(file)) { file.writeText(payload.toString()); temp.delete() }
            trimCache()
        } catch (_: Exception) { /* Cache failure must not interrupt lyrics */ }
    }
    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.extension == "json" } ?: return
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }
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
        if (record.has("lines")) {
            val arr = record.optJSONArray("lines") ?: JSONArray()
            return LyricsResult((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map { LyricLine(it.optLong("timeMs"), it.optString("text")) },
                record.optString("plain").takeIf { it.isNotBlank() && it != "null" },
                record.optBoolean("instrumental"))
        }
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
        if (hasValidMissing(key)) return null
        readCache(key)?.let { return it }
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
        writeCache(key, result)
        return result
    }
}
