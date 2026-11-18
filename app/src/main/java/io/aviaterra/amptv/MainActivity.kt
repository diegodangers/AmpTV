package io.aviaterra.amptv

import android.os.Bundle
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
    val spectrum = remember { AudioSpectrum() }
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
            delay(4000)
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

    Column(Modifier.fillMaxSize().background(Color(0xFF343434)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().background(navy).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AMP TV", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("0.6  •  QUEUE", color = silver, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(152.dp).border(2.dp, Color.White).background(silver).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().background(screen).padding(10.dp), verticalArrangement = Arrangement.Center) {
                Text(nowPlaying?.title ?: "AMP TV  --  SPOTIFY", color = green, fontFamily = FontFamily.Monospace, fontSize = 17.sp, maxLines = 1)
                Text(nowPlaying?.artist ?: playbackStatus, color = green, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                val position = nowPlaying?.progressMs ?: 0L
                val duration = nowPlaying?.durationMs ?: 0L
                Text("${position / 60000}:${(position / 1000 % 60).toString().padStart(2, '0')} / ${duration / 60000}:${(duration / 1000 % 60).toString().padStart(2, '0')}",
                    color = green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Canvas(Modifier.fillMaxWidth().height(19.dp)) {
                    val tick = visualTick
                    val bars = 28
                    val width = size.width / (bars * 1.5f)
                    for (i in 0 until bars) {
                        val wave = spectrum.bands.getOrElse(i) { 0f }
                        val height = size.height * wave
                        drawRect(green, topLeft = Offset(i * size.width / bars, size.height - height), size = Size(width, height))
                    }
                }
            }
            Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("PREV", "PLAY", "PAUSE", "NEXT").forEachIndexed { index, label ->
                        Button(
                            onClick = {
                                when (index) {
                                    0 -> runPlayback { api.previous(it) }
                                    1 -> runPlayback { api.resume(it) }
                                    2 -> runPlayback { api.pause(it) }
                                    3 -> runPlayback { api.next(it) }
                                }
                            },
                            enabled = auth.hasSession() && !playbackBusy,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(38.dp),
                            shape = RoundedCornerShape(2.dp)
                        ) { Text(label, fontSize = 13.sp) }
                    }

                }
                if (authStatus != "Ready") Text(authStatus, color = navy, fontSize = 12.sp, maxLines = 1)
                if (!auth.hasSession()) {
                    Button(onClick = { auth.signIn({ authStatus = it }, { sessionReady = true; reload++ }) }, modifier = Modifier.height(35.dp)) { Text("CONNECT SPOTIFY") }
                }
            }
            Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(activeSession?.device?.name ?: "NO ACTIVE DEVICE", color = navy, fontSize = 12.sp, maxLines = 1)
                Button(onClick = { transferToTv() }, enabled = sessionReady && !playbackBusy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Text("TRANSFER TO TV", fontSize = 12.sp)
                }
                Text(playbackStatus, color = navy, fontSize = 10.sp, maxLines = 2)
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).border(2.dp, Color.White).background(silver).padding(6.dp)) {
            Text("LYRICS  •  LRCLIB", modifier = Modifier.fillMaxWidth().background(navy).padding(6.dp),
                color = Color.White, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            val lines = lyrics?.lines.orEmpty()
            val active = lines.indexOfLast { it.timeMs <= livePosition }
            val lyricScroll = rememberLazyListState()
            LaunchedEffect(active, lines.size) {
                if (active >= 0) lyricScroll.animateScrollToItem((active - 2).coerceAtLeast(0))
            }
            if (lines.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxSize().background(screen).padding(8.dp), state = lyricScroll,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(lines) { index, line ->
                        Text(line.text.ifBlank { "♪" }, color = if (index == active) Color.White else green.copy(alpha = 0.55f),
                            fontSize = if (index == active) 21.sp else 17.sp,
                            fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                val message = when {
                    lyricsLoading -> "Finding lyrics…"
                    lyricError -> "Lyrics service unavailable"
                    lyrics?.instrumental == true -> "Instrumental"
                    !lyrics?.plain.isNullOrBlank() -> lyrics!!.plain!!
                    nowPlaying == null -> "Play a song to see lyrics"
                    else -> "Lyrics unavailable"
                }
                Text(message, Modifier.fillMaxSize().background(screen).padding(14.dp),
                    color = green, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
            }
        }
            }
        Column(Modifier.weight(1.25f).fillMaxHeight().border(2.dp, Color.White).background(silver).padding(6.dp)) {
            Text("SPOTIFY QUEUE  •  ${queue.size} UPCOMING",
                modifier = Modifier.fillMaxWidth().background(navy).padding(6.dp),
                color = Color.White, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(queueStatus, color = navy, fontSize = 12.sp, modifier = Modifier.padding(5.dp))
            if (activeSession == null && sessionReady) {
                Button(onClick = { shuffleLikedSongs() }, enabled = !playbackBusy,
                    modifier = Modifier.fillMaxWidth().padding(6.dp)) {
                    Text("SHUFFLE LIKED SONGS")
                }
                Text(playbackStatus, color = navy, fontSize = 12.sp, modifier = Modifier.padding(5.dp))
                Button(onClick = {
                    auth.signOut()
                    sessionReady = false
                    auth.signIn({ authStatus = it }, { sessionReady = true; reload++ })
                }, modifier = Modifier.padding(6.dp)) {
                    Text("REAUTHORIZE SPOTIFY", fontSize = 11.sp)
                }
            }
            LazyColumn(Modifier.fillMaxSize().background(screen).padding(6.dp), state = queueScroll) {
                itemsIndexed(queue, key = { index, item -> "${item.uri}-$index" }) { index, item ->
                    Text("${index + 1}. ${item.title} — ${item.artist}",
                        color = green, fontSize = 19.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp))
                }
            }
        }

        }
    }
}