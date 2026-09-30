package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.aurora.music.navigation.Routes

data class SettingsDestination(val route: String, private val labelRes: Int, private val descriptionRes: Int) {
    val label: String get() = appString(labelRes)
    val description: String get() = appString(descriptionRes)
}

object SettingsDestinations {
    val accounts = SettingsDestination(Routes.SETTINGS_ACCOUNTS, R.string.text_servers_accounts_59e105, R.string.text_switch_between_saved_logins_dfa5ef)
    val sources = SettingsDestination(Routes.SETTINGS_SOURCES, R.string.text_library_sources_101221, R.string.text_source_priority_and_unified_library_5d9b2c)
    val storage = SettingsDestination(Routes.SETTINGS_STORAGE, R.string.text_downloads_storage_f7c580, R.string.text_download_quality_and_offline_files_157e78)
    val analysis = SettingsDestination(Routes.SETTINGS_SONIC, R.string.text_library_analysis_discovery_211d17, R.string.text_library_scans_sonic_radio_and_auto_dj_dbb04a)
    val playback = SettingsDestination(Routes.SETTINGS_PLAYBACK, R.string.text_playback_quality_144407, R.string.text_streaming_quality_crossfade_gapless_and_speed_602d35)
    val output = SettingsDestination(Routes.SETTINGS_OUTPUT, R.string.text_audio_output_2b89cc, R.string.text_output_device_hi_res_and_usb_modes_58ec99)
    val equalizer = SettingsDestination(Routes.SETTINGS_EQ, R.string.text_equalizer_effects_e6ad57, R.string.text_eq_device_presets_convolution_and_channels_4e2569)
    val advancedAudio = SettingsDestination(Routes.SETTINGS_ADVANCED_AUDIO, R.string.text_advanced_audio_decbca, R.string.text_processing_tuning_and_extensions_d9a2fc)
    val processingPresets = SettingsDestination(Routes.SETTINGS_PROCESSING_PRESETS, R.string.text_saved_processing_presets_f22d4b, R.string.text_save_and_restore_your_audio_settings_6ec6de)
    val processingRack = SettingsDestination(Routes.SETTINGS_PROCESSING_RACK, R.string.text_processing_rack_f7dff1, R.string.text_arrange_and_tune_your_audio_processing_stages_67b15f)
    val tuning = SettingsDestination(Routes.SETTINGS_TUNING, R.string.text_measurement_tuning_815052, R.string.text_measurements_targets_and_saved_correction_projects_d6f94e)
    val impulses = SettingsDestination(Routes.SETTINGS_IMPULSES, R.string.text_impulse_responses_b2ac6e, R.string.text_saved_wavs_trimming_and_normalization_732c0c)
    val comparison = SettingsDestination(Routes.SETTINGS_COMPARISON, R.string.text_compare_presets_b7d8ea, R.string.text_level_matched_a_b_and_blind_abx_611108)
    val presetRules = SettingsDestination(Routes.SETTINGS_PRESET_RULES, R.string.text_preset_rules_c9d4d1, R.string.text_switch_presets_by_track_and_output_546392)
    val loudness = SettingsDestination(Routes.SETTINGS_LOUDNESS, R.string.text_volume_loudness_0e8ee1, R.string.text_replaygain_and_volume_leveling_39af19)
    val listening = SettingsDestination(Routes.SETTINGS_LISTENING, R.string.text_listening_levels_c1db13, R.string.text_headphone_calibration_and_local_level_history_3c0581)
    val signalPath = SettingsDestination(Routes.SIGNAL_PATH, R.string.text_signal_path_c3e29b, R.string.text_inspect_the_current_playback_path_5a0995)
    val appearance = SettingsDestination(Routes.SETTINGS_APPEARANCE, R.string.text_appearance_41def7, R.string.text_theme_accent_and_layout_cb0657)
    val visualizer = SettingsDestination(Routes.SETTINGS_VISUALIZER, R.string.text_visualizer_7177c7, R.string.text_spectrum_waveform_radial_and_particles_145442)
    val integrations = SettingsDestination(Routes.SETTINGS_INTEGRATIONS, R.string.text_integrations_a7881c, R.string.text_scrobbling_presence_lyrics_and_metadata_687ea0)
    val backup = SettingsDestination(Routes.SETTINGS_BACKUP, R.string.text_backup_restore_a16162, R.string.text_export_or_import_settings_and_playlists_4ce918)
    val about = SettingsDestination(Routes.SETTINGS_ABOUT, R.string.text_about_aurora_b4ed8c, R.string.text_version_and_app_information_9c9a82)

    val all = listOf(accounts, sources, storage, analysis, playback, output, equalizer, advancedAudio, processingRack, tuning, impulses, comparison, presetRules, processingPresets, loudness,
        listening, signalPath, appearance, visualizer, integrations, backup, about)
}

@Composable
internal fun SettingsDestinationRow(
    icon: ImageVector,
    destination: SettingsDestination,
    summary: String = destination.description,
    onClick: () -> Unit,
) = SettingsNavRow(icon, destination.label, subtitle = summary, selected = LocalSelectedSettingsRoute.current == destination.route, onClick = onClick)

val LocalSelectedSettingsRoute = androidx.compose.runtime.compositionLocalOf<String?> { null }
