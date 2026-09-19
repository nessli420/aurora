package com.aurora.music.ui.screens.settings

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
            loadError = it.message ?: "Could not load the IR library."
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
            catch (failure: Exception) { notify(failure.message ?: "Could not update the IR library.") }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform("Importing WAV…") {
            val imported = withContext(Dispatchers.IO) {
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Impulse.wav"
                val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the WAV file.")
                input.use { store.importImpulse(it, name).getOrThrow() }
            }
            selectedId = imported.id
            notify("IR imported.")
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = pendingExportId
        val prepared = pendingExportPrepared
        pendingExportId = null
        if (uri != null && id != null) perform("Exporting WAV…") {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the destination.")
                output.use { store.exportImpulse(id, prepared, it).getOrThrow() }
            }
            notify("WAV exported.")
        }
    }
    fun export(entry: ImpulseLibraryEntry, prepared: Boolean) {
        if (!ready) return
        pendingExportId = entry.id
        pendingExportPrepared = prepared
        val name = entry.name.filter { it.isLetterOrDigit() || it in " -_" }.trim().take(60).ifBlank { "Impulse" }
        runCatching { exporter.launch("$name${if (prepared) " variant" else ""}.wav") }
            .onFailure { pendingExportId = null; notify("No document picker is available.") }
    }
    fun back() { if (!busy) { if (selectedId != null) selectedId = null else onBack() } }
    BackHandler(onBack = ::back)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(current?.name ?: "IR library", ::back)
            if (busy) {
                Text(workingText, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loadError != null) item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("IR library unavailable", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                            Text(loadError!!, style = MaterialTheme.typography.bodySmall)
                            Text("Changes are disabled. Restore a backup to recover the library.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (selectedId == null) {
                    item {
                        Button(onClick = { runCatching { importer.launch(arrayOf("audio/*", "application/octet-stream")) }
                            .onFailure { notify("No file picker is available.") } }, enabled = ready,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Icon(Icons.Filled.Add, null)
                            Text("Import WAV", Modifier.padding(start = 8.dp))
                        }
                    }
                    if (selectedPath.isNotBlank() && entries != null && entries!!.none {
                        it.sourcePath == selectedPath || it.prepared?.path == selectedPath
                    }) item {
                        SettingsGroup {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(audio?.dspConvIrName?.ifBlank { "Selected IR" } ?: "Selected IR", style = MaterialTheme.typography.titleSmall)
                                OutlinedButton(enabled = ready, onClick = { perform("Saving selected IR…") {
                                    selectedId = store.importCurrentImpulse().getOrThrow().id
                                } }, modifier = Modifier.fillMaxWidth()) { Text("Add selected IR to library") }
                            }
                        }
                    }
                    when {
                        entries == null -> item { ImpulseStatusText("Loading IR library…") }
                        loadError != null -> Unit
                        entries!!.isEmpty() -> item { ImpulseStatusText("No saved impulse responses.") }
                        else -> items(entries!!, key = { it.id }) { entry ->
                            ImpulseLibraryRow(entry, ready, selectedPath, onOpen = { selectedId = entry.id },
                                onRename = { rename = entry }, onDelete = { delete = entry })
                        }
                    }
                } else if (current != null) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = ready, onClick = { rename = current }) { Text("Rename") }
                            TextButton(enabled = ready, onClick = { delete = current }) { Text("Delete") }
                        }
                    }
                    item {
                        ImpulseDetails(current, ready, selectedPath, playbackRate,
                            onSelect = { prepared -> perform("Selecting IR…") {
                                store.selectImpulse(current.id, prepared).getOrThrow()
                                notify("IR selected.")
                            } }, onExport = { export(current, it) }, onPrepare = { prepare = current })
                    }
                    if (!convolutionEnabled && audio != null && rack != null) item {
                        ImpulseStatusText("Enable convolution in Equalizer or Processing rack.")
                    }
                } else if (entries != null && loadError == null) item { ImpulseStatusText("This IR is no longer in the library.") }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }
    rename?.let { entry -> ImpulseNameDialog(entry.name, onDismiss = { rename = null }) { name ->
        rename = null
        perform("Renaming IR…") { store.renameImpulse(entry.id, name).getOrThrow() }
    } }
    delete?.let { entry -> AlertDialog(onDismissRequest = { if (!busy) delete = null }, title = { Text("Delete ${entry.name}?") },
        text = { Text("Removes this library entry. Selected IRs and saved presets stay unchanged.") },
        confirmButton = { TextButton(enabled = ready, onClick = { perform("Deleting IR…") {
            store.deleteImpulse(entry.id).getOrThrow()
            delete = null
            if (selectedId == entry.id) selectedId = null
        } }) { Text("Delete") } }, dismissButton = { TextButton(enabled = !busy, onClick = { delete = null }) { Text("Cancel") } }) }
    prepare?.let { entry -> ImpulsePreparationDialog(entry, onDismiss = { prepare = null }, onSave = { options ->
        store.prepareImpulse(entry.id, options).getOrThrow()
        prepare = null
        notify("Variant saved.")
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
                    entry.sourcePath -> "Original selected"
                    entry.prepared?.path -> "Variant selected"
                    else -> null
                }
                selection?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Box {
                IconButton(enabled = enabled, onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for ${entry.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
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
        result.onSuccess { preview = it }.onFailure { previewError = it.message ?: "Could not read the waveform." }
    }
    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showingPrepared, onClick = { prepared = false }, label = { Text("Original") })
                if (entry.prepared != null) FilterChip(selected = showingPrepared, onClick = { prepared = true }, label = { Text("Variant") })
            }
            Text(metadata.summary(), style = MaterialTheme.typography.titleSmall)
            Text("${metadata.frames} frames · ${impulseNumber(metadata.frames * 1000.0 / metadata.sampleRate)} ms", style = MaterialTheme.typography.bodySmall)
            val format = when (metadata.precision) {
                SamplePrecision.FLOAT_32 -> "32-bit float"
                SamplePrecision.FLOAT_64 -> "64-bit float"
                else -> "${metadata.validBits}-bit PCM"
            }
            Text("$format · Peak ${metadata.peak.peakLabel()}", style = MaterialTheme.typography.bodySmall)
            Text("Source: ${entry.sourceName}", style = MaterialTheme.typography.bodySmall)
            if (showingPrepared) {
                val options = entry.prepared!!.preparation
                Text(buildList {
                    add("Frames ${options.startFrame}–${options.endFrameExclusive}")
                    if (options.normalization != ImpulseNormalization.NONE) add("Peak −1 dB")
                    if (options.minimumPhase) add("Minimum phase")
                    if (options.delayFrames > 0) add("${impulseNumber(options.delayFrames * 1000.0 / metadata.sampleRate)} ms delay")
                }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
            }
            when {
                preview != null -> ImpulseWaveform(preview!!)
                previewError != null -> Text(previewError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (!sourceSupported || !estimate.supported) Text(
                if (!sourceSupported) "Trim this response before selecting it." else "Too long at ${impulseNumber(targetRate / 1000.0)} kHz. Trim first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { onSelect(showingPrepared) }, enabled = enabled && selectedPath != assetPath && preview != null && sourceSupported && estimate.supported,
                modifier = Modifier.fillMaxWidth()) { Text(if (selectedPath == assetPath) "Selected" else "Select ${if (showingPrepared) "variant" else "original"}") }
            OutlinedButton(onClick = { onExport(showingPrepared) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Export WAV") }
            OutlinedButton(onClick = onPrepare, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Prepare impulse") }
            TextButton(onClick = { showSize = !showSize }) { Text(if (showSize) "Hide playback size" else "Playback size") }
            if (showSize) {
                Text("${impulseNumber(targetRate / 1000.0)} kHz · ${if (playbackRate == null) "Source rate" else "Current playback"}", style = MaterialTheme.typography.bodySmall)
                Text("${estimate.targetFrames} taps · Estimated memory ${impulseNumber((estimate.decodedBytes + estimate.partitionBytes) / 1048576.0)} MiB",
                    style = MaterialTheme.typography.bodySmall)
                if (estimate.resamplingDelayFrames > 0) Text("SRC delay: ${impulseNumber(estimate.resamplingDelayFrames * 1000.0 / targetRate)} ms",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ImpulseNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename IR") }, text = {
        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ImpulseStatusText(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun ImpulseMetadata.summary(): String = "${when (channels) { 1 -> "Mono"; 4 -> "True stereo · LL/LR/RL/RR"; else -> "Stereo" }} · ${impulseNumber(sampleRate / 1000.0)} kHz"
internal fun impulseNumber(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
internal fun Double.peakLabel(): String = if (this == 0.0) "−∞ dBFS" else "${impulseNumber(20.0 * log10(this))} dBFS"
