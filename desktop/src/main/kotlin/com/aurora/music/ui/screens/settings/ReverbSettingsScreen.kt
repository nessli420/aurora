package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.ReverbPrefs
import com.aurora.music.data.ReverbPreset
import com.aurora.music.desktop.ui.LocalDesktopContainer
import java.text.NumberFormat
import kotlin.math.exp
import kotlin.math.ln

internal fun reverbPresetName(id: Int): String = appString(when (id) {
    ReverbPreset.ROOM -> R.string.reverb_preset_room
    ReverbPreset.HALL -> R.string.reverb_preset_hall
    ReverbPreset.PLATE -> R.string.reverb_preset_plate
    ReverbPreset.CATHEDRAL -> R.string.reverb_preset_cathedral
    ReverbPreset.AMBIENCE -> R.string.reverb_preset_ambience
    ReverbPreset.LUSH -> R.string.reverb_preset_lush
    else -> R.string.reverb_custom
})

internal fun reverbSummary(prefs: ReverbPrefs): String = when {
    !prefs.enabled -> appString(R.string.text_off_e3de5a)
    prefs.concertPrefs.enabled -> "${concertVenueName(prefs.concertPrefs.venue)} · ${concertSoundName(prefs.concertPrefs.sound)} · ${NumberFormat.getPercentInstance().format(prefs.concertPrefs.amount)}"
    else -> "${reverbPresetName(prefs.preset)} · ${NumberFormat.getPercentInstance().format(prefs.mix)}"
}

@Composable
internal fun ReverbPresetChips(selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) =
    ChoiceChips(ReverbPreset.ALL.indices.map(::reverbPresetName), selected, modifier, onPick)

@Composable
fun ReverbQuickControls(onOpenSettings: () -> Unit) {
    val store = LocalDesktopContainer.current.settingsStore
    val stored by store.reverbPrefs.collectAsStateWithLifecycle(initialValue = ReverbPrefs())
    // the switch goes through the draft too, or the draft would show the old state for up to a second
    val live = rememberLiveValue(stored) { store.setReverb(it) }
    val prefs = live.local ?: stored
    val accent = MaterialTheme.colorScheme.primary
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
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = accent),
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
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
            )
            if (concert.enabled) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ConcertSceneChips(concert) { save(prefs.copy(concert = it)) }
                ConcertStyleChips(concert) { save(prefs.copy(concert = it)) }
                ConcertPositionPicker(concert) { save(prefs.copy(concert = it)) }
                ConcertShowControls(concert) { save(prefs.copy(concert = it)) }
            } else ReverbPresetChips(prefs.preset) { id -> save(prefs.withPreset(id)) }
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 4.dp).pointerHoverIcon(PointerIcon.Hand)) {
            Text(appString(R.string.reverb_more))
        }
    }
}

