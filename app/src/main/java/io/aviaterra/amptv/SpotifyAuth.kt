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
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class SpotifyAuth(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("spotify_session", Context.MODE_PRIVATE)
    private val redirect = "http://127.0.0.1:8888/callback"
    private val scopes = "user-read-private playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state"
    private val lock = Any()
    @Volatile private var token: String? = null
    @Volatile private var refresh: String? = null
    @Volatile private var expiry: Long = 0

    init {
        try {
            val stored = prefs.getString("session", null)
            if (stored != null) {
                val raw = Base64.decode(stored, Base64.NO_WRAP)
                val iv = raw.copyOfRange(0, 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                val obj = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size))))
                token = obj.optString("access", "").ifBlank { null }
                refresh = obj.optString("refresh", "").ifBlank { null }
                expiry = obj.optLong("expiry")
            }
        } catch (_: Exception) { prefs.edit().remove("session").apply() }
    }

    fun hasSession(): Boolean = token != null || refresh != null

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("amptv_spotify", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("amptv_spotify", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun save() {
        val obj = JSONObject().put("access", token).put("refresh", refresh).put("expiry", expiry)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(obj.toString().toByteArray())
        prefs.edit().putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun exchange(form: String): JSONObject {
        val connection = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(form.toByteArray()) }
            if (connection.responseCode !in 200..299) throw IllegalStateException("Token HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun acceptTokens(obj: JSONObject) {
        token = obj.getString("access_token")
        refresh = obj.optString("refresh_token").ifBlank { refresh }
        expiry = System.currentTimeMillis() + obj.optLong("expires_in", 3600) * 1000L
        save()
    }

    fun validToken(): String = synchronized(lock) {
        if (token != null && System.currentTimeMillis() < expiry - 60_000) return@synchronized token!!
        val r = refresh ?: throw IllegalStateException("Sign in required")
        try {
            acceptTokens(exchange("grant_type=refresh_token&refresh_token=${Uri.encode(r)}&client_id=${Uri.encode(BuildConfig.SPOTIFY_CLIENT_ID)}"))
            token ?: throw IllegalStateException("Missing access token")
        } catch (e: Exception) {
            // Keep session on transient network errors; user can retry.
            throw e
        }
    }

    fun signOut() = synchronized(lock) {
        token = null; refresh = null; expiry = 0
        prefs.edit().remove("session").apply()
    }

    fun signIn(report: (String) -> Unit, connected: () -> Unit) {
        Thread {
            fun status(value: String) = main.post { report(value) }
            fun success() = main.post { connected() }
            val random = SecureRandom()
            fun randomUrlSafe(size: Int): String = ByteArray(size).also(random::nextBytes)
                .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
            val verifier = randomUrlSafe(48)
            val state = randomUrlSafe(24)
            val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
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
                        .appendQueryParameter("code_challenge", challenge).build()
                    val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(context.packageManager) == null) {
                        status("No TV browser available"); return@Thread
                    }
                    status("Waiting for Spotify authorization…")
                    context.startActivity(intent)
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val request = socket.getInputStream().bufferedReader().readLine() ?: ""
                        val target = request.split(" ").getOrNull(1) ?: ""
                        val uri = Uri.parse("http://127.0.0.1:8888$target")
                        val code = if (uri.path == "/callback" && uri.getQueryParameter("state") == state) uri.getQueryParameter("code") else null
                        val page = if (code != null) "Authorization received. Return to AmpTV." else "Authorization failed."
                        val body = "<html><body><h2>$page</h2></body></html>".toByteArray()
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(header.toByteArray() + body)
                        socket.getOutputStream().flush()
                        if (code == null) { status("Authorization cancelled or invalid state"); return@Thread }
                        val form = "grant_type=authorization_code&code=${Uri.encode(code)}&redirect_uri=${Uri.encode(redirect)}&client_id=${Uri.encode(BuildConfig.SPOTIFY_CLIENT_ID)}&code_verifier=${Uri.encode(verifier)}"
                        synchronized(lock) { acceptTokens(exchange(form)) }
                        status("Spotify connected")
                        success()
                    }
                }
            } catch (_: SocketTimeoutException) {
                status("Spotify sign-in timed out")
            } catch (e: Exception) {
                status("Sign-in failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }
}
