package com.aurora.music.ui.screens.settings

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
    var busyMessage by remember { mutableStateOf("Updating preset…") }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDialog by remember { mutableStateOf<PresetNameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<ProcessingPreset?>(null) }
    var lastAction by remember { mutableStateOf<String?>(null) }
    var actionFailed by remember { mutableStateOf(false) }

    fun perform(progressMessage: String = "Updating preset…", operation: suspend () -> String) {
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
                "This file is not a valid preset archive, or it is damaged. Choose an Aurora preset export."
            } catch (error: IOException) {
                actionFailed = true
                "Could not read or write the preset file. Check access to the selected location and try again."
            } catch (error: SecurityException) {
                actionFailed = true
                "Access to the selected file was denied. Choose the file or location again."
            } catch (error: Exception) {
                actionFailed = true
                error.message ?: "Could not update the preset. Please try again."
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
            perform("Exporting preset…") {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Could not open the selected location for saving.")
                    output.use { store.exportProcessingPreset(presetId, it).getOrThrow() }
                }
                "Preset exported with its saved settings and any impulse response."
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            perform("Importing preset…") {
                val imported = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected preset file.")
                    input.use { store.importProcessingPreset(it).getOrThrow() }
                }
                "Imported ${imported.name}. Tap Apply when you want to use it."
            }
        }
    }

    val editable = library != null && library?.error == null && !busy && pendingExportId == null
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar("Saved processing presets", onBack)
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Save your current EQ, effects, loudness, playback processing and output preferences together. Later adjustments do not change a saved preset.",
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
                        Text("Save current settings", Modifier.padding(start = 8.dp))
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            try {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            } catch (_: ActivityNotFoundException) {
                                perform { error("No file picker is available. Enable a files app and try again.") }
                            }
                        },
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                        Text("Import preset", Modifier.padding(start = 8.dp))
                    }
                    Text("Import an Aurora preset file to add it to your collection. Your sound changes only when you tap Apply.",
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
                        Text("Automatic device correction is on. Changing output devices can replace the EQ from an applied preset.",
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                library?.error?.let { error ->
                    item {
                        SettingsGroup {
                            Text("Presets unavailable", Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text(error, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                lastAction?.let { message ->
                    item {
                        Text(message, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (actionFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
                if (library == null) {
                    item { Text("Loading presets…", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium) }
                } else if (library?.error == null && library?.presets.isNullOrEmpty()) {
                    item {
                        SettingsGroup {
                            Text("No saved presets yet", Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                                style = MaterialTheme.typography.titleMedium)
                            Text("Adjust your sound in settings, then save it here with a name you will recognize.",
                                Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                "Applied ${applied.presetName}." + if (applied.restartRequired)
                                    " Restart Aurora to activate the changed output or engine settings."
                                else " Check Signal Path for the active processing."
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
                                perform { error("No file picker is available. Enable a files app and try again.") }
                            }
                        },
                        onDelete = { deleteTarget = preset },
                    )
                }
                item {
                    Text(
                        "Use a preset’s Export action to save its settings and impulse response in one file for another Aurora installation. Output and engine changes can require restarting Aurora; Signal Path shows what is actually running.",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    SettingsGroup {
                        SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath,
                            "Inspect the active processing after applying a preset", onClick = onOpenSignalPath)
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
                        PresetNameMode.SAVE -> "Saved ${store.saveProcessingPreset(name).getOrThrow().name}."
                        PresetNameMode.RENAME -> {
                            store.renameProcessingPreset(requireNotNull(dialog.preset).id, name).getOrThrow()
                            "Renamed preset to $name."
                        }
                        PresetNameMode.DUPLICATE -> "Created ${store.duplicateProcessingPreset(requireNotNull(dialog.preset).id, name).getOrThrow().name}."
                    }
                }
            },
        )
    }
    deleteTarget?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete preset?") },
            text = { Text("Delete “${preset.name}”? Your current audio settings will stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    perform {
                        store.deleteProcessingPreset(preset.id).getOrThrow()
                        "Deleted ${preset.name}."
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
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
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${preset.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Export") }, onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        val engine = when (preset.audio.dspMode) {
            DspMode.CUSTOM -> "Custom DSP"
            DspMode.SYSTEM -> "System effects"
            else -> "Tone shaping off"
        }
        val replayGain = when (preset.audio.replayGain) { 1 -> "Track ReplayGain"; 2 -> "Album ReplayGain"; else -> "ReplayGain off" }
        Text("$engine · $replayGain", Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val output = when {
            preset.playback.bitPerfectUsb -> "Direct USB requested"
            preset.playback.preferHighRes -> "Android output · hi-res preferred"
            else -> "Android output"
        }
        Text(output, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (preset.playback.independentOutput) "Independent output on · other apps can keep playing"
            else "Independent output off · request audio focus",
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val playback = buildList {
            add("${preset.playback.defaultSpeed}× default speed")
            if (preset.playback.monoAudio) add("Mono")
            if (preset.playback.skipSilence) add("Skip silence")
            if (preset.playback.crossfadeSec > 0) add("${preset.playback.crossfadeSec}s crossfade")
        }.joinToString(" · ")
        Text(playback, Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (preset.audio.dspConvEnabled) {
            Text("Convolution: ${preset.audio.dspConvIrName.ifBlank { "Saved impulse response" }}",
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onApply, enabled = enabled, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) { Text("Apply") }
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
            PresetNameMode.DUPLICATE -> "${dialog.preset?.name.orEmpty().take(70)} copy"
        })
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val title = when (dialog.mode) { PresetNameMode.SAVE -> "Save current settings"; PresetNameMode.RENAME -> "Rename preset"; PresetNameMode.DUPLICATE -> "Duplicate preset" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Preset name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                Spacer(Modifier.height(8.dp))
                Text("${name.length}/80", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(if (dialog.mode == PresetNameMode.DUPLICATE) "Duplicate" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
