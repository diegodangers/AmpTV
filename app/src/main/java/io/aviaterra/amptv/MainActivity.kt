package io.aviaterra.amptv

import android.os.Bundle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AmpTVScreen() }
    }
}

@Composable
fun AmpTVScreen() {
    var selected by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val auth = remember { SpotifyAuth(context.applicationContext) }
    val api = remember { SpotifyApi(auth) }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<SpotifyDevice>>(emptyList()) }
    var device by remember { mutableStateOf<SpotifyDevice?>(null) }
    var playbackStatus by remember { mutableStateOf("Playback not started") }
    var playbackBusy by remember { mutableStateOf(false) }
    fun runPlayback(action: (SpotifyDevice) -> Unit) {
        if (playbackBusy) return
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
            } finally { playbackBusy = false }
        }
    }
    var authStatus by remember { mutableStateOf(if (auth.hasSession()) "Restoring Spotify session…" else "Spotify not connected") }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    var currentPlaylist by remember { mutableStateOf<SpotifyPlaylist?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (auth.hasSession()) {
            loading = true
            try {
                val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.profileName() to api.playlists()
                }
                authStatus = "Connected: ${loaded.first}"
                playlists = loaded.second
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
    val demoTracks = remember { listOf("01. Welcome to AmpTV", "02. Connect your Spotify account", "03. Choose a Spotify Connect device", "04. Start your playlist") }
    Column(Modifier.fillMaxSize().background(Color(0xFF343434)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().background(navy).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AMP TV", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("0.3.0  •  CLASSIC SKIN", color = silver, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }
        Column(Modifier.fillMaxWidth().border(3.dp, Color.White).background(silver).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).background(screen).padding(18.dp)) {
                    Text("AMP TV  --  SPOTIFY", color = green, fontFamily = FontFamily.Monospace, fontSize = 21.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("00:00     128 kbps     STEREO", color = green, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SPOTIFY CONTROLLER", color = navy, fontWeight = FontWeight.Bold)
                    Text(authStatus, color = Color.DarkGray)
                    Button(onClick = { auth.signIn({ authStatus = it }, { reload++ }) }) {
                        Text(if (auth.hasSession()) "RECONNECT" else "CONNECT SPOTIFY")
                    }
                    Text("Output: ${device?.name ?: "TV not selected"}", color = navy)
                    Text(playbackStatus, color = Color.DarkGray, fontSize = 13.sp)
                    Button(onClick = {
                        scope.launch {
                            try {
                                devices = withContext(Dispatchers.IO) { api.devices() }
                                val tv = devices.firstOrNull { it.name.contains("onn", true) || it.name.contains("google tv", true) || it.name.contains("family room tv", true) }
                                if (tv != null) { device = tv; playbackStatus = "TV selected: ${tv.name}" }
                                else playbackStatus = "Select your TV below; open Spotify on TV if absent"
                            } catch (e: Exception) { playbackStatus = "Devices: ${e.message}" }
                        }
                    }, enabled = auth.hasSession()) { Text("FIND TV") }
                    devices.forEach { candidate ->
                        Button(onClick = { device = candidate; playbackStatus = "Selected: ${candidate.name}" }) {
                            Text((if (device?.id == candidate.id) "* " else "") + candidate.name)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PREV", "PLAY", "PAUSE", "NEXT").forEachIndexed { index, label ->
                    Button(onClick = {
                        when (index) {
                            0 -> runPlayback { api.previous(it.id) }
                            1 -> runPlayback { output ->
                                val playlist = currentPlaylist
                                if (playlist != null) api.play(playlist.id, output.id, selected.coerceAtLeast(0))
                                else api.resume(output.id)
                            }
                            2 -> runPlayback { api.pause(it.id) }
                            3 -> runPlayback { api.next(it.id) }
                        }
                    }, enabled = auth.hasSession() && !playbackBusy, shape = RoundedCornerShape(2.dp)) {
                        Text(label, fontSize = 16.sp)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).border(3.dp, Color.White).background(silver).padding(10.dp)) {
            Text(if (currentPlaylist == null) "PLAYLISTS" else "TRACKS: ${currentPlaylist!!.name}", color = Color.White, modifier = Modifier.fillMaxWidth().background(navy).padding(8.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (currentPlaylist != null) {
                Button(onClick = { currentPlaylist = null; tracks = emptyList(); selected = 0 }) { Text("BACK TO PLAYLISTS") }
            }
            if (loading) Text("Loading Spotify…", color = navy)
            val entries = if (currentPlaylist != null) tracks.map { "${it.name} — ${it.artist}" }
                          else if (auth.hasSession()) playlists.map { it.name } else demoTracks
            LazyColumn(Modifier.fillMaxSize().background(screen).padding(8.dp)) {
                itemsIndexed(entries) { index, track ->
                    var focused by remember { mutableStateOf(false) }
                    val highlighted = focused || selected == index
                    Text(
                        text = track,
                        color = if (highlighted) Color.White else green,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused }
                            .background(if (highlighted) Color(0xFF1749B5) else screen)
                            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent)
                            .clickable { selected = index; if (currentPlaylist == null && auth.hasSession() && index < playlists.size) currentPlaylist = playlists[index] }
                            .padding(10.dp),
                        fontSize = 19.sp,
                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Text("MVP 0.3 • SPOTIFY CONNECT • SELECT PLAYLIST AND PRESS PLAY", color = silver, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}