package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.PlaybackPrefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BITRATES = listOf(0, 128, 192, 256, 320)
private val BITRATE_LABELS: List<String> get() = listOf(appString(R.string.text_lossless_f3b36f), "128", "192", "256", "320")

@Composable
fun PlaybackSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenLoudness: () -> Unit,
    onOpenEq: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val isLocal = container.isLocal
    val prefs by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val dataSaver by store.dataSaver.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_playback_quality_144407), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            // streaming quality only applies to server backends
            if (!isLocal) {
                item { SettingsSectionTitle(appString(R.string.text_streaming_quality_7a2799)) }
                item {
                    val sel = BITRATES.indexOf(prefs.streamWifi).coerceAtLeast(0)
                    SegmentedRow(appString(R.string.text_on_wi_fi_bb6180), BITRATE_LABELS, sel) { i -> scope.launch { store.setStreamWifi(BITRATES[i]) } }
                }
                item {
                    val sel = BITRATES.indexOf(prefs.streamCellular).coerceAtLeast(0)
                    SegmentedRow(appString(R.string.text_on_cellular_188845), BITRATE_LABELS, sel) { i -> scope.launch { store.setStreamCellular(BITRATES[i]) } }
                }
                item {
                    SettingsGroup {
                        SettingsSwitchRow(Icons.Filled.DataSaverOn, appString(R.string.text_data_saver_07147b), appString(R.string.text_cap_streaming_to_96_kbps_on_mobile_data_4a7eaa), dataSaver) { v -> scope.launch { store.setDataSaver(v) } }
                    }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_playback_c8e308)) }
            item {
                SettingsSliderRow(
                    appString(R.string.text_crossfade_00acfc),
                    if (prefs.crossfadeSec == 0) appString(R.string.text_off_e3de5a) else appString(R.string.text_s_3eb314, (prefs.crossfadeSec)),
                    prefs.crossfadeSec.toFloat(), 0f..30f, steps = 29,
                ) { v -> scope.launch { store.setCrossfade(v.roundToInt()) } }
                if (prefs.crossfadeSec > 0) {
                    val curves = listOf("SMOOTH", "LINEAR", "POWER")
                    SegmentedRow(appString(R.string.text_fade_curve_7089cd), listOf(appString(R.string.text_smooth_7f93ca), appString(R.string.text_linear_af502f), appString(R.string.text_equal_power_70e301)), curves.indexOf(prefs.crossfadeCurve).coerceAtLeast(0)) {
                        index -> scope.launch { store.setCrossfadeCurve(curves[index]) }
                    }
                    SettingsSwitchRow(title = appString(R.string.text_overlap_headroom_c1a0a1), subtitle = appString(R.string.text_balance_loud_tracks_during_overlap_to_prevent_volume_peaks_f14f26), checked = prefs.crossfadeHeadroom) {
                        value -> scope.launch { store.setCrossfadeHeadroom(value) }
                    }
                    Text(if (prefs.bitPerfectUsb) appString(R.string.text_exclusive_usb_blends_compatible_local_flac_files_other_formats_ke_f8da1d)
                        else appString(R.string.text_the_next_track_prepares_in_advance_fades_pause_with_playback_and_ed5667),
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Audiotrack, appString(R.string.text_gapless_playback_439221), appString(R.string.text_play_tracks_back_to_back_with_no_gap_e1d3d9), prefs.gapless) { v -> scope.launch { store.setGapless(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.GraphicEq, appString(R.string.text_skip_silences_015ea3), appString(R.string.text_cut_silent_sections_within_tracks_4306c9), prefs.skipSilence) { v -> scope.launch { store.setSkipSilence(v) } }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_default_speed_c5ae71)) }
            item {
                SettingsSliderRow(appString(R.string.text_playback_speed_6ff42b), "${"%.2f".format(prefs.defaultSpeed)}x", prefs.defaultSpeed, 0.5f..2.0f, steps = 5) { v ->
                    scope.launch { store.setDefaultSpeed(v) }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_related_settings_661f04)) }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Devices, SettingsDestinations.output, onClick = onOpenOutput)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.VolumeUp, SettingsDestinations.loudness, onClick = onOpenLoudness)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.equalizer, appString(R.string.text_mono_audio_channel_processing_and_tone_6a5bfe), onClick = onOpenEq)
                }
            }
        }
    }
}
