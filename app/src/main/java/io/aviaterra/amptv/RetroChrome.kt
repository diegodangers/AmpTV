package io.aviaterra.amptv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val retroNavy = Color(0xFF1D2A59)
val retroGreen = Color(0xFF79F45B)
val retroDark = Color(0xFF060E0A)
private val retroLight = Color(0xFF8390BA)
private val retroMid = Color(0xFF364476)

@Composable
fun RetroFrame(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier
        .border(2.dp, Color(0xFF10172F))
        .border(1.dp, retroLight)
        .background(Brush.verticalGradient(listOf(Color(0xFF455486), retroNavy, Color(0xFF172044))))
        .padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0xFF6476AB), Color(0xFF25366D), Color(0xFF17244D))))
            .border(1.dp, Color(0xFF8396C4)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = Color(0xFFF0F2FF), fontFamily = FontFamily.Monospace,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        content()
    }
}

@Composable
fun RetroButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, active: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val top = if (active) Color(0xFF729F70) else Color(0xFFAAB5D2)
    val bottom = if (active) Color(0xFF335D38) else Color(0xFF526189)
    Box(modifier.height(39.dp)
        .border(if (focused) 3.dp else 1.dp, if (focused) retroGreen else Color(0xFF10172C))
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .onFocusChanged { focused = it.isFocused }
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            color = if (enabled) Color(0xFF101A32) else Color.DarkGray, maxLines = 1)
    }
}

@Composable
fun RetroDisplay(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.border(2.dp, Color(0xFF6C79A0))
        .background(retroDark).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
fun RetroReadout(text: String, modifier: Modifier = Modifier, size: Int = 17) {
    Text(text, modifier, color = retroGreen, fontFamily = FontFamily.Monospace,
        fontSize = size.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun RetroProgress(fraction: Float, modifier: Modifier = Modifier, accent: Color = Color(0xFFE3A078)) {
    Canvas(modifier.fillMaxWidth().height(11.dp)) {
        drawRect(Color(0xFF07111B))
        val safe = fraction.coerceIn(0f, 1f)
        drawRect(Color(0xFF47547B), size = Size(size.width, 2.dp.toPx()))
        drawRect(accent, topLeft = Offset(0f, 3.dp.toPx()),
            size = Size(size.width * safe, 5.dp.toPx()))
        val knobX = (size.width * safe).coerceIn(0f, (size.width - 5.dp.toPx()).coerceAtLeast(0f))
        drawRect(Color(0xFFD8E0F0), topLeft = Offset(knobX, 1.dp.toPx()),
            size = Size(5.dp.toPx(), 9.dp.toPx()))
    }
}

@Composable
fun RetroSpectrum(bands: List<Float>, peaks: List<Float>, count: Int, showPeaks: Boolean,
    palette: String, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        val n = count.coerceIn(19, 64)
        val step = size.width / n
        val width = step * 0.65f
        for (i in 0 until n) {
            val level = bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val segments = (level * 13).toInt()
            for (j in 0 until segments) {
                val color = when (palette) {
                    "blue" -> Color(0xFF65CBFF)
                    "classic" -> if (j >= 10) Color(0xFFFD6650) else if (j >= 7) Color(0xFFEED54D) else retroGreen
                    else -> retroGreen
                }
                val h = size.height / 14f
                drawRect(color, topLeft = Offset(i * step, size.height - (j + 1) * h),
                    size = Size(width, h * 0.76f))
            }
            if (showPeaks) {
                val p = peaks.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                drawRect(Color(0xFFD4F7C2), topLeft = Offset(i * step, size.height * (1f - p)),
                    size = Size(width, 2.dp.toPx()))
            }
        }
    }
}
