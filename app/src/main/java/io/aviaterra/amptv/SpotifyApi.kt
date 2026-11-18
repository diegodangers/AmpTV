package io.aviaterra.amptv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpotifyPlaylist(val id: String, val name: String, val total: Int)
data class SpotifyTrack(val name: String, val artist: String, val album: String, val uri: String)

data class NowPlaying(val title: String, val artist: String, val progressMs: Long, val durationMs: Long, val playing: Boolean, val album: String = "")

data class SpotifyDevice(val id: String, val name: String, val active: Boolean)

class SpotifyApi(private val auth: SpotifyAuth) {
    private fun request(method: String, path: String, payload: String? = null): JSONObject {
        val connection = URL("https://api.spotify.com/v1/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Authorization", "Bearer ${auth.validToken()}")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val message = try { JSONObject(body).optJSONObject("error")?.optString("message") ?: "" } catch (_: Exception) { "" }
                throw IllegalStateException("Spotify HTTP $status" + if (message.isNotBlank()) ": ${message.take(120)}" else "")
            }
            return if (status == 204) JSONObject() else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun nowPlaying(): NowPlaying? {
        val connection = URL("https://api.spotify.com/v1/me/player/currently-playing").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("Authorization", "Bearer ${auth.validToken()}")
            val status = connection.responseCode
            if (status == 204) return null
            if (status !in 200..299) throw IllegalStateException("Playback status HTTP $status")
            val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val item = data.optJSONObject("item") ?: return null
            val artists = item.optJSONArray("artists")
            val artist = (0 until (artists?.length() ?: 0)).mapNotNull {
                artists?.optJSONObject(it)?.optString("name")
            }.joinToString(", ")
            return NowPlaying(item.optString("name", "Unknown track"), artist,
                data.optLong("progress_ms"), item.optLong("duration_ms"), data.optBoolean("is_playing"), item.optJSONObject("album")?.optString("name") ?: "")
        } finally { connection.disconnect() }
    }

    fun devices(): List<SpotifyDevice> {
        val array = request("GET", "me/player/devices").getJSONArray("devices")
        return (0 until array.length()).mapNotNull { i ->
            val d = array.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optString("id")
            if (id.isBlank() || d.optBoolean("is_restricted")) null
            else SpotifyDevice(id, d.optString("name", "Spotify device"), d.optBoolean("is_active"))
        }
    }

    fun play(playlistId: String, deviceId: String, trackUri: String? = null) {
        val payload = JSONObject().put("context_uri", "spotify:playlist:$playlistId")
        if (trackUri != null) payload.put("offset", JSONObject().put("uri", trackUri))
        request("PUT", "me/player/play?device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}", payload.toString())
    }

    fun resume(deviceId: String) {
        request("PUT", "me/player/play?device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}")
    }

    fun pause(deviceId: String) {
        request("PUT", "me/player/pause?device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}")
    }

    fun next(deviceId: String) {
        request("POST", "me/player/next?device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}")
    }

    fun previous(deviceId: String) {
        request("POST", "me/player/previous?device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}")
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Authorization", "Bearer ${auth.validToken()}")
            if (connection.responseCode !in 200..299) {
                val status = connection.responseCode
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val details = try {
                    val error = JSONObject(body).opt("error")
                    when (error) {
                        is JSONObject -> error.optString("message", error.optString("reason", ""))
                        is String -> error
                        else -> ""
                    }
                } catch (_: Exception) { "" }
                val path = URL(url).path
                throw IllegalStateException("HTTP $status at $path" + if (details.isNotBlank()) ": ${details.take(160)}" else "")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun profileName(): String = get("https://api.spotify.com/v1/me").optString("display_name", "Spotify user")

    fun playlists(): List<SpotifyPlaylist> {
        val result = mutableListOf<SpotifyPlaylist>()
        val userId = get("https://api.spotify.com/v1/me").getString("id")
        var next: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var pages = 0
        while (next != null && pages++ < 10) {
            val response = get(next)
            val items = response.getJSONArray("items")
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val id = p.optString("id")
                if (id.isNotBlank() && p.optJSONObject("owner")?.optString("id") == userId) {
                    result.add(SpotifyPlaylist(id, p.optString("name", "Untitled playlist"),
                        p.optJSONObject("tracks")?.optInt("total") ?: p.optJSONObject("items")?.optInt("total") ?: 0))
                }
            }
            next = response.optString("next").takeIf { it.startsWith("https://api.spotify.com/") }
        }
        return result
    }

    fun tracks(playlistId: String): List<SpotifyTrack> {
        val result = mutableListOf<SpotifyTrack>()
        var next: String? = "https://api.spotify.com/v1/playlists/$playlistId/items?limit=50"
        var pages = 0
        while (next != null && pages++ < 10) {
            val response = get(next)
            val items = response.getJSONArray("items")
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val track = item.optJSONObject("item") ?: item.optJSONObject("track") ?: continue
                if (track.optString("type") != "track") continue
                val artists = track.optJSONArray("artists")
                val names = mutableListOf<String>()
                if (artists != null) for (j in 0 until artists.length()) {
                    artists.optJSONObject(j)?.optString("name")?.let { names.add(it) }
                }
                result.add(SpotifyTrack(track.optString("name", "Unknown track"),
                    names.joinToString(", "), track.optJSONObject("album")?.optString("name") ?: "", track.optString("uri")))
            }
            next = response.optString("next").takeIf { it.startsWith("https://api.spotify.com/") }
        }
        return result
    }
}
