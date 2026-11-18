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
    var selected by remember { mutableIntStateOf(0) }
    val playlistScroll = rememberLazyListState()
    val trackScroll = rememberLazyListState()
    val context = LocalContext.current
    val auth = remember { SpotifyAuth(context.applicationContext) }
    val api = remember { SpotifyApi(auth) }
    val lyricsApi = remember { LyricsApi(context.applicationContext) }
    var lyrics by remember { mutableStateOf<LyricsResult?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricError by remember { mutableStateOf(false) }
    var playbackSampleTime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val scope = rememberCoroutineScope()
    val jamImage = remember { jamQr(TEST_JAM_URL).asImageBitmap() }
    var devices by remember { mutableStateOf<List<SpotifyDevice>>(emptyList()) }
    var device by remember { mutableStateOf<SpotifyDevice?>(null) }
    var playbackStatus by remember { mutableStateOf("Playback not started") }
    var playbackBusy by remember { mutableStateOf(false) }
    var currentPlaylist by remember { mutableStateOf<SpotifyPlaylist?>(null) }
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
            try { nowPlaying = withContext(Dispatchers.IO) { api.nowPlaying() }; playbackSampleTime = SystemClock.elapsedRealtime() }
            catch (_: Exception) { }
            delay(4000)
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
    fun runPlayback(action: (SpotifyDevice) -> Unit) {
        if (playbackBusy) return
        val listState = if (currentPlaylist != null) trackScroll else playlistScroll
        val view = currentPlaylist?.id
        val savedIndex = listState.firstVisibleItemIndex
        val savedOffset = listState.firstVisibleItemScrollOffset
        playbackBusy = true
        scope.launch {
            try {
                val available = withContext(Dispatchers.IO) { api.devices() }
                devices = available
                val chosen = available.firstOrNull { it.id == device?.id }
                    ?: available.firstOrNull { it.name.contains("onn", true) || it.name.contains("google tv", true) || it.name.contains("family room tv", true) }
                if (chosen == null) {
                    playbackStatus = if (available.isEmpty()) "No devices: open Spotify on the Onn TV"
                        else "Select your TV below before playing"
                } else {
                    device = chosen
                    withContext(Dispatchers.IO) { action(chosen) }
                    playbackStatus = "Sent to ${chosen.name}"
                }
            } catch (e: Exception) {
                playbackStatus = "Playback error: ${e.message ?: "Request failed"}"
            } finally {
                playbackBusy = false
                if (currentPlaylist?.id == view &&
                    (listState.firstVisibleItemIndex != savedIndex ||
                     listState.firstVisibleItemScrollOffset != savedOffset)) {
                    listState.scrollToItem(savedIndex, savedOffset)
                }
            }
        }
    }
    var authStatus by remember { mutableStateOf(if (auth.hasSession()) "Restoring Spotify session…" else "Spotify not connected") }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (auth.hasSession()) {
            loading = true
            try {
                val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.profileName() to api.playlists()
                }
                authStatus = "Ready"
                playlists = loaded.second
                sessionReady = true
            } catch (e: Exception) {
                authStatus = "Spotify: ${e.message ?: "Request failed"}"
            } finally { loading = false }
        }
    }
    LaunchedEffect(currentPlaylist) {
        val p = currentPlaylist
        if (p != null) {
            loading = true
            tracks = emptyList()
            try {
                tracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.tracks(p.id) }
            } catch (e: Exception) {
                authStatus = "Playlist error: ${e.message ?: "Request failed"}"
            } finally { loading = false }
        }
    }
    fun playSelectedTrack(index: Int) {
        val playlist = currentPlaylist ?: return
        selected = index
        runPlayback { output -> api.play(playlist.id, output.id, tracks.getOrNull(index)?.uri) }
    }
    val demoTracks = remember { listOf("Connect Spotify to see your playlists") }

    Column(Modifier.fillMaxSize().background(Color(0xFF343434)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().background(navy).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AMP TV", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("0.5.2  •  NAV FIX", color = silver, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
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
                                    0 -> runPlayback { api.previous(it.id) }
                                    1 -> runPlayback { api.resume(it.id) }
                                    2 -> runPlayback { api.pause(it.id) }
                                    3 -> runPlayback { api.next(it.id) }
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
            Column(Modifier.width(82.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(bitmap = jamImage, contentDescription = "Join Spotify Jam QR",
                    modifier = Modifier.size(70.dp).background(Color.White).padding(2.dp))
                Text("JOIN JAM", color = navy, fontSize = 10.sp)
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
            Row(Modifier.fillMaxWidth().background(navy).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (currentPlaylist == null) "PLAYLISTS  (${playlists.size})" else "TRACKS  •  ${currentPlaylist!!.name}",
                    color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                    modifier = Modifier.weight(1f))
                if (currentPlaylist != null) {
                    Button(onClick = { currentPlaylist = null; tracks = emptyList(); selected = 0 }, modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("BACK", fontSize = 13.sp) }
                }
            }
            if (loading) Text("Loading Spotify…", color = navy)
            val entries = if (currentPlaylist != null) tracks.map { "${it.name} — ${it.artist}" }
                else if (auth.hasSession()) playlists.map { it.name } else demoTracks
            LazyColumn(Modifier.fillMaxSize().background(screen).padding(6.dp),
                state = if (currentPlaylist == null) playlistScroll else trackScroll) {
                itemsIndexed(entries, key = { index, _ ->
                    if (currentPlaylist == null) "playlist-$index" else "track-${currentPlaylist!!.id}-$index"
                }) { index, entry ->
                    var focused by remember { mutableStateOf(false) }
                    val highlighted = focused || selected == index
                    Text(entry, color = if (highlighted) Color.White else green,
                        modifier = Modifier.fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused }
                            .background(if (highlighted) Color(0xFF1749B5) else screen)
                            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent)
                            .clickable {
                                if (currentPlaylist == null && auth.hasSession() && index < playlists.size) {
                                    currentPlaylist = playlists[index]
                                    selected = 0
                                } else if (currentPlaylist != null && index < tracks.size) {
                                    playSelectedTrack(index)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        fontSize = 20.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }        }

    }
}