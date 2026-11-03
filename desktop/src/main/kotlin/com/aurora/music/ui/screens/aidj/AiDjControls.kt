package com.aurora.music.ui.screens.aidj

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.ai.DjMode
import com.aurora.music.ai.DjSegment
import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.themedCorner

fun aiDjLabel(state: DjMode.State): String = state.intent?.let { it.title.ifBlank { state.prompt } } ?: when (state.segment) {
    DjSegment.REPEAT -> appString(R.string.ai_dj_segment_repeat)
    DjSegment.HOUR -> appString(R.string.ai_dj_segment_hour)
    DjSegment.PAST -> appString(R.string.ai_dj_segment_past)
    DjSegment.NEW -> appString(R.string.ai_dj_segment_new)
    DjSegment.LIKED -> appString(R.string.ai_dj_segment_liked)
    DjSegment.ARTIST -> appString(R.string.ai_dj_segment_artist, state.artist.orEmpty())
    DjSegment.MIX -> appString(R.string.ai_dj_segment_mix)
    null -> appString(R.string.ai_dj_busy)
}

@Composable
fun AiDjHomeCard(state: DjMode.State, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(themedCorner(20.dp)))
            .background(Brush.horizontalGradient(listOf(colors.primary.copy(alpha = 0.28f), colors.tertiary.copy(alpha = 0.14f))))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(themedCorner(14.dp))).background(Brush.linearGradient(listOf(colors.primary, colors.tertiary))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.AutoAwesome, null, tint = colors.onPrimary, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(appString(R.string.ai_dj_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (state.active) aiDjLabel(state) else appString(R.string.ai_dj_home_detail), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Icon(if (state.active) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow, null, tint = colors.primary, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun AiDjQueueBanner(state: DjMode.State, onSteer: (String) -> Unit, onSwitch: () -> Unit, onStop: () -> Unit) {
    var steer by remember { mutableStateOf("") }
    val send = { if (steer.isNotBlank() && !state.busy) { onSteer(steer); steer = "" } }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.ai_dj_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(aiDjLabel(state), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onSwitch, enabled = !state.busy) { Text(appString(R.string.ai_dj_switch), maxLines = 1) }
                TextButton(onClick = onStop) { Text(appString(R.string.ai_dj_stop), maxLines = 1) }
            }
            OutlinedTextField(
                value = steer,
                onValueChange = { steer = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(appString(R.string.ai_dj_steer_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { TextButton(onClick = send, enabled = steer.isNotBlank() && !state.busy) { Text(appString(R.string.ai_dj_steer)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            when {
                state.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.ai_dj_busy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.failed -> Text(appString(R.string.ai_dj_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
