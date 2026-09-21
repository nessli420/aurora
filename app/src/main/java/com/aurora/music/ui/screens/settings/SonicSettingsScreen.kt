package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import kotlinx.coroutines.launch

@Composable
fun SonicSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = remember { (ctx.applicationContext as AuroraApplication).container }
    val progress by app.sonicEngine.progress.collectAsStateWithLifecycle()
    val analyzed by app.sonicEngine.analyzedCount.collectAsStateWithLifecycle()
    val auto by app.settingsStore.sonicAutoAnalyze.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.analysis.label, onBack)
        Column(Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.text_tracks_analyzed_3d39e3, (analyzed)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(appString(R.string.text_powers_sonic_radio_auto_dj_and_mix_transitions_4e309b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SettingsSectionTitle(appString(R.string.text_analyze_0e524d))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = !progress.running) { app.sonicEngine.scan() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (progress.running) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (progress.running) appString(R.string.text_analyzing_library_c854a6) else appString(R.string.text_analyze_library_afcfa4),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    )
                    Text(
                        when {
                            progress.running -> "${progress.done} / ${progress.total} • ${progress.current}"
                            analyzed > 0 -> appString(R.string.text_analyzed_tap_to_scan_new_tracks_5933cd, (analyzed))
                            else -> appString(R.string.text_analyze_the_whole_library_including_streamed_tracks_68593c)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress.running) {
                    Text(
                        appString(R.string.text_cancel_77dfd2), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { app.sonicEngine.cancel() }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            if (progress.failed > 0 || progress.error != null) Text(
                progress.error ?: appString(R.string.text_tracks_unavailable_tap_analyze_library_to_retry_dc5b2d, (progress.failed)),
                modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
            SettingsSectionTitle(appString(R.string.text_automation_a15fde))
            SettingsGroup {
                SettingsSwitchRow(
                    Icons.Filled.AutoAwesome, appString(R.string.text_auto_analyze_on_launch_854335),
                    appString(R.string.text_analyze_new_tracks_across_your_library_including_streams_841790), auto,
                ) { v -> scope.launch { app.settingsStore.setSonicAutoAnalyze(v) } }
            }

            Text(
                appString(R.string.text_sonic_radio_compares_the_actual_sound_of_your_tracks_timbre_harmo_001cdd) +
                    appString(R.string.text_on_your_device_streams_are_read_from_your_server_without_adding_d_4dfc77) +
                    appString(R.string.text_analysis_uses_network_data_and_takes_time_on_large_libraries_comp_a41cc1) +
                    appString(R.string.text_you_can_cancel_and_resume_unavailable_tracks_are_retried_on_the_n_af1eea),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
