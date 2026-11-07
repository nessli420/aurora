package com.aurora.music.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.ReverbPrefs
import com.aurora.music.data.ReverbPreset
import com.aurora.music.localization.appString
import com.aurora.music.ui.screens.settings.rememberLiveValue
import java.text.NumberFormat

internal fun reverbPresetName(id: Int): String = appString(when (id) {
    ReverbPreset.ROOM -> R.string.reverb_preset_room
    ReverbPreset.HALL -> R.string.reverb_preset_hall
    ReverbPreset.PLATE -> R.string.reverb_preset_plate
    ReverbPreset.CATHEDRAL -> R.string.reverb_preset_cathedral
    ReverbPreset.AMBIENCE -> R.string.reverb_preset_ambience
    ReverbPreset.LUSH -> R.string.reverb_preset_lush
    else -> R.string.reverb_custom
})

@Composable
internal fun ReverbPresetChips(selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) =
    ChoiceChips(ReverbPreset.ALL.indices.map(::reverbPresetName), selected, modifier, onPick)

@Composable
internal fun ReverbQuickControls(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AuroraApplication).container.settingsStore }
    val stored by store.reverbPrefs.collectAsStateWithLifecycle(initialValue = ReverbPrefs())
    // the switch goes through the draft too, or the draft would show the old state for up to a second
    val live = rememberLiveValue(stored) { store.setReverb(it) }
    val prefs = live.local ?: stored
    val accent = MaterialTheme.colorScheme.secondary
    val concert = prefs.concertPrefs
    fun save(next: ReverbPrefs) = live.set(next)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Waves, null, tint = accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(appString(R.string.reverb_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (!prefs.enabled) appString(R.string.reverb_summary)
                    else if (concert.enabled) "${appString(R.string.reverb_mode_concert)} · ${concertVenueName(concert.venue)} · ${concertSoundName(concert.sound)}"
                    else reverbPresetName(prefs.preset),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = prefs.enabled,
                onCheckedChange = { on -> save(prefs.copy(enabled = on)) },
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onSecondary, checkedTrackColor = accent),
            )
        }
        if (prefs.enabled) {
            val mix = if (concert.enabled) concert.amount else prefs.mix
            ReverbModeChips(concert.enabled, Modifier.padding(top = 16.dp)) { on -> save(prefs.copy(concert = concert.copy(enabled = on))) }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.reverb_amount), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(NumberFormat.getPercentInstance().format(mix), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = mix,
                onValueChange = { v -> save(if (concert.enabled) prefs.copy(concert = concert.copy(amount = v)) else prefs.copy(mix = v)) },
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
            )
            if (concert.enabled) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ConcertSceneChips(concert) { save(prefs.copy(concert = it)) }
                ConcertStyleChips(concert) { save(prefs.copy(concert = it)) }
                ConcertPositionPicker(concert) { save(prefs.copy(concert = it)) }
                ConcertShowControls(concert) { save(prefs.copy(concert = it)) }
            } else ReverbPresetChips(prefs.preset) { id -> save(prefs.withPreset(id)) }
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 4.dp)) { Text(appString(R.string.reverb_more)) }
    }
}