@Composable
fun ReverbSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = LocalDesktopContainer.current.settingsStore
    val stored by store.reverbPrefs.collectAsStateWithLifecycle(initialValue = ReverbPrefs())
    // every control edits one draft so drags reach datastore at most every LIVE_WRITE_MS and build on each other
    val live = rememberLiveValue(stored) { store.setReverb(it) }
    val prefs = live.local ?: stored
    val percent = remember { NumberFormat.getPercentInstance() }
    fun save(next: ReverbPrefs) = live.set(next)
    fun shape(edit: (ReverbPrefs) -> ReverbPrefs) = save(edit(prefs).copy(preset = -1))
    val concert = prefs.concertPrefs
    val impulses by store.impulseLibrary.collectAsStateWithLifecycle(initialValue = emptyList())
    val rooms = impulses.map { it.id to it.name }
    fun seconds(v: Float) = appString(R.string.reverb_value_seconds, "%.1f".format(v))
    fun millis(v: Float) = appString(R.string.reverb_value_ms, "%.0f".format(v))
    fun hertz(v: Float) = if (v < 1000f) appString(R.string.reverb_value_hz, "%.0f".format(v))
        else appString(R.string.reverb_value_khz, "%.1f".format(v / 1000f))

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.reverb.label, onBack)
        SettingsList(contentPadding) {
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Waves, appString(R.string.reverb_title), appString(R.string.reverb_summary), prefs.enabled) { on ->
                        save(prefs.copy(enabled = on))
                    }
                    SettingsRowDivider()
                    ReverbSlider(appString(R.string.reverb_amount), if (concert.enabled) concert.amount else prefs.mix, 0f..1f, label = percent::format) { v ->
                        save(if (concert.enabled) prefs.copy(concert = concert.copy(amount = v)) else prefs.copy(mix = v))
                    }
                }
            }
            item { SettingsSectionTitle(appString(R.string.reverb_section_style)) }
            item {
                SettingsGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReverbModeChips(concert.enabled) { on -> save(prefs.copy(concert = concert.copy(enabled = on))) }
                        if (!concert.enabled) ReverbPresetChips(prefs.preset) { id -> save(prefs.withPreset(id)) }
                    }
                }
            }
            if (concert.enabled) {
                item { SettingsSectionTitle(appString(R.string.concert_section_scenes)) }
                item { SettingsGroup { ConcertSceneChips(concert, Modifier.padding(16.dp), label = false) { save(prefs.copy(concert = it)) } } }
                item { SettingsSectionTitle(appString(R.string.concert_section_venue)) }
                item {
                    SettingsGroup {
                        ConcertStyleChips(concert, Modifier.padding(16.dp), labelVenue = false) { save(prefs.copy(concert = it)) }
                        ConcertRoomSliders(concert, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) { save(prefs.copy(concert = it)) }
                    }
                }
                item { SettingsSectionTitle(appString(R.string.concert_section_position)) }
                item { SettingsGroup { ConcertPositionPicker(concert, Modifier.padding(16.dp), hint = true) { save(prefs.copy(concert = it)) } } }
                item { SettingsSectionTitle(appString(R.string.concert_section_show)) }
                item { SettingsGroup { ConcertShowControls(concert, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), full = true) { save(prefs.copy(concert = it)) } } }
                item { SettingsSectionTitle(appString(R.string.concert_section_air)) }
                item { SettingsGroup { ConcertAirControls(concert, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { save(prefs.copy(concert = it)) } } }
                item { SettingsSectionTitle(appString(R.string.concert_room)) }
                item { SettingsGroup { ConcertMeasuredRoom(concert, rooms, Modifier.padding(16.dp)) { save(prefs.copy(concert = it)) } } }
            } else {
                item { SettingsSectionTitle(appString(R.string.reverb_section_space)) }
                item {
                    SettingsGroup {
                        ReverbSlider(appString(R.string.reverb_decay), prefs.decaySec, 0.2f..12f, log = true, label = ::seconds) { v -> shape { it.copy(decaySec = v) } }
                        ReverbSlider(appString(R.string.reverb_size), prefs.size, 0f..1f, label = percent::format) { v -> shape { it.copy(size = v) } }
                        ReverbSlider(appString(R.string.reverb_predelay), prefs.predelayMs, 0f..200f, label = ::millis) { v -> shape { it.copy(predelayMs = v) } }
                        ReverbSlider(appString(R.string.reverb_early), prefs.early, 0f..1f, label = percent::format) { v -> shape { it.copy(early = v) } }
                    }
                }
                item { SettingsSectionTitle(appString(R.string.reverb_section_tone)) }
                item {
                    SettingsGroup {
                        ReverbSlider(appString(R.string.reverb_damping), prefs.damping, 0f..1f, label = percent::format) { v -> shape { it.copy(damping = v) } }
                        ReverbSlider(appString(R.string.reverb_low_cut), prefs.lowCutHz, 20f..1000f, log = true, label = ::hertz) { v -> shape { it.copy(lowCutHz = v) } }
                        ReverbSlider(appString(R.string.reverb_high_cut), prefs.highCutHz, 1500f..20000f, log = true, label = ::hertz) { v -> shape { it.copy(highCutHz = v) } }
                    }
                }
                item { SettingsSectionTitle(appString(R.string.reverb_section_character)) }
                item {
                    SettingsGroup {
                        ReverbSlider(appString(R.string.reverb_width), prefs.width, 0f..1.5f, label = percent::format) { v -> shape { it.copy(width = v) } }
                        ReverbSlider(appString(R.string.reverb_modulation), prefs.modulation, 0f..1f, label = percent::format) { v -> shape { it.copy(modulation = v) } }
                    }
                }
            }
            item {
                Text(appString(R.string.reverb_note), Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// drafts the thumb locally so datastore round-trips never make it jump
@Composable
private fun ReverbSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, log: Boolean = false,
    label: (Float) -> String, onChange: (Float) -> Unit) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val low = range.start; val high = range.endInclusive
    fun position(v: Float) = if (log) (ln(v / low) / ln(high / low)) else (v - low) / (high - low)
    fun valueAt(p: Float) = if (log) low * exp(p * ln(high / low)) else low + p * (high - low)
    val shown = draft ?: value
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(label(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = position(shown.coerceIn(low, high)),
            onValueChange = { p -> valueAt(p).also { draft = it; onChange(it) } },
            onValueChangeFinished = { draft = null },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}
