package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ProcessingRack

@Composable
fun AdvancedAudioSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (SettingsDestination) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val rack by container.settingsStore.processingRack.collectAsStateWithLifecycle(initialValue = ProcessingRack())
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(SettingsDestinations.advancedAudio.label, onBack)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item { SettingsSectionTitle("Processing") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.processingRack,
                        if (rack.enabled) "${rack.name} · ${rack.nodes.size} stages" else "Arrange your effects") {
                        onOpen(SettingsDestinations.processingRack)
                    }
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Backup, SettingsDestinations.processingPresets) {
                        onOpen(SettingsDestinations.processingPresets)
                    }
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.GraphicEq, SettingsDestinations.impulses) {
                        onOpen(SettingsDestinations.impulses)
                    }
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.presetRules) {
                        onOpen(SettingsDestinations.presetRules)
                    }
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Extension, SettingsDestinations.extensions) {
                        onOpen(SettingsDestinations.extensions)
                    }
                }
            }
            item { SettingsSectionTitle("Tuning & listening") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.AutoMirrored.Filled.ShowChart, SettingsDestinations.tuning) {
                        onOpen(SettingsDestinations.tuning)
                    }
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.comparison) {
                        onOpen(SettingsDestinations.comparison)
                    }
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.AutoMirrored.Filled.VolumeUp, SettingsDestinations.listening) {
                        onOpen(SettingsDestinations.listening)
                    }
                }
            }
        }
    }
}
