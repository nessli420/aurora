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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.aurora.music.ui.theme.isPaperTheme
import kotlin.random.Random

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
            if (number.isNotEmpty()) {
                val text = measurer.measure(number, TextStyle(color = ink, fontWeight = FontWeight.Black, fontSize = (size.minDimension * 0.5f).toSp()))
                drawText(text, topLeft = Offset(size.width * 0.1f, size.height * 0.14f))
            }
        }
        if (icon != null) Icon(icon, null, tint = if (paper) Color.Black else Color.White, modifier = Modifier.fillMaxSize(0.42f))
        // a station is its artist's face, so its mark keeps to the side, below where a page puts its back button
        if (slot.startsWith("artist-")) Box(Modifier.align(BiasAlignment(-1f, -0.42f)).fillMaxSize(0.32f), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Sensors, null, tint = if (paper) Color.Black else Color.White, modifier = Modifier.fillMaxSize(0.62f))
        }
    }
}
