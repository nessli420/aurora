package com.aurora.music.ui.screens.player

import androidx.compose.foundation.verticalScroll
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.rememberScrollState
import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.ui.screens.settings.SegmentedChoice

// the tab you used last, for as long as the app runs
private var lastTab = 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedPitchSheet(
    speed: Float,
    onSpeed: (Float) -> Unit,
    onReset: () -> Unit,
    onOpenReverbSettings: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(lastTab) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(appString(R.string.text_playback_controls_5ee1ab), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            SegmentedChoice(listOf(appString(R.string.text_speed_2d2cb0), appString(R.string.reverb_title)), tab) { tab = it; lastTab = it }
            Spacer(Modifier.height(20.dp))

            if (tab == 1) com.aurora.music.ui.screens.settings.ReverbQuickControls(onOpenReverbSettings)
            else Column {
                ControlBlock(
                    icon = { Icon(Icons.Filled.Speed, null, tint = MaterialTheme.colorScheme.primary) },
                    title = appString(R.string.text_speed_2d2cb0),
                    value = "${"%.2f".format(speed)}x",
                ) {
                    Slider(
                        value = speed,
                        onValueChange = onSpeed,
                        valueRange = 0.5f..2.0f,
                        steps = 29,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                    )
                    QuickPicks(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f), speed) { onSpeed(it) }
                }

                TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End).padding(top = 8.dp).pointerHoverIcon(PointerIcon.Hand)) {
                    Text(appString(R.string.text_reset_to_default_39c90e))
                }
            }
        }
    }
}

@Composable
private fun ControlBlock(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    content()
}

@Composable
private fun QuickPicks(options: List<Float>, selected: Float, onPick: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val active = kotlin.math.abs(opt - selected) < 0.001f
            Box(
                Modifier
                    .weight(1f)
                    .paperClip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onPick(opt) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    formatMultiplier(opt),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun formatMultiplier(v: Float): String {
    val s = if (v % 1f == 0f) v.toInt().toString() else v.toString().trimEnd('0').trimEnd('.')
    return "${s}x"
}
