package io.aviaterra.amptv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpotifyPlaylist(val id: String, val name: String, val total: Int)
data class SpotifyTrack(val name: String, val artist: String, val album: String)

class SpotifyApi(private val auth: SpotifyAuth) {
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
                    names.joinToString(", "), track.optJSONObject("album")?.optString("name") ?: ""))
            }
            next = response.optString("next").takeIf { it.startsWith("https://api.spotify.com/") }
        }
        return result
    }
}
