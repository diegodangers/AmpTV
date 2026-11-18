package io.aviaterra.amptv

import android.os.Bundle
import android.media.AudioManager
import android.content.Context
import androidx.activity.compose.BackHandler
import android.os.SystemClock
import android.view.WindowManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val silver = Color(0xFFC3C7C9)
private val navy = Color(0xFF17285B)
private val screen = Color(0xFF08100B)
private val green = Color(0xFF65FF65)
private const val TEST_JAM_URL = "https://open.spotify.com/socialsession/2dFCdR1YvDcJxq6znlXQMV"
private fun jamQr(url: String): Bitmap {
    val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 256, 256)
    val pixels = IntArray(256 * 256) { i ->
        if (matrix[i % 256, i / 256]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, 256, 0, 0, 256, 256)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AmpTVScreen() }
    }
}

@Composable
fun AmpTVScreen() {
    val context = LocalContext.current
    val auth = remember { SpotifyAuth(context.applicationContext) }
    val api = remember { SpotifyApi(auth) }
    val lyricsApi = remember { LyricsApi(context.applicationContext) }
    var lyrics by remember { mutableStateOf<LyricsResult?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricError by remember { mutableStateOf(false) }
    var playbackSampleTime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var androidVolume by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            androidVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            delay(2000)
        }
    }
    var devices by remember { mutableStateOf<List<SpotifyDevice>>(emptyList()) }
    var device by remember { mutableStateOf<SpotifyDevice?>(null) }
    var playbackStatus by remember { mutableStateOf("Playback not started") }
    var playbackBusy by remember { mutableStateOf(false) }
    var activeSession by remember { mutableStateOf<SpotifyPlaybackSession?>(null) }
    var queue by remember { mutableStateOf<List<SpotifyQueueItem>>(emptyList()) }
    var queueStatus by remember { mutableStateOf("Looking for Spotify session…") }
    val queueScroll = rememberLazyListState()
    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    var visualTick by remember { mutableIntStateOf(0) }
    var sessionReady by remember { mutableStateOf(auth.hasSession()) }
    val visPrefs = remember { context.getSharedPreferences("visualizer", android.content.Context.MODE_PRIVATE) }
    var visBands by remember { mutableIntStateOf(visPrefs.getInt("bands", 28)) }
    var visSensitivity by remember { mutableFloatStateOf(visPrefs.getFloat("sensitivity", 1f)) }
    var visDecay by remember { mutableFloatStateOf(visPrefs.getFloat("decay", 0.65f)) }
    var visPeaks by remember { mutableStateOf(visPrefs.getBoolean("peaks", true)) }
    var visColor by remember { mutableStateOf(visPrefs.getString("color", "green") ?: "green") }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf("Visualizer") }
    BackHandler(settingsOpen) { settingsOpen = false }
    val spectrum = remember { AudioSpectrum() }
    SideEffect {
        spectrum.bandCount = visBands
        spectrum.sensitivity = visSensitivity
        spectrum.decay = visDecay
        spectrum.showPeaks = visPeaks
    }
    val spectrumColor = when (visColor) {
        "blue" -> Color(0xFF6AD7FF)
        "classic" -> Color(0xFF8AFF44)
        else -> green
    }

    var fftStatus by remember { mutableStateOf("FFT not tested") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { spectrum.start(); fftStatus = spectrum.status }
        else fftStatus = "FFT permission denied"
    }
    DisposableEffect(Unit) { onDispose { spectrum.stop() } }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            spectrum.start(); fftStatus = spectrum.status
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(sessionReady) {
        if (sessionReady) while (true) {
            try {
                val snapshot = withContext(Dispatchers.IO) { api.playbackSession() }
                activeSession = snapshot
                nowPlaying = snapshot?.track
                playbackSampleTime = SystemClock.elapsedRealtime()
                queueStatus = if (snapshot == null) "No active Spotify session" else
                    "Spotify on ${snapshot.device?.name ?: "unknown device"}"
            } catch (e: Exception) {
                queueStatus = "Session unavailable: ${e.message}"
            }
            delay(2000)
        }
    }
    LaunchedEffect(sessionReady) {
        if (sessionReady) while (true) {
            try {
                queue = withContext(Dispatchers.IO) { api.queue() }
            } catch (e: Exception) {
                queueStatus = "Queue unavailable: ${e.message}"
            }
            delay(12000)
        }
    }
    LaunchedEffect(nowPlaying?.title, nowPlaying?.artist, nowPlaying?.durationMs) {
        if (sessionReady && nowPlaying != null) {
            try { queue = withContext(Dispatchers.IO) { api.queue() } }
            catch (_: Exception) { }
        }
    }
    LaunchedEffect(nowPlaying?.title, nowPlaying?.artist, nowPlaying?.album, nowPlaying?.durationMs) {
        val track = nowPlaying
        lyrics = null
        lyricError = false
        if (track != null) {
            lyricsLoading = true
            try { lyrics = withContext(Dispatchers.IO) { lyricsApi.lookup(track) } }
            catch (_: Exception) { lyricError = true }
            finally { lyricsLoading = false }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(110); visualTick++ } }
    val livePosition = (nowPlaying?.progressMs ?: 0L) +
        if (nowPlaying?.playing == true) (SystemClock.elapsedRealtime() - playbackSampleTime).coerceAtLeast(0L) else 0L
    fun runPlayback(action: (String) -> Unit) {
        if (playbackBusy) return
        val id = activeSession?.device?.id
        if (id.isNullOrBlank()) {
            playbackStatus = "No active Spotify device"
            return
        }
        playbackBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action(id) }
                playbackStatus = "Command sent"
                activeSession = withContext(Dispatchers.IO) { api.playbackSession() }
                nowPlaying = activeSession?.track
                queue = withContext(Dispatchers.IO) { api.queue() }
                playbackSampleTime = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                playbackStatus = "Playback error: ${e.message ?: "Request failed"}"
            } finally { playbackBusy = false }
        }
    }
    fun toggleShuffle() {
        val current = activeSession?.shuffle ?: return
        runPlayback { api.setShuffle(!current, it) }
    }
    fun transferToTv() {
        if (playbackBusy) return
        playbackBusy = true
        scope.launch {
            try {
                val available = withContext(Dispatchers.IO) { api.devices() }
                devices = available
                val target = available.firstOrNull {
                    it.name.contains("onn", true) || it.name.contains("google tv", true)
                } ?: throw IllegalStateException("Onn TV not available in Spotify Connect; open Spotify on the TV")
                if (target.id == activeSession?.device?.id) {
                    playbackStatus = "Already playing on ${target.name}"
                } else {
                    withContext(Dispatchers.IO) {
                        api.transferPlayback(target.id, activeSession?.track?.playing == true)
                    }
                    playbackStatus = "Transfer requested to ${target.name}"
                    delay(1500)
                    activeSession = withContext(Dispatchers.IO) { api.playbackSession() }
                    nowPlaying = activeSession?.track
                    playbackSampleTime = SystemClock.elapsedRealtime()
                    if (activeSession?.device?.id != target.id) playbackStatus = "Transfer pending; check Spotify on TV"
                }
            } catch (e: Exception) {
                playbackStatus = "Transfer error: ${e.message ?: "Request failed"}"
            } finally { playbackBusy = false }
        }
    }
    fun shuffleLikedSongs() {
        if (playbackBusy || activeSession != null) return
        playbackBusy = true
        scope.launch {
            try {
                val available = withContext(Dispatchers.IO) { api.devices() }
                val target = available.firstOrNull {
                    it.name.contains("onn", true) || it.name.contains("google tv", true)
                } ?: throw IllegalStateException("Open Spotify on the Onn TV first")
                val uri = withContext(Dispatchers.IO) { api.randomLikedTrack() }
                withContext(Dispatchers.IO) { api.playTrack(target.id, uri) }
                playbackStatus = "Playing random Liked Song on ${target.name}"
                delay(1200)
                activeSession = withContext(Dispatchers.IO) { api.playbackSession() }
                nowPlaying = activeSession?.track
                playbackSampleTime = SystemClock.elapsedRealtime()
                queue = withContext(Dispatchers.IO) { api.queue() }
            } catch (e: Exception) {
                playbackStatus = if (e.message?.contains("403") == true)
                    "Liked Songs permission denied: reconnect Spotify to authorize library access"
                else "Liked Songs: ${e.message ?: "Request failed"}"
            } finally { playbackBusy = false }
        }
    }
    var authStatus by remember { mutableStateOf(if (auth.hasSession()) "Restoring Spotify session…" else "Spotify not connected") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (auth.hasSession()) {
            sessionReady = true
            authStatus = "Ready"
        }
    }

    if (settingsOpen) {
        Column(Modifier.fillMaxSize().background(Color(0xFF10162D)).padding(14.dp)) {
            Row(Modifier.fillMaxWidth().background(navy).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AMP TV  •  PREFERENCES", color = Color.White, fontSize = 23.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Button(onClick = { settingsOpen = false }) { Text("BACK TO PLAYER") }
            }
            Row(Modifier.fillMaxSize().background(retroNavy).border(2.dp, Color(0xFF8996BC)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.width(210.dp).fillMaxHeight().background(Color(0xFF2A3768)).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Visualizer", "Playback", "Spotify", "Lyrics", "Appearance", "Diagnostics").forEach { name ->
                        Button(onClick = { settingsSection = name }, modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (settingsSection == name) navy else silver,
                                contentColor = if (settingsSection == name) Color.White else Color.Black)) {
                            Text(name.uppercase(), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().background(retroDark).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(settingsSection.uppercase(), color = retroGreen, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                    HorizontalDivider(color = Color(0xFF5B6B99))
                    when (settingsSection) {
                        "Visualizer" -> {
                            Text("FREQUENCY BANDS: $visBands", color = retroGreen)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                listOf(19, 28, 38, 64).forEach { n ->
                                    Button(onClick = { visBands = n; visPrefs.edit().putInt("bands", n).apply() }) { Text("$n") }
                                }
                            }
                            Text("SENSITIVITY: ${(visSensitivity * 100).toInt()}%", color = retroGreen)
                            Slider(value = visSensitivity, onValueChange = {
                                visSensitivity = it; visPrefs.edit().putFloat("sensitivity", it).apply()
                            }, valueRange = 0.3f..2f)
                            Text("DECAY: ${(visDecay * 100).toInt()}%", color = retroGreen)
                            Slider(value = visDecay, onValueChange = {
                                visDecay = it; visPrefs.edit().putFloat("decay", it).apply()
                            }, valueRange = 0f..0.95f)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = visPeaks, onCheckedChange = {
                                    visPeaks = it; visPrefs.edit().putBoolean("peaks", it).apply()
                                })
                                Text("PEAK INDICATORS", color = retroGreen)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                listOf("green", "classic", "blue").forEach { color ->
                                    Button(onClick = { visColor = color; visPrefs.edit().putString("color", color).apply() }) {
                                        Text(color.uppercase())
                                    }
                                }
                            }
                        }
                        "Playback" -> {
                            Text("SESSION DETECTION: 4 SECONDS", color = retroGreen)
                            Text("QUEUE REFRESH: 12 SECONDS", color = retroGreen)
                            Text("DEFAULT: SHUFFLE LIKED SONGS (MANUAL)", color = retroGreen)
                            Text("QUEUE IS ALWAYS THE PRIMARY TRACK LIST", color = retroGreen)
                        }
                        "Spotify" -> {
                            Text(if (sessionReady) "ACCOUNT CONNECTED" else "ACCOUNT DISCONNECTED", color = retroGreen)
                            Text("DEVICE: ${activeSession?.device?.name ?: "NONE"}", color = retroGreen)
                            Button(onClick = { transferToTv(); settingsOpen = false }, enabled = sessionReady && !playbackBusy) {
                                Text("TRANSFER TO TV")
                            }
                            Button(onClick = {
                                auth.signOut(); sessionReady = false
                                auth.signIn({ authStatus = it }, { sessionReady = true; reload++ })
                            }) { Text("REAUTHORIZE SPOTIFY") }
                            Text(authStatus, color = retroGreen)
                        }
                        "Lyrics" -> {
                            Text("LRCLIB V1 • PERSISTENT CACHE", color = retroGreen)
                            Button(onClick = {
                                java.io.File(context.cacheDir, "lrclib").listFiles()?.forEach { it.delete() }
                            }) { Text("CLEAR LYRICS CACHE") }
                            Text("SYNC: SPOTIFY PLAYBACK POSITION", color = retroGreen)
                        }
                        "Appearance" -> {
                            Text("CLASSIC WINAMP 2.X", color = retroGreen)
                            Text("SILVER CHROME • NAVY TITLE BARS", color = retroGreen)
                            Text("GREEN LED DISPLAY • TV-SIZED CONTROLS", color = retroGreen)
                        }
                        "Diagnostics" -> {
                            Text("VERSION: 0.6 DEVELOPMENT", color = retroGreen)
                            Text("FFT: $fftStatus", color = retroGreen)
                            Text("SPOTIFY: $authStatus", color = retroGreen)
                            Text("SESSION: $queueStatus", color = retroGreen)
                            Text("QUEUE TRACKS: ${queue.size}", color = retroGreen)
                            Text("PLAYBACK: $playbackStatus", color = retroGreen)
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Color(0xFF10162D)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Color(0xFF57679B), retroNavy, Color(0xFF0C1530))))
                .border(2.dp, Color(0xFF8895B8)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("AMP TV", color = Color.White, fontFamily = FontFamily.Monospace,
                    fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("  CLASSIC PLAYER", color = Color(0xFFB8C7EA),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                RetroButton("SETTINGS", onClick = { settingsOpen = true })
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    RetroFrame("AmpTV — Main Player", Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RetroDisplay(Modifier.weight(0.9f)) {
                                val pos = livePosition.coerceIn(0L, nowPlaying?.durationMs ?: 0L)
                                RetroReadout("%d:%02d".format(pos / 60000, pos / 1000 % 60), size = 31)
                                RetroSpectrum(spectrum.bands, spectrum.peaks, visBands, visPeaks, visColor)
                            }
                            RetroDisplay(Modifier.weight(1.4f)) {
                                RetroReadout(nowPlaying?.artist ?: "AMP TV", size = 14)
                                RetroReadout(nowPlaying?.title ?: "SPOTIFY CONNECT", size = 17)
                                RetroReadout(if (nowPlaying?.playing == true) "PLAYING" else "PAUSED",
                                    size = 12)
                                val duration = nowPlaying?.durationMs ?: 0L
                                RetroProgress(if (duration > 0) livePosition.toFloat() / duration else 0f)
                                RetroReadout("TOTAL  %d:%02d".format(duration / 60000, duration / 1000 % 60), size = 11)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RetroButton("<<", { runPlayback { api.previous(it) } }, Modifier.weight(1f),
                                enabled = sessionReady && !playbackBusy)
                            RetroButton("PLAY", { runPlayback { api.resume(it) } }, Modifier.weight(1f),
                                enabled = sessionReady && !playbackBusy)
                            RetroButton("PAUSE", { runPlayback { api.pause(it) } }, Modifier.weight(1f),
                                enabled = sessionReady && !playbackBusy)
                            RetroButton(">>", { runPlayback { api.next(it) } }, Modifier.weight(1f),
                                enabled = sessionReady && !playbackBusy)
                            RetroButton("SHUFFLE", { toggleShuffle() }, Modifier.weight(1.5f),
                                enabled = sessionReady && !playbackBusy && activeSession?.shuffle != null,
                                active = activeSession?.shuffle == true)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("VOL", color = retroGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Box(Modifier.weight(1f)) { RetroProgress(androidVolume, accent = retroGreen) }
                            Text(activeSession?.device?.name ?: "NO DEVICE", color = Color(0xFFCED7F3),
                                fontSize = 11.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (!sessionReady) {
                                RetroButton("CONNECT SPOTIFY", {
                                    auth.signIn({ authStatus = it }, { sessionReady = true; reload++ })
                                })
                            } else {
                                RetroButton("TRANSFER TO TV", { transferToTv() }, enabled = !playbackBusy)
                            }
                            Text(playbackStatus, color = Color(0xFFD1DAF4), fontSize = 10.sp, maxLines = 1)
                        }
                    }
                    RetroFrame("Lyrics — LRCLIB", Modifier.fillMaxWidth().weight(1f)) {
                        val lines = lyrics?.lines.orEmpty()
                        val active = lines.indexOfLast { it.timeMs <= livePosition }
                        val lyricScroll = rememberLazyListState()
                        LaunchedEffect(active, lines.size) {
                            if (active >= 0) lyricScroll.animateScrollToItem((active - 2).coerceAtLeast(0))
                        }
                        if (lines.isNotEmpty()) {
                            LazyColumn(Modifier.fillMaxSize().background(retroDark).padding(10.dp),
                                state = lyricScroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(lines) { index, line ->
                                    Text(line.text.ifBlank { "♪" },
                                        color = if (index == active) retroGreen else Color(0xFF5D8A5B),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = if (index == active) 19.sp else 15.sp)
                                }
                            }
                        } else {
                            val message = when {
                                lyricsLoading -> "FINDING LYRICS..."
                                lyricError -> "LYRICS SERVICE UNAVAILABLE"
                                lyrics?.instrumental == true -> "INSTRUMENTAL"
                                !lyrics?.plain.isNullOrBlank() -> lyrics!!.plain!!
                                nowPlaying == null -> "PLAY A SONG TO SEE LYRICS"
                                else -> "LYRICS UNAVAILABLE"
                            }
                            Text(message, Modifier.fillMaxSize().background(retroDark).padding(14.dp),
                                color = retroGreen, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                RetroFrame("Spotify Queue — ${queue.size} Upcoming", Modifier.weight(1.12f).fillMaxHeight()) {
                    RetroDisplay(Modifier.fillMaxWidth()) {
                        RetroReadout(if (nowPlaying == null) "NO ACTIVE TRACK" else
                            "NOW: ${nowPlaying?.artist} — ${nowPlaying?.title}", size = 14)
                    }
                    Text(queueStatus, color = Color(0xFFCCD6EF), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                    if (activeSession == null && sessionReady) {
                        RetroButton("SHUFFLE LIKED SONGS", { shuffleLikedSongs() }, enabled = !playbackBusy)
                    }
                    LazyColumn(Modifier.fillMaxSize().background(retroDark).padding(9.dp),
                        state = queueScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(queue, key = { index, item -> "${item.uri}-$index" }) { index, item ->
                            Text("${index + 1}. ${item.title} — ${item.artist}",
                                color = retroGreen, fontSize = 17.sp, fontFamily = FontFamily.Monospace,
                                maxLines = 1, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
                        }
                    }
                }
            }
        }
    }
}
