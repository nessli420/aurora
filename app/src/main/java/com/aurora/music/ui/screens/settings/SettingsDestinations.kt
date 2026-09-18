package com.aurora.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.aurora.music.navigation.Routes

/** The current, usable settings destinations. Also suitable for a future settings search. */
data class SettingsDestination(val route: String, val label: String, val description: String)

object SettingsDestinations {
    val accounts = SettingsDestination(Routes.SETTINGS_ACCOUNTS, "Servers & accounts", "Switch between saved logins")
    val sources = SettingsDestination(Routes.SETTINGS_SOURCES, "Library & sources", "Source priority and unified library")
    val storage = SettingsDestination(Routes.SETTINGS_STORAGE, "Downloads & storage", "Download quality and offline files")
    val analysis = SettingsDestination(Routes.SETTINGS_SONIC, "Library analysis & discovery", "Library scans, Sonic radio and Auto DJ")
    val playback = SettingsDestination(Routes.SETTINGS_PLAYBACK, "Playback & quality", "Streaming quality, crossfade, gapless and speed")
    val output = SettingsDestination(Routes.SETTINGS_OUTPUT, "Audio output", "Output device, hi-res and direct USB")
    val equalizer = SettingsDestination(Routes.SETTINGS_EQ, "Equalizer & effects", "EQ, device presets, convolution and channels")
    val processingPresets = SettingsDestination(Routes.SETTINGS_PROCESSING_PRESETS, "Saved processing presets", "Save and restore your audio settings")
    val processingRack = SettingsDestination(Routes.SETTINGS_PROCESSING_RACK, "Processing rack", "Arrange and tune your audio processing stages")
    val tuning = SettingsDestination(Routes.SETTINGS_TUNING, "Measurement tuning", "Measurements, targets and saved correction projects")
    val impulses = SettingsDestination(Routes.SETTINGS_IMPULSES, "Impulse responses", "Saved WAVs, trimming and normalization")
    val loudness = SettingsDestination(Routes.SETTINGS_LOUDNESS, "Volume & loudness", "ReplayGain and volume leveling")
    val signalPath = SettingsDestination(Routes.SIGNAL_PATH, "Signal Path", "Inspect the current playback path")
    val alarm = SettingsDestination(Routes.SETTINGS_ALARM, "Alarm", "Wake to your liked music")
    val appearance = SettingsDestination(Routes.SETTINGS_APPEARANCE, "Appearance", "Theme, accent and layout")
    val visualizer = SettingsDestination(Routes.SETTINGS_VISUALIZER, "Visualizer", "Spectrum, waveform, radial and particles")
    val gestures = SettingsDestination(Routes.SETTINGS_GESTURES, "Gestures & behaviour", "Swipe, haptics and private session")
    val integrations = SettingsDestination(Routes.SETTINGS_INTEGRATIONS, "Integrations", "Last.fm, ListenBrainz, Discord and lyrics")
    val permissions = SettingsDestination(Routes.SETTINGS_PERMISSIONS, "Permissions", "Notifications, background, alarms and DAC")
    val backup = SettingsDestination(Routes.SETTINGS_BACKUP, "Backup & restore", "Export or import settings and playlists")
    val about = SettingsDestination(Routes.SETTINGS_ABOUT, "About Aurora", "Version and app information")

    val all = listOf(accounts, sources, storage, analysis, playback, output, equalizer, processingRack, tuning, impulses, processingPresets, loudness,
        signalPath, alarm, appearance, visualizer, gestures, integrations, permissions, backup, about)
}

@Composable
internal fun SettingsDestinationRow(
    icon: ImageVector,
    destination: SettingsDestination,
    summary: String = destination.description,
    onClick: () -> Unit,
) = SettingsNavRow(icon, destination.label, subtitle = summary, onClick = onClick)
