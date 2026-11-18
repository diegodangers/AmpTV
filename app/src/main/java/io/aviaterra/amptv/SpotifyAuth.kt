package io.aviaterra.amptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Prototype TV-local PKCE authorization.
 * Requires an installed browser on the TV and the Spotify dashboard redirect
 * http://127.0.0.1:8888/callback. Tokens are held in memory only.
 */
class SpotifyAuth(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val redirect = "http://127.0.0.1:8888/callback"
    private val scopes = "user-read-private playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state"
    @Volatile var accessToken: String? = null
        private set

    fun signIn(report: (String) -> Unit) {
        Thread {
            fun status(value: String) = main.post { report(value) }
            val random = SecureRandom()
            fun randomUrlSafe(size: Int): String = ByteArray(size).also(random::nextBytes)
                .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
            val verifier = randomUrlSafe(48)
            val state = randomUrlSafe(24)
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            try {
                ServerSocket(8888, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 120_000
                    val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                        .appendQueryParameter("response_type", "code")
                        .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                        .appendQueryParameter("redirect_uri", redirect)
                        .appendQueryParameter("scope", scopes)
                        .appendQueryParameter("state", state)
                        .appendQueryParameter("code_challenge_method", "S256")
                        .appendQueryParameter("code_challenge", challenge)
                        .build()
                    val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(context.packageManager) == null) {
                        status("No browser installed on this TV. Phone-based sign-in is next.")
                        return@Thread
                    }
                    status("Waiting for Spotify browser authorization…")
                    context.startActivity(intent)
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val request = socket.getInputStream().bufferedReader().readLine() ?: ""
                        val target = request.split(" ").getOrNull(1) ?: ""
                        val uri = Uri.parse("http://127.0.0.1:8888$target")
                        val valid = uri.path == "/callback" && uri.getQueryParameter("state") == state
                        val code = if (valid) uri.getQueryParameter("code") else null
                        val page = if (code != null) "Authorization received. Return to AmpTV." else "Authorization failed. Return to AmpTV."
                        val body = "<html><body><h2>$page</h2></body></html>".toByteArray()
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(header.toByteArray() + body)
                        socket.getOutputStream().flush()
                        if (code == null) {
                            status("Spotify authorization rejected or callback state mismatch.")
                            return@Thread
                        }
                        val form = "grant_type=authorization_code&code=${Uri.encode(code)}&redirect_uri=${Uri.encode(redirect)}&client_id=${Uri.encode(BuildConfig.SPOTIFY_CLIENT_ID)}&code_verifier=${Uri.encode(verifier)}"
                        val connection = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
                        try {
                            connection.requestMethod = "POST"
                            connection.connectTimeout = 15000
                            connection.readTimeout = 15000
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                            connection.outputStream.use { it.write(form.toByteArray()) }
                            if (connection.responseCode !in 200..299) {
                                status("Spotify token exchange failed (HTTP ${connection.responseCode}).")
                                return@Thread
                            }
                            accessToken = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("access_token")
                        } finally { connection.disconnect() }
                        val profile = URL("https://api.spotify.com/v1/me").openConnection() as HttpURLConnection
                        try {
                            profile.setRequestProperty("Authorization", "Bearer $accessToken")
                            profile.connectTimeout = 15000
                            profile.readTimeout = 15000
                            if (profile.responseCode !in 200..299) {
                                status("Authorized, but profile lookup failed (HTTP ${profile.responseCode}).")
                            } else {
                                val name = JSONObject(profile.inputStream.bufferedReader().use { it.readText() })
                                    .optString("display_name", "Spotify user")
                                status("Connected to Spotify: $name")
                            }
                        } finally { profile.disconnect() }
                    }
                }
            } catch (_: SocketTimeoutException) {
                status("Spotify sign-in timed out. Try again.")
            } catch (e: Exception) {
                status("Sign-in failed: ${e.javaClass.simpleName}. Check TV browser and callback settings.")
            }
        }.start()
    }
}
