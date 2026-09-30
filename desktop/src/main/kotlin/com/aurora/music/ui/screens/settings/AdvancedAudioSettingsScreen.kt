package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.ProcessingRack
import com.aurora.music.desktop.ui.LocalDesktopContainer

@Composable
fun AdvancedAudioSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (SettingsDestination) -> Unit,
    available: (SettingsDestination) -> Boolean = { true },
) {
    val rack by LocalDesktopContainer.current.settingsStore.processingRack.collectAsStateWithLifecycle(initialValue = ProcessingRack())
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(SettingsDestinations.advancedAudio.label, onBack)
        SettingsList(contentPadding) {
            item { SettingsSectionTitle(appString(R.string.text_processing_e63451)) }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.processingRack,
                        if (rack.enabled) appString(R.string.text_stages_8e34a7, (rack.name), (rack.nodes.size)) else appString(R.string.text_arrange_your_effects_ded878)) {
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
                    if (available(SettingsDestinations.presetRules)) {
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.presetRules) {
                            onOpen(SettingsDestinations.presetRules)
                        }
                    }
                }
            }
            val tuningRows = listOf(
                Icons.AutoMirrored.Filled.ShowChart to SettingsDestinations.tuning,
                Icons.Filled.Tune to SettingsDestinations.comparison,
                Icons.AutoMirrored.Filled.VolumeUp to SettingsDestinations.listening,
            ).filter { available(it.second) }
            if (tuningRows.isNotEmpty()) {
                item { SettingsSectionTitle(appString(R.string.text_tuning_listening_b4bf76)) }
                item {
                    SettingsGroup {
                        tuningRows.forEachIndexed { index, (icon, destination) ->
                            if (index > 0) SettingsRowDivider()
                            SettingsDestinationRow(icon, destination) { onOpen(destination) }
                        }
                    }
                }
            }
        }
    }
}
