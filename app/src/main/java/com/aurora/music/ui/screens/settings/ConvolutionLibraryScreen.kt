package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.ir.*
import com.aurora.music.playback.engine.SamplePrecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.log10

@Composable
fun ConvolutionLibraryScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val signalPath by container.signalPath.collectAsStateWithLifecycle()
    val playbackRate = signalPath.decoder.format?.rateHz?.takeIf { signalPath.active && it in 8_000..768_000 }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var loadError by remember { mutableStateOf<String?>(null) }
    val libraryFlow = remember(store) {
        store.impulseLibrary.onEach { loadError = null }.catch {
            loadError = it.message ?: appString(R.string.text_could_not_load_the_ir_library_f5856d)
            emit(emptyList())
        }
    }
    val entries by libraryFlow.collectAsStateWithLifecycle<List<ImpulseLibraryEntry>?>(initialValue = null)
    val audio by store.audioPrefs.collectAsStateWithLifecycle<AudioPrefs?>(initialValue = null)
    val rack by store.processingRack.collectAsStateWithLifecycle<ProcessingRack?>(initialValue = null)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var workingText by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<ImpulseLibraryEntry?>(null) }
    var delete by remember { mutableStateOf<ImpulseLibraryEntry?>(null) }
    var prepare by remember { mutableStateOf<ImpulseLibraryEntry?>(null) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportPrepared by rememberSaveable { mutableStateOf(false) }
    val ready = entries != null && loadError == null && !busy
    val current = entries?.firstOrNull { it.id == selectedId }
    val selectedPath = audio?.dspConvIrPath.orEmpty()
    val convolutionEnabled = if (rack?.enabled == true && audio?.dspMode == DspMode.CUSTOM) {
        rack!!.nodes.any { it.kind == RackNodeKind.CONVOLUTION && !it.bypass && it.wet > 0f }
    } else audio?.dspConvEnabled == true

    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun perform(label: String, block: suspend () -> Unit) {
        if (busy || loadError != null) return
        busy = true
        workingText = label
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: appString(R.string.text_could_not_update_the_ir_library_b7593b)) }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform(appString(R.string.text_importing_wav_5811f7)) {
            val imported = withContext(Dispatchers.IO) {
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Impulse.wav"
                val input = context.contentResolver.openInputStream(uri) ?: error(appString(R.string.text_could_not_open_the_wav_file_ad86f4))
                input.use { store.importImpulse(it, name).getOrThrow() }
            }
            selectedId = imported.id
            notify(appString(R.string.text_ir_imported_08d0ed))
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = pendingExportId
        val prepared = pendingExportPrepared
        pendingExportId = null
        if (uri != null && id != null) perform(appString(R.string.text_exporting_wav_501d1a)) {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error(appString(R.string.text_could_not_open_the_destination_ff7025))
                output.use { store.exportImpulse(id, prepared, it).getOrThrow() }
            }
            notify(appString(R.string.text_wav_exported_78ffe9))
        }
    }
    fun export(entry: ImpulseLibraryEntry, prepared: Boolean) {
        if (!ready) return
        pendingExportId = entry.id
        pendingExportPrepared = prepared
        val name = entry.name.filter { it.isLetterOrDigit() || it in " -_" }.trim().take(60).ifBlank { appString(R.string.text_impulse_9ca8ac) }
        runCatching { exporter.launch("$name${if (prepared) " variant" else ""}.wav") }
            .onFailure { pendingExportId = null; notify(appString(R.string.text_no_document_picker_is_available_a8fedf)) }
    }
    fun back() { if (!busy) { if (selectedId != null) selectedId = null else onBack() } }
    BackHandler(onBack = ::back)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(current?.name ?: appString(R.string.text_ir_library_46a40d), ::back)
            if (busy) {
                Text(workingText, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loadError != null) item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(appString(R.string.text_ir_library_unavailable_ba529f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Text(loadError!!, style = MaterialTheme.typography.bodySmall)
                            Text(appString(R.string.text_changes_are_disabled_restore_a_backup_to_recover_the_library_8962a5), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (selectedId == null) {
                    item {
                        Button(onClick = { runCatching { importer.launch(arrayOf("audio/*", "application/octet-stream")) }
                            .onFailure { notify(appString(R.string.text_no_file_picker_is_available_fb12a4)) } }, enabled = ready,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Icon(Icons.Filled.Add, null)
                            Text(appString(R.string.text_import_wav_962ac2), Modifier.padding(start = 8.dp))
                        }
                    }
                    if (selectedPath.isNotBlank() && entries != null && entries!!.none {
                        it.sourcePath == selectedPath || it.prepared?.path == selectedPath
                    }) item {
                        SettingsGroup {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(audio?.dspConvIrName?.ifBlank { appString(R.string.text_selected_ir_6a167c) } ?: appString(R.string.text_selected_ir_6a167c), style = MaterialTheme.typography.titleSmall)
                                OutlinedButton(enabled = ready, onClick = { perform(appString(R.string.text_saving_selected_ir_1e5b57)) {
                                    selectedId = store.importCurrentImpulse().getOrThrow().id
                                } }, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_add_selected_ir_to_library_d9c2a9)) }
                            }
                        }
                    }
                    when {
                        entries == null -> item { ImpulseStatusText(appString(R.string.text_loading_ir_library_0a9e58)) }
                        loadError != null -> Unit
                        entries!!.isEmpty() -> item { ImpulseStatusText(appString(R.string.text_no_saved_impulse_responses_5d3fa1)) }
                        else -> items(entries!!, key = { it.id }) { entry ->
                            ImpulseLibraryRow(entry, ready, selectedPath, onOpen = { selectedId = entry.id },
                                onRename = { rename = entry }, onDelete = { delete = entry })
                        }
                    }
                } else if (current != null) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = ready, onClick = { rename = current }) { Text(appString(R.string.text_rename_d3f4cb)) }
                            TextButton(enabled = ready, onClick = { delete = current }) { Text(appString(R.string.text_delete_f6fdbe)) }
                        }
                    }
                    item {
                        ImpulseDetails(current, ready, selectedPath, playbackRate,
                            onSelect = { prepared -> perform(appString(R.string.text_selecting_ir_cac365)) {
                                store.selectImpulse(current.id, prepared).getOrThrow()
                                notify(appString(R.string.text_ir_selected_9c0796))
                            } }, onExport = { export(current, it) }, onPrepare = { prepare = current })
                    }
                    if (!convolutionEnabled && audio != null && rack != null) item {
                        ImpulseStatusText(appString(R.string.text_enable_convolution_in_equalizer_or_processing_rack_a19cc2))
                    }
                } else if (entries != null && loadError == null) item { ImpulseStatusText(appString(R.string.text_this_ir_is_no_longer_in_the_library_7118f3)) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }
    rename?.let { entry -> ImpulseNameDialog(entry.name, onDismiss = { rename = null }) { name ->
        rename = null
        perform(appString(R.string.text_renaming_ir_4c7642)) { store.renameImpulse(entry.id, name).getOrThrow() }
    } }
    delete?.let { entry -> AlertDialog(onDismissRequest = { if (!busy) delete = null }, title = { Text(appString(R.string.text_delete_137cdc, (entry.name))) },
        text = { Text(appString(R.string.text_removes_this_library_entry_selected_irs_and_saved_presets_stay_un_0fb84e)) },
        confirmButton = { TextButton(enabled = ready, onClick = { perform(appString(R.string.text_deleting_ir_794fb8)) {
            store.deleteImpulse(entry.id).getOrThrow()
            delete = null
            if (selectedId == entry.id) selectedId = null
        } }) { Text(appString(R.string.text_delete_f6fdbe)) } }, dismissButton = { TextButton(enabled = !busy, onClick = { delete = null }) { Text(appString(R.string.text_cancel_77dfd2)) } }) }
    prepare?.let { entry -> ImpulsePreparationDialog(entry, onDismiss = { prepare = null }, onSave = { options ->
        store.prepareImpulse(entry.id, options).getOrThrow()
        prepare = null
        notify(appString(R.string.text_variant_saved_f3c476))
    }) }
}

