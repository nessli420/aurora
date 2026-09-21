package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioPrefs
import kotlinx.coroutines.launch

@Composable
fun LoudnessSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenSignalPath: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val prefs by store.audioPrefs.collectAsStateWithLifecycle(initialValue = AudioPrefs())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.loudness.label, onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle("ReplayGain") }
            item {
                SegmentedRow(appString(R.string.text_volume_leveling_a9df98), listOf(appString(R.string.text_off_e3de5a), appString(R.string.text_track_b1c5a7), appString(R.string.text_album_dfb4c9)), prefs.replayGain.coerceIn(0, 2)) { mode ->
                    scope.launch { store.setReplayGain(mode) }
                }
            }
            item {
                Text(
                    when (prefs.replayGain) {
                        1 -> appString(R.string.text_track_mode_uses_each_track_s_gain_tag_to_reduce_volume_difference_c08f12)
                        2 -> appString(R.string.text_album_mode_uses_album_gain_tags_to_preserve_relative_levels_withi_99c44e)
                        else -> appString(R.string.text_off_leaves_replaygain_disabled_56fb0f)
                    } + appString(R.string.text_aurora_currently_applies_attenuation_only_missing_or_positive_gai_20c829),
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SettingsSectionTitle(appString(R.string.text_related_settings_661f04)) }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.equalizer,
                        appString(R.string.text_preamp_limiter_and_other_effect_gain_controls_32ddcd), onClick = onOpenEq)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath,
                        appString(R.string.text_check_whether_volume_leveling_is_active_or_bypassed_05e612), onClick = onOpenSignalPath)
                }
            }
        }
    }
}
