package com.aurora.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.aurora.music.ui.theme.isPaperTheme
import kotlin.random.Random

// the app's logo as ic_aurora_logo draws it: a 64 box, stroke 9, round ends
private const val AURORA_MARK = "M9,53 L28.4,11.3 Q32,3.8 35.6,11.3 L55,53 M16,39.5 C27,30 37,49 48,39.5"

@Composable
fun MixCover(slot: String, accent: Color, modifier: Modifier = Modifier, pictured: Boolean = false) {
    val paper = isPaperTheme() && !pictured
    val number = slot.substringAfter("daily-", "")
    val icon = when (slot) {
        "time-morning" -> Icons.Filled.WbTwilight
        "time-afternoon" -> Icons.Filled.WbSunny
        "time-evening" -> Icons.Filled.Nightlight
        "time-night" -> Icons.Filled.Bedtime
        "recommended" -> Icons.Filled.AutoAwesome
        "discover" -> Icons.Filled.Explore
        else -> null
    }
    val bars = remember(slot) { Random(slot.hashCode()).let { r -> FloatArray(22) { 0.25f + r.nextFloat() * 0.75f } } }
    val measurer = rememberTextMeasurer()
    val logo = remember { PathParser().parsePathString(AURORA_MARK).toPath() }
    val station = rememberVectorPainter(Icons.Filled.Sensors)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // over a picture the accent only tints, and the dark end keeps the mark readable
            if (pictured) drawRect(Brush.linearGradient(listOf(accent.copy(alpha = 0.66f), Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.7f)), Offset.Zero, Offset(size.width, size.height)))
            else if (paper) drawRect(lerp(accent, Color.White, 0.55f))
            else drawRect(Brush.linearGradient(listOf(lerp(accent, Color.White, 0.14f), lerp(accent, Color.Black, 0.62f)), Offset.Zero, Offset(size.width, size.height)))
            val ink = if (paper) Color.Black else Color.White
            val step = size.width / bars.size
            bars.forEachIndexed { i, level ->
                val h = size.height * 0.34f * level
                drawRoundRect(ink.copy(alpha = if (pictured) 0.26f else 0.16f), Offset(i * step + step * 0.2f, size.height - h), Size(step * 0.6f, h), CornerRadius(if (paper) 0f else step * 0.3f))
            }
            // the logo takes the corner where the tint is strongest; a phone's page header has its back button there, so it sits below
            val mark = size.minDimension * 0.13f
            val edge = size.minDimension * 0.07f
            val header = size.width > 340.dp.toPx()
            val top = if (header) 104.dp.toPx() else edge
            withTransform({ translate(edge, top); scale(mark / 64f, mark / 64f, Offset.Zero) }) {
                drawPath(logo, lerp(accent, Color.Black, 0.48f), style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (number.isNotEmpty()) {
                // under the logo on a tile, beside it on a header, where below would run into the title
                val text = measurer.measure(number, TextStyle(color = ink, fontWeight = FontWeight.Black, fontSize = (size.minDimension * if (header) 0.5f else 0.46f).toSp()))
                drawText(text, topLeft = if (header) Offset(edge * 2 + mark, size.height * 0.14f) else Offset(size.width * 0.1f, size.height * 0.19f))
            }
            if (slot.startsWith("artist-")) translate(edge, top + mark + edge * 0.7f) {
                with(station) { draw(Size(size.minDimension * 0.2f, size.minDimension * 0.2f), colorFilter = ColorFilter.tint(ink)) }
            }
        }
        if (icon != null) Icon(icon, null, tint = if (paper) Color.Black else Color.White, modifier = Modifier.fillMaxSize(0.42f))
    }
}
