package com.aurora.music.ui.screens.settings

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
                SegmentedRow("Volume leveling", listOf("Off", "Track", "Album"), prefs.replayGain.coerceIn(0, 2)) { mode ->
                    scope.launch { store.setReplayGain(mode) }
                }
            }
            item {
                Text(
                    when (prefs.replayGain) {
                        1 -> "Track mode uses each track's gain tag to reduce volume differences between songs."
                        2 -> "Album mode uses album gain tags to preserve relative levels within an album."
                        else -> "Off leaves ReplayGain disabled."
                    } + " Aurora currently applies attenuation only. Missing or positive gain values leave volume unchanged. Availability depends on the active playback path.",
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SettingsSectionTitle("Related settings") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.equalizer,
                        "Preamp, limiter and other effect gain controls", onClick = onOpenEq)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath,
                        "Check whether volume leveling is active or bypassed", onClick = onOpenSignalPath)
                }
            }
        }
    }
}