@Composable
private fun ImpulseLibraryRow(entry: ImpulseLibraryEntry, enabled: Boolean, selectedPath: String,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onOpen).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(entry.sourceMetadata.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val selection = when (selectedPath) {
                    entry.sourcePath -> appString(R.string.text_original_selected_634e39)
                    entry.prepared?.path -> appString(R.string.text_variant_selected_b06527)
                    else -> null
                }
                selection?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Box {
                IconButton(enabled = enabled, onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, appString(R.string.text_actions_for_cf6f1b, (entry.name))) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(appString(R.string.text_rename_d3f4cb)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_delete_f6fdbe)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ImpulseDetails(entry: ImpulseLibraryEntry, enabled: Boolean, selectedPath: String, playbackRate: Int?,
    onSelect: (Boolean) -> Unit, onExport: (Boolean) -> Unit, onPrepare: () -> Unit) {
    var prepared by rememberSaveable(entry.id) { mutableStateOf(false) }
    val showingPrepared = prepared && entry.prepared != null
    val assetPath = if (showingPrepared) entry.prepared!!.path else entry.sourcePath
    val metadata = if (showingPrepared) entry.prepared!!.metadata else entry.sourceMetadata
    val targetRate = playbackRate ?: metadata.sampleRate
    val estimate = remember(metadata, targetRate) { ImpulseLibraryFiles.estimate(metadata, targetRate) }
    val sourceSupported = metadata.frames <= ImpulseLibraryCodec.MAX_PREPARED_FRAMES
    var showSize by rememberSaveable(entry.id) { mutableStateOf(false) }
    var preview by remember(entry, showingPrepared) { mutableStateOf<ImpulsePreview?>(null) }
    var previewError by remember(entry, showingPrepared) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry, showingPrepared) {
        val result = withContext(Dispatchers.IO) { ImpulseLibraryFiles.preview(entry, showingPrepared) }
        result.onSuccess { preview = it }.onFailure { previewError = it.message ?: appString(R.string.text_could_not_read_the_waveform_f99d0d) }
    }
    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showingPrepared, onClick = { prepared = false }, label = { Text(appString(R.string.text_original_c0a806)) })
                if (entry.prepared != null) FilterChip(selected = showingPrepared, onClick = { prepared = true }, label = { Text(appString(R.string.text_variant_cc91b1)) })
            }
            Text(metadata.summary(), style = MaterialTheme.typography.titleSmall)
            Text(appString(R.string.text_frames_ms_753397, (metadata.frames), (impulseNumber(metadata.frames * 1000.0 / metadata.sampleRate))), style = MaterialTheme.typography.bodySmall)
            val format = when (metadata.precision) {
                SamplePrecision.FLOAT_32 -> appString(R.string.text_32_bit_float_a68053)
                SamplePrecision.FLOAT_64 -> appString(R.string.text_64_bit_float_eaae87)
                else -> appString(R.string.text_bit_pcm_692bdd, (metadata.validBits))
            }
            Text(appString(R.string.text_peak_6a5678, (format), (metadata.peak.peakLabel())), style = MaterialTheme.typography.bodySmall)
            Text(appString(R.string.text_source_e9f395, (entry.sourceName)), style = MaterialTheme.typography.bodySmall)
            if (showingPrepared) {
                val options = entry.prepared!!.preparation
                Text(buildList {
                    add(appString(R.string.text_frames_fef602, (options.startFrame), (options.endFrameExclusive)))
                    if (options.normalization != ImpulseNormalization.NONE) add(appString(R.string.text_peak_1_db_472a8a))
                    if (options.minimumPhase) add(appString(R.string.text_minimum_phase_500a84))
                    if (options.delayFrames > 0) add(appString(R.string.text_ms_delay_3db8d0, (impulseNumber(options.delayFrames * 1000.0 / metadata.sampleRate))))
                }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
            }
            when {
                preview != null -> ImpulseWaveform(preview!!)
                previewError != null -> Text(previewError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (!sourceSupported || !estimate.supported) Text(
                if (!sourceSupported) appString(R.string.text_trim_this_response_before_selecting_it_c65802) else appString(R.string.text_too_long_at_khz_trim_first_79c65a, (impulseNumber(targetRate / 1000.0))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { onSelect(showingPrepared) }, enabled = enabled && selectedPath != assetPath && preview != null && sourceSupported && estimate.supported,
                modifier = Modifier.fillMaxWidth()) { Text(if (selectedPath == assetPath) appString(R.string.text_selected_9a976f) else appString(R.string.text_select_0fc8ec, (if (showingPrepared) appString(R.string.text_variant_cc91b1) else appString(R.string.text_original_c0a806)))) }
            OutlinedButton(onClick = { onExport(showingPrepared) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_export_wav_f98f7d)) }
            OutlinedButton(onClick = onPrepare, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_prepare_impulse_828a45)) }
            TextButton(onClick = { showSize = !showSize }) { Text(if (showSize) appString(R.string.text_hide_playback_size_fd99cc) else appString(R.string.text_playback_size_aeb4ca)) }
            if (showSize) {
                Text(appString(R.string.text_khz_32b265, (impulseNumber(targetRate / 1000.0)), (if (playbackRate == null) appString(R.string.text_source_rate_cf5128) else appString(R.string.text_current_playback_27f5a5))), style = MaterialTheme.typography.bodySmall)
                Text(appString(R.string.text_taps_estimated_memory_mib_88d38b, (estimate.targetFrames), (impulseNumber((estimate.decodedBytes + estimate.partitionBytes) / 1048576.0))),
                    style = MaterialTheme.typography.bodySmall)
                if (estimate.resamplingDelayFrames > 0) Text(appString(R.string.text_src_delay_ms_165eac, (impulseNumber(estimate.resamplingDelayFrames * 1000.0 / targetRate))),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ImpulseNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.text_rename_ir_350160)) }, text = {
        OutlinedTextField(name, { name = it.take(80) }, label = { Text(appString(R.string.text_name_709a23)) }, singleLine = true)
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text(appString(R.string.text_save_efc007)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

@Composable
private fun ImpulseStatusText(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun ImpulseMetadata.summary(): String = appString(R.string.text_khz_368e33, (when (channels) { 1 -> appString(R.string.text_mono_c5c553); 4 -> appString(R.string.text_true_stereo_ll_lr_rl_rr_f8b7db); else -> appString(R.string.text_stereo_f4f390) }), (impulseNumber(sampleRate / 1000.0)))
internal fun impulseNumber(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
internal fun Double.peakLabel(): String = if (this == 0.0) "−∞ dBFS" else "${impulseNumber(20.0 * log10(this))} dBFS"
