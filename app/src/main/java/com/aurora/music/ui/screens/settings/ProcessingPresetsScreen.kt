package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingPresetLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipException

private enum class PresetNameMode { SAVE, RENAME, DUPLICATE }
private data class PresetNameDialog(val mode: PresetNameMode, val preset: ProcessingPreset? = null)

@Composable
fun ProcessingPresetsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenSignalPath: () -> Unit,
) {
    val context = LocalContext.current
    val store = (context.applicationContext as AuroraApplication).container.settingsStore
    val library by store.processingPresetLibrary.collectAsStateWithLifecycle<ProcessingPresetLibrary?>(initialValue = null)
    val autoEqAutoSwitch by store.autoEqAutoSwitch.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf(appString(R.string.text_updating_preset_aae445)) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDialog by remember { mutableStateOf<PresetNameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<ProcessingPreset?>(null) }
    var lastAction by remember { mutableStateOf<String?>(null) }
    var actionFailed by remember { mutableStateOf(false) }
    var showOutputBindings by rememberSaveable { mutableStateOf(false) }

    if (showOutputBindings) {
        OutputPresetBindingsScreen(contentPadding) { showOutputBindings = false }
        return
    }

    fun perform(progressMessage: String = appString(R.string.text_updating_preset_aae445), operation: suspend () -> String) {
        if (busy) return
        busy = true
        busyMessage = progressMessage
        scope.launch {
            val message = try {
                operation().also { actionFailed = false }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ZipException) {
                actionFailed = true
                appString(R.string.text_this_file_is_not_a_valid_preset_archive_or_it_is_damaged_choose_a_fc090e)
            } catch (error: IOException) {
                actionFailed = true
                appString(R.string.text_could_not_read_or_write_the_preset_file_check_access_to_the_selec_56fd44)
            } catch (error: SecurityException) {
                actionFailed = true
                appString(R.string.text_access_to_the_selected_file_was_denied_choose_the_file_or_locatio_467687)
            } catch (error: Exception) {
                actionFailed = true
                error.message ?: appString(R.string.text_could_not_update_the_preset_please_try_again_12c9d8)
            } finally {
                busy = false
            }
            lastAction = message
            snackbar.showSnackbar(message)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val presetId = pendingExportId
        pendingExportId = null
        if (uri != null && presetId != null) {
            perform(appString(R.string.text_exporting_preset_f23e43)) {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error(appString(R.string.text_could_not_open_the_selected_location_for_saving_d2bee1))
                    output.use { store.exportProcessingPreset(presetId, it).getOrThrow() }
                }
                appString(R.string.text_preset_exported_with_its_saved_settings_and_any_impulse_response_58728a)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            perform(appString(R.string.text_importing_preset_21cb3f)) {
                val imported = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(appString(R.string.text_could_not_open_the_selected_preset_file_42fc48))
                    input.use { store.importProcessingPreset(it).getOrThrow() }
                }
                appString(R.string.text_imported_tap_apply_when_you_want_to_use_it_b70105, (imported.name))
            }
        }
    }

    val editable = library != null && library?.error == null && !busy && pendingExportId == null
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(appString(R.string.text_saved_processing_presets_f22d4b), onBack)
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedButton(onClick = { showOutputBindings = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text(appString(R.string.text_output_presets_8523fd)) }
                }
                item {
                    Text(
                        appString(R.string.text_save_eq_effects_loudness_and_output_settings_cfa9ad),
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Button(
                        onClick = { nameDialog = PresetNameDialog(PresetNameMode.SAVE) },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(appString(R.string.text_save_current_settings_0eaab9), Modifier.padding(start = 8.dp))
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            try {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            } catch (_: ActivityNotFoundException) {
                                perform { error(appString(R.string.text_no_file_picker_is_available_enable_a_files_app_and_try_again_3fdf0a)) }
                            }
                        },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                        Text(appString(R.string.text_import_preset_a72e4d), Modifier.padding(start = 8.dp))
                    }
                    Text(appString(R.string.text_imported_presets_stay_inactive_until_applied_cdd01b),
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) {
                    item {
                        Text(busyMessage, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (autoEqAutoSwitch) {
                    item {
                        Text(appString(R.string.text_manual_presets_pause_automatic_switching_76e93a),
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                library?.error?.let { error ->
                    item {
                        SettingsGroup {
                            Text(appString(R.string.text_presets_unavailable_d18129), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text(error, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                lastAction?.takeIf { actionFailed }?.let { message ->
                    item {
                        Text(message, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (actionFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
                if (library == null) {
                    item { Text(appString(R.string.text_loading_presets_4c510b), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) }
                } else if (library?.error == null && library?.presets.isNullOrEmpty()) {
                    item {
                        SettingsGroup {
                            Text(appString(R.string.text_no_saved_presets_yet_a819ff), Modifier.padding(20.dp),
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                items(library?.presets.orEmpty(), key = { it.id }) { preset ->
                    ProcessingPresetCard(
                        preset = preset,
                        enabled = editable,
                        onApply = {
                            perform {
                                val applied = store.applyProcessingPreset(preset.id).getOrThrow()
                                appString(R.string.text_applied_3d3cce, (applied.presetName)) + if (applied.restartRequired)
                                    appString(R.string.text_restart_aurora_to_activate_the_changed_output_or_engine_settings_b7046b)
                                else ""
                            }
                        },
                        onRename = { nameDialog = PresetNameDialog(PresetNameMode.RENAME, preset) },
                        onDuplicate = { nameDialog = PresetNameDialog(PresetNameMode.DUPLICATE, preset) },
                        onExport = {
                            pendingExportId = preset.id
                            try {
                                exportLauncher.launch(processingPresetFileName(preset.name))
                            } catch (_: ActivityNotFoundException) {
                                pendingExportId = null
                                perform { error(appString(R.string.text_no_file_picker_is_available_enable_a_files_app_and_try_again_3fdf0a)) }
                            }
                        },
                        onDelete = { deleteTarget = preset },
                    )
                }
                item {
                    SettingsGroup {
                        SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath,
                            appString(R.string.text_inspect_the_active_processing_after_applying_a_preset_c9b6f4), onClick = onOpenSignalPath)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()))
    }

    nameDialog?.let { dialog ->
        ProcessingPresetNameDialog(
            dialog = dialog,
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                nameDialog = null
                perform {
                    when (dialog.mode) {
                        PresetNameMode.SAVE -> appString(R.string.text_saved_290e9d, (store.saveProcessingPreset(name).getOrThrow().name))
                        PresetNameMode.RENAME -> {
                            store.renameProcessingPreset(requireNotNull(dialog.preset).id, name).getOrThrow()
                            appString(R.string.text_renamed_preset_to_5dc3bd, (name))
                        }
                        PresetNameMode.DUPLICATE -> appString(R.string.text_created_137992, (store.duplicateProcessingPreset(requireNotNull(dialog.preset).id, name).getOrThrow().name))
                    }
                }
            },
        )
    }
    deleteTarget?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(appString(R.string.text_delete_preset_f004e2)) },
            text = { Text(appString(R.string.text_delete_your_current_audio_settings_will_stay_as_they_are_243d73, (preset.name))) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    perform {
                        store.deleteProcessingPreset(preset.id).getOrThrow()
                        appString(R.string.text_deleted_c338d3, (preset.name))
                    }
                }) { Text(appString(R.string.text_delete_f6fdbe), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(appString(R.string.text_cancel_77dfd2)) } },
        )
    }
}

@Composable
private fun ProcessingPresetCard(
    preset: ProcessingPreset,
    enabled: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(preset.id) { mutableStateOf(false) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(Icons.Filled.MoreVert, contentDescription = appString(R.string.text_actions_for_cf6f1b, (preset.name)))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(appString(R.string.text_rename_d3f4cb)) }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_duplicate_972d57)) }, onClick = { menuOpen = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_export_f3e4fa)) }, onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_delete_f6fdbe)) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        val engine = when (preset.audio.dspMode) {
            DspMode.CUSTOM -> appString(R.string.text_custom_dsp_df083c)
            DspMode.SYSTEM -> appString(R.string.text_system_effects_d5a44a)
            else -> appString(R.string.text_tone_shaping_off_37d5aa)
        }
        val replayGain = when (preset.audio.replayGain) { 1 -> appString(R.string.text_track_replaygain_ddc5fd); 2 -> appString(R.string.text_album_replaygain_23ed6d); else -> appString(R.string.text_replaygain_off_d67cdc) }
        Text("$engine · $replayGain", Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val output = when {
            preset.playback.bitPerfectUsb -> appString(R.string.text_direct_usb_requested_fe8923)
            preset.playback.preferHighRes -> appString(R.string.text_android_output_hi_res_preferred_aaeb20)
            else -> appString(R.string.text_android_output_0a3516)
        }
        Text(output, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (preset.playback.independentOutput) appString(R.string.text_independent_output_on_other_apps_can_keep_playing_b84f60)
            else appString(R.string.text_independent_output_off_request_audio_focus_ee8c22),
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val playback = buildList {
            add(appString(R.string.text_default_speed_3297b3, (preset.playback.defaultSpeed)))
            if (preset.playback.monoAudio) add(appString(R.string.text_mono_c5c553))
            if (preset.playback.skipSilence) add(appString(R.string.text_skip_silence_3d4da2))
            if (preset.playback.crossfadeSec > 0) add(appString(R.string.text_s_crossfade_0fa271, (preset.playback.crossfadeSec)))
        }.joinToString(" · ")
        Text(playback, Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (preset.audio.dspConvEnabled) {
            Text(appString(R.string.text_convolution_206000, (preset.audio.dspConvIrName.ifBlank { appString(R.string.text_saved_impulse_response_a5fc40) })),
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onApply, enabled = enabled, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) { Text(appString(R.string.text_apply_cfea41)) }
    }
}

private fun processingPresetFileName(name: String): String {
    val base = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim().take(64).trimEnd('.', ' ').ifBlank { "aurora-preset" }
    return "$base.aurorapreset.zip"
}

@Composable
private fun ProcessingPresetNameDialog(dialog: PresetNameDialog, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember(dialog) {
        mutableStateOf(when (dialog.mode) {
            PresetNameMode.SAVE -> ""
            PresetNameMode.RENAME -> dialog.preset?.name.orEmpty()
            PresetNameMode.DUPLICATE -> appString(R.string.text_copy_37e469, (dialog.preset?.name.orEmpty().take(70)))
        })
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val title = when (dialog.mode) { PresetNameMode.SAVE -> appString(R.string.text_save_current_settings_0eaab9); PresetNameMode.RENAME -> appString(R.string.text_rename_preset_52abbe); PresetNameMode.DUPLICATE -> appString(R.string.text_duplicate_preset_27bc59) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text(appString(R.string.text_preset_name_eb39e1)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                Spacer(Modifier.height(8.dp))
                Text("${name.length}/80", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(if (dialog.mode == PresetNameMode.DUPLICATE) appString(R.string.text_duplicate_972d57) else appString(R.string.text_save_efc007)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}
