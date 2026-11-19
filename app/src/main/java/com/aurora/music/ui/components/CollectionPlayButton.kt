package com.aurora.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.ui.components.glass.GlassButton
import com.aurora.music.ui.theme.GlassColors
import com.aurora.music.ui.theme.LocalGlassAmbient
import com.aurora.music.ui.theme.glassIsDark
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.pressClickable

// the queue as collection headers see it: the page that made it, whether it plays, and pause or resume
@Immutable
data class QueuePlayback(val origin: String? = null, val playing: Boolean = false, val toggle: () -> Unit = {})

val LocalQueuePlayback = compositionLocalOf { QueuePlayback() }

// a collection header's play button; on the page whose queue is playing it pauses and resumes instead of starting over
@Composable
fun CollectionPlayButton(
    origin: String?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    label: String = appString(R.string.text_play_5d12bd),
    iconOnly: Boolean = false,
) {
    val queue = LocalQueuePlayback.current
    val here = origin != null && origin == queue.origin
    val playing = here && queue.playing
    val click = if (here) queue.toggle else onPlay
    val text = if (playing) appString(R.string.text_pause_781961) else label
    val ink = if (accent.luminance() > 0.6f) Color.Black else Color.White
    when {
        iconOnly -> Box(
            modifier.size(44.dp).paperClip(CircleShape).pressClickable(enabled, role = Role.Button, onClick = click)
                .semantics { contentDescription = text }.padding(8.dp),
        ) { PlayPauseIcon(playing, accent, Modifier.fillMaxSize()) }
        isGlassTheme() -> GlassButton(click, LocalGlassAmbient.current, modifier.alpha(if (enabled) 1f else .5f),
            surface = GlassColors.accent(accent, glassIsDark()), enabled = enabled) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayPauseIcon(playing, ink, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
            }
        }
        else -> Row(
            modifier
                .paperClip(RoundedCornerShape(50))
                .pressClickable(enabled, role = Role.Button, onClick = click)
                .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.78f))))
                .padding(horizontal = 24.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayPauseIcon(playing, ink, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = ink)
        }
    }
}
