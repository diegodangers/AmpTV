package io.aviaterra.amptv

import android.os.Bundle
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
    var authStatus by remember { mutableStateOf("Spotify not connected") }
    val demoTracks = remember { listOf("01. Welcome to AmpTV", "02. Connect your Spotify account", "03. Choose a Spotify Connect device", "04. Start your playlist") }
    Column(Modifier.fillMaxSize().background(Color(0xFF343434)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().background(navy).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AMP TV", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("0.1.0  •  CLASSIC SKIN", color = silver, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }
        Column(Modifier.fillMaxWidth().border(3.dp, Color.White).background(silver).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).background(screen).padding(18.dp)) {
                    Text("AMP TV  --  NOT CONNECTED", color = green, fontFamily = FontFamily.Monospace, fontSize = 21.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("00:00     128 kbps     STEREO", color = green, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SPOTIFY CONTROLLER", color = navy, fontWeight = FontWeight.Bold)
                    Text(authStatus, color = Color.DarkGray)
                    Button(onClick = { auth.signIn { authStatus = it } }) {
                        Text("CONNECT SPOTIFY")
                    }
                    Text("Client: ${BuildConfig.SPOTIFY_CLIENT_ID.take(8)}…", color = Color.DarkGray, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("|◀", "▶", "Ⅱ", "■", "▶|").forEach { label ->
                    Button(onClick = { /* disabled until Spotify OAuth and Connect are implemented */ }, enabled = false, shape = RoundedCornerShape(2.dp), colors = ButtonDefaults.buttonColors(disabledContainerColor = Color.Gray, disabledContentColor = Color.White)) { Text(label, fontSize = 18.sp) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).border(3.dp, Color.White).background(silver).padding(10.dp)) {
            Text("PLAYLIST EDITOR", color = Color.White, modifier = Modifier.fillMaxWidth().background(navy).padding(8.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxSize().background(screen).padding(8.dp)) {
                itemsIndexed(demoTracks) { index, track ->
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
                            .clickable { selected = index }
                            .padding(10.dp),
                        fontSize = 19.sp,
                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Text("MVP 0.1 • D-PAD PLAYLIST NAVIGATION • SPOTIFY AUTH COMING NEXT", color = silver, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}