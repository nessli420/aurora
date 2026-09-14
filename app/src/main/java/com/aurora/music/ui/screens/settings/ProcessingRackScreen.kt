package com.aurora.music.ui.screens.settings

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import com.aurora.music.playback.ConvolutionProcessor
import com.aurora.music.playback.DspCoeffBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

private data class RackSave(val version: Int, val rack: ProcessingRack, val completion: CompletableDeferred<Result<Unit>>? = null)
private data class RackNameTarget(val nodeId: String?, val name: String)
private enum class RackTemplate { LEGACY, RECOMMENDED }

@Composable
fun ProcessingRackScreen(contentPadding: PaddingValues, onBack: () -> Unit,
    onOpenPresets: () -> Unit, onOpenSignalPath: () -> Unit, onOpenTuning: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val signalPath by container.signalPath.collectAsStateWithLifecycle()
    val persisted by store.processingRack.collectAsStateWithLifecycle<ProcessingRack?>(initialValue = null)
    val audio by store.audioPrefs.collectAsStateWithLifecycle(initialValue = AudioPrefs())
    val playback by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val saves = remember { Channel<RackSave>(Channel.CONFLATED) }
    var rack by remember { mutableStateOf<ProcessingRack?>(null) }
    var requestedVersion by remember { mutableIntStateOf(0) }
    var savedVersion by remember { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var nameTarget by remember { mutableStateOf<RackNameTarget?>(null) }
    var removeTarget by remember { mutableStateOf<ProcessingRackNode?>(null) }
    var template by remember { mutableStateOf<RackTemplate?>(null) }
    var importingIr by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var importEq by remember { mutableStateOf(false) }
    var exportEq by remember { mutableStateOf<ProcessingRackNode?>(null) }
    var pendingEqExport by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(persisted) {
        if (requestedVersion == savedVersion) rack = persisted
    }
    LaunchedEffect(saves) {
        for (request in saves) {
            val result = store.setProcessingRack(request.rack)
            if (request.version == requestedVersion) {
                savedVersion = request.version
                if (result.isFailure) {
                    rack = store.processingRack.first()
                    scope.launch { snackbar.showSnackbar(result.exceptionOrNull()?.message ?: "Could not save the rack.") }
                }
            }
            request.completion?.complete(result)
        }
    }
    fun change(transform: (ProcessingRack) -> ProcessingRack) {
        val current = rack ?: return
        val next = runCatching { ProcessingRackCodec.validate(transform(current)) }
        next.onSuccess {
            rack = it
            requestedVersion++
            saves.trySend(RackSave(requestedVersion, it))
        }.onFailure { error -> scope.launch { snackbar.showSnackbar(error.message ?: "Could not update the rack.") } }
    }
    fun changeNode(id: String, transform: (ProcessingRackNode) -> ProcessingRackNode) {
        change { current -> current.copy(nodes = current.nodes.map { if (it.id == id) transform(it) else it }) }
    }
    suspend fun appendEq(profile: ParsedEq, name: String): Result<Unit> {
        snapshotFlow { requestedVersion == savedVersion }.first { it }
        val current = rack ?: return Result.failure(IllegalStateException("The rack is still loading."))
        val next = RackEqTextCodec.appendToRack(current, profile, name).getOrElse { return Result.failure(it) }
        val completion = CompletableDeferred<Result<Unit>>()
        requestedVersion++
        rack = next
        // Gain and EQ enter the graph in one atomic store write after the explicit preview.
        saves.send(RackSave(requestedVersion, next, completion))
        return completion.await()
    }
    fun moveNode(id: String, step: Int) {
        change { current ->
            val list = current.nodes.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            if (from >= 0 && from + step in list.indices) list.add(from + step, list.removeAt(from))
            current.copy(nodes = list)
        }
    }
    fun leave(action: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            // Complete the latest conflated write before navigation cancels this screen's scope.
            snapshotFlow { requestedVersion == savedVersion }.first { it }
            action()
        }
    }
    val pickImpulse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importingIr) {
            importingIr = true
            scope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        val displayName = context.contentResolver.query(uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }?.takeIf { it.isNotBlank() } ?: "Impulse response.wav"
                        val directory = File(context.filesDir, "impulses").apply { mkdirs() }
                        val file = File(directory, "rack-${UUID.randomUUID()}.wav")
                        try {
                            val input = context.contentResolver.openInputStream(uri) ?: error("Could not open this file.")
                            input.use { source -> file.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 64L * 1024 * 1024) { "Choose an impulse response smaller than 64 MiB." }
                                    output.write(buffer, 0, count)
                                }
                            } }
                            ConvolutionProcessor.loadWavResult(file).getOrThrow()
                        } catch (error: Exception) { file.delete(); throw error }
                        // Once published, this immutable asset may be referenced by a saved preset.
                        // Do not delete it if the picker screen is removed during the store update.
                        withContext(NonCancellable) { store.setDspConvIr(file.absolutePath, displayName) }
                        displayName
                    }
                    snackbar.showSnackbar("Loaded $name")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { snackbar.showSnackbar(error.message ?: "Could not load this impulse response.") }
                finally { importingIr = false }
            }
        }
    }
    fun openImpulsePicker() {
        runCatching { pickImpulse.launch(arrayOf("audio/*", "application/octet-stream", "application/x-wav")) }
            .onFailure { scope.launch { snackbar.showSnackbar("No file picker is available.") } }
    }
    val exportEqLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingEqExport
        pendingEqExport = null
        if (uri != null && text != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the selected location.")
                    output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                snackbar.showSnackbar("Parametric EQ text exported.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: "Could not export EQ text.") }
        }
    }

    val current = rack
    val edited = current?.nodes?.firstOrNull { it.id == editingId }
    BackHandler { if (edited != null) editingId = null else leave(onBack) }
    Box(Modifier.fillMaxSize()) {
        if (edited != null && current != null) {
            key(edited.id) {
                RackNodeEditor(edited, current.parametricBandCount(), current.enabled, audio,
                    contentPadding, importingIr, onBack = { editingId = null },
                    onEdit = { transform -> changeNode(edited.id, transform) },
                    onRename = { nameTarget = RackNameTarget(edited.id, edited.name) },
                    onPickImpulse = ::openImpulsePicker,
                    decoderRate = signalPath.decoder.format?.rateHz?.takeIf { signalPath.active && it in 8_000..768_000 },
                    onExportEq = { exportEq = edited })
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SettingsTopBar("Processing rack") { leave(onBack) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (current == null) item { Text("Loading your rack…", Modifier.padding(20.dp)) }
                    else {
                        item {
                            SettingsGroup {
                                SegmentedRow("Processing mode", listOf("Standard", "Rack"), if (current.enabled) 1 else 0) { choice ->
                                    change { it.copy(enabled = choice == 1) }
                                }
                                RackDescription(if (current.enabled)
                                    "Stages run from top to bottom. Changes apply to playback; the rack owns its EQ and channel settings."
                                else "Standard settings are active. Arrange and tune this rack, then choose Rack to use it.")
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(current.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("${current.nodes.size}/16 stages · ${current.parametricBandCount()}/64 parametric bands",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { nameTarget = RackNameTarget(null, current.name) }) {
                                    Icon(Icons.Filled.Edit, "Rename rack")
                                }
                            }
                        }
                        itemsIndexed(current.nodes, key = { _, node -> node.id }) { index, node ->
                            RackNodeCard(node, index, current.nodes.size, onEdit = { editingId = node.id },
                                onBypass = { bypass -> changeNode(node.id) { it.copy(bypass = bypass) } },
                                onMove = { moveNode(node.id, it) },
                                onRename = { nameTarget = RackNameTarget(node.id, node.name) },
                                canDuplicate = current.nodes.size < 16 && node.kind != RackNodeKind.CONVOLUTION &&
                                    (node.kind !in listOf(RackNodeKind.EQ, RackNodeKind.LEGACY_DSP) ||
                                        current.parametricBandCount() + node.audio.dspParametric.size <= 64),
                                onDuplicate = { change { it.copy(nodes = it.nodes + node.copy(
                                    id = UUID.randomUUID().toString(), name = "${node.name.take(70)} copy")) } },
                                onRemove = { removeTarget = node })
                        }
                        item {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                Button(onClick = { addMenu = true }, enabled = current.nodes.size < 16, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Filled.Add, null)
                                    Text("Add stage", Modifier.padding(start = 8.dp))
                                }
                                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                    RackNodeKind.entries.forEach { kind ->
                                        DropdownMenuItem(text = { Text(kind.label()) },
                                            enabled = kind != RackNodeKind.CONVOLUTION || current.nodes.none { it.kind == kind },
                                            onClick = {
                                                addMenu = false
                                                val node = ProcessingRackNode(UUID.randomUUID().toString(), kind.label(), kind,
                                                    audio = AudioPrefs(dspLimiterEnabled = false, dspLimiterCeilingDb = -1f))
                                                change { it.copy(nodes = it.nodes + node) }
                                                editingId = node.id
                                            })
                                    }
                                }
                            }
                            if (current.nodes.isEmpty()) RackDescription("Add a stage to begin. An empty rack passes audio through.")
                        }
                        item {
                            SettingsSectionTitle("Starting points")
                            SettingsGroup {
                                SettingsDestinationRow(Icons.Filled.ShowChart, SettingsDestinations.tuning, onClick = { leave(onOpenTuning) })
                                SettingsRowDivider()
                                SettingsNavRow(Icons.Filled.FileDownload, "Import EQ text", "Preview a file or pasted filters, then append them with their preamp") {
                                    importEq = true
                                }
                                SettingsRowDivider()
                                SettingsNavRow(Icons.Filled.History, "Copy standard settings", "Copy software DSP and current channel settings") {
                                    template = RackTemplate.LEGACY
                                }
                                SettingsRowDivider()
                                SettingsNavRow(Icons.Filled.AutoAwesome, "Recommended order", "Separate stages with a final limiter after convolution") {
                                    template = RackTemplate.RECOMMENDED
                                }
                            }
                        }
                        item {
                            SettingsSectionTitle("Save & inspect")
                            SettingsGroup {
                                SettingsDestinationRow(Icons.Filled.Bookmark, SettingsDestinations.processingPresets, onClick = { leave(onOpenPresets) })
                                SettingsRowDivider()
                                SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath, onClick = { leave(onOpenSignalPath) })
                            }
                            RackDescription(if (requestedVersion != savedVersion) "Saving changes…" else
                                "Your rack is saved automatically. Saved processing presets can store the rack and its selected impulse response together.")
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }
    if (importEq && current != null) RackEqImportDialog(current, onDismiss = { importEq = false }, onAppend = ::appendEq)
    exportEq?.let { target ->
        RackEqExportDialog(target, onDismiss = { exportEq = null }, onSaveText = { text ->
            pendingEqExport = text
            val filename = target.name.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }
                .trim().take(60).ifBlank { "Aurora EQ" }
            runCatching { exportEqLauncher.launch("$filename.txt") }.onSuccess { exportEq = null }.onFailure {
                pendingEqExport = null
                scope.launch { snackbar.showSnackbar("No document picker is available.") }
            }
        }, onOpenPresets = { exportEq = null; leave(onOpenPresets) })
    }
    nameTarget?.let { target ->
        RackNameDialog(target.name, if (target.nodeId == null) "Rename rack" else "Rename stage", onDismiss = { nameTarget = null }) { name ->
            if (target.nodeId == null) change { it.copy(name = name) }
            else changeNode(target.nodeId) { it.copy(name = name) }
            nameTarget = null
        }
    }
    removeTarget?.let { target ->
        AlertDialog(onDismissRequest = { removeTarget = null }, title = { Text("Remove ${target.name}?") },
            text = { Text("This removes the stage and its settings from this rack.") },
            confirmButton = { TextButton(onClick = {
                change { it.copy(nodes = it.nodes.filterNot { node -> node.id == target.id }) }
                removeTarget = null
            }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } })
    }
    template?.let { selected ->
        AlertDialog(onDismissRequest = { template = null }, title = { Text("Replace this rack?") },
            text = { Text(if (selected == RackTemplate.LEGACY)
                "Copy your standard software EQ, effects and mono setting into an editable rack with the original processing order." +
                    if (audio.dspMode == DspMode.SYSTEM) " Android System EQ is separate and is not copied; enabling the rack selects Custom processing." else ""
            else "Copy your standard sound settings into separate stages and place an enabled limiter last, after convolution. This changes the processing order.") },
            confirmButton = { TextButton(onClick = {
                change { old -> (if (selected == RackTemplate.LEGACY) ProcessingRack.legacy(audio, playback.monoAudio)
                    else ProcessingRack.recommended(audio, playback.monoAudio)).copy(enabled = old.enabled) }
                template = null
            }) { Text("Replace rack") } }, dismissButton = { TextButton(onClick = { template = null }) { Text("Cancel") } })
    }
}

@Composable
private fun RackNodeCard(node: ProcessingRackNode, index: Int, count: Int, onEdit: () -> Unit,
    onBypass: (Boolean) -> Unit, onMove: (Int) -> Unit, onRename: () -> Unit,
    canDuplicate: Boolean, onDuplicate: () -> Unit, onRemove: () -> Unit) {
    var menu by remember(node.id) { mutableStateOf(false) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 8.dp)) {
                Text("${index + 1}. ${node.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(node.kind.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(if (node.bypass) "Bypassed" else "${node.summary()} · ${(node.wet * 100).roundToInt()}% wet",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for ${node.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit settings") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, enabled = canDuplicate, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(Icons.Filled.ArrowUpward, "Move ${node.name} up") }
            IconButton(onClick = { onMove(1) }, enabled = index + 1 < count) { Icon(Icons.Filled.ArrowDownward, "Move ${node.name} down") }
            TextButton(onClick = onEdit) { Text("Edit") }
            Spacer(Modifier.weight(1f))
            Text("Bypass", style = MaterialTheme.typography.labelMedium)
            Switch(checked = node.bypass, onCheckedChange = onBypass, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun RackNodeEditor(node: ProcessingRackNode, totalBands: Int, rackEnabled: Boolean, globalAudio: AudioPrefs,
    padding: PaddingValues, importingIr: Boolean, onBack: () -> Unit,
    onEdit: ((ProcessingRackNode) -> ProcessingRackNode) -> Unit, onRename: () -> Unit, onPickImpulse: () -> Unit,
    decoderRate: Int?, onExportEq: () -> Unit) {
    val audio = node.audio
    val legacy = node.kind == RackNodeKind.LEGACY_DSP
    var legacySection by rememberSaveable(node.id) { mutableStateOf("Equalizer") }
    var sectionMenu by remember { mutableStateOf(false) }
    var bandEdit by remember { mutableStateOf<Int?>(null) }
    var showGraphic by rememberSaveable(node.id) { mutableStateOf(false) }
    var showResponse by rememberSaveable(node.id) { mutableStateOf(false) }
    fun changeAudio(transform: (AudioPrefs) -> AudioPrefs) = onEdit { it.copy(audio = transform(it.audio)) }
    fun shows(kind: RackNodeKind, section: String) = node.kind == kind || legacy && legacySection == section
    val eq = shows(RackNodeKind.EQ, "Equalizer")
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(node.name, onBack)
        LazyColumn(Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SettingsGroup {
                    SettingsSwitchRow(title = "Bypass stage", checked = node.bypass,
                        subtitle = if (rackEnabled) "Keep the settings while passing this stage unchanged" else "This rack is currently inactive",
                        onCheckedChange = { bypass -> onEdit { it.copy(bypass = bypass) } })
                    SettingsSliderRow("Wet / dry", "${(node.wet * 100).roundToInt()}% wet", node.wet, 0f..1f) { wet -> onEdit { it.copy(wet = wet) } }
                    TextButton(onClick = onRename, modifier = Modifier.padding(start = 12.dp)) { Text("Rename stage") }
                }
            }
            if (legacy) item {
                SettingsGroup {
                    Box {
                        SettingsNavRow(Icons.Filled.Tune, "Legacy DSP settings", legacySection) { sectionMenu = true }
                        DropdownMenu(expanded = sectionMenu, onDismissRequest = { sectionMenu = false }) {
                            listOf("Equalizer", "Gain", "Stereo", "Crossfeed", "Saturation", "Dynamics", "Delay").forEach { section ->
                                DropdownMenuItem(text = { Text(section) }, onClick = { legacySection = section; sectionMenu = false })
                            }
                        }
                    }
                    RackDescription("These settings belong to this stage. The original EQ → gain → saturation → stereo → dynamics → delay order is retained.")
                }
            }
            if (eq) {
                if (!legacy) {
                    item {
                        SettingsGroup {
                            SegmentedRow("Channels", listOf("Both", "Left", "Right"), node.eqChannel.ordinal) { selected ->
                                onEdit { it.copy(eqChannel = RackEqChannel.entries[selected]) }
                            }
                            SettingsNavRow(Icons.Filled.ShowChart, "Calculated response", if (showResponse) "Hide graph" else "Magnitude, phase and group delay for this stage") {
                                showResponse = !showResponse
                            }
                            SettingsRowDivider()
                            SettingsNavRow(Icons.Filled.FileUpload, "Export parametric EQ text", "Review what the text file includes before saving", onClick = onExportEq)
                        }
                    }
                    if (showResponse) item { RackEqResponseCard(node, decoderRate, rackEnabled) }
                }
                val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(audio.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
                item {
                    SettingsGroup {
                        SettingsNavRow(Icons.Filled.Tune, "Graphic EQ", "${layout.name} · ${if (showGraphic) "Hide" else "Show"} controls") { showGraphic = !showGraphic }
                        if (showGraphic) {
                            SegmentedRow("Layout", DspCoeffBuilder.GRAPHIC_LAYOUTS.map { it.name }, audio.dspGraphicLayout) { selected ->
                                changeAudio { old -> old.copy(dspGraphicLayout = selected) }
                            }
                            layout.freqs.forEachIndexed { index, frequency ->
                                val gain = audio.dspGraphicBands.getOrElse(index) { 0f }
                                SettingsSliderRow(rackFrequency(frequency), rackDb(gain), gain.coerceIn(-12f, 12f), -12f..12f) { value ->
                                    changeAudio { old -> old.copy(dspGraphicBands = List(maxOf(old.dspGraphicBands.size, layout.freqs.size)) {
                                        if (it == index) value else old.dspGraphicBands.getOrElse(it) { 0f }
                                    }) }
                                }
                            }
                        }
                    }
                }
                item {
                    SettingsSectionTitle("Parametric EQ · ${audio.dspParametric.size} ${if (audio.dspParametric.size == 1) "band" else "bands"}")
                    RackDescription("$totalBands of 64 parametric bands used across the rack.${if (legacy) " This legacy stage supports 12; add an Equalizer stage for more." else " Tap a band to edit frequency, gain, Q and filter type."}")
                }
                itemsIndexed(audio.dspParametric) { index, band ->
                    RackBandRow(index, band, audio.dspParametric.size, canDuplicate = totalBands < 64 && (!legacy || audio.dspParametric.size < 12),
                        onEdit = { bandEdit = index }, onMove = { step -> changeAudio { old ->
                            val list = old.dspParametric.toMutableList()
                            if (index in list.indices && index + step in list.indices) list.add(index + step, list.removeAt(index))
                            old.copy(dspParametric = list)
                        } }, onDuplicate = { changeAudio { old -> old.copy(dspParametric = old.dspParametric.toMutableList().also { it.add(index + 1, band) }) } },
                        onRemove = { changeAudio { old -> old.copy(dspParametric = old.dspParametric.filterIndexed { i, _ -> i != index }) } })
                }
                item {
                    OutlinedButton(onClick = { bandEdit = -1 }, enabled = totalBands < 64 && (!legacy || audio.dspParametric.size < 12),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Icon(Icons.Filled.Add, null); Text("Add parametric band", Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (shows(RackNodeKind.GAIN, "Gain")) item {
                SettingsGroup { RackDbSlider("Preamp", audio.dspPreampDb, -12f..12f) { value -> changeAudio { it.copy(dspPreampDb = value) } } }
            }
            if (shows(RackNodeKind.STEREO, "Stereo")) item {
                SettingsGroup {
                    SettingsSliderRow("Stereo width", "%.2f×".format(audio.dspWidth), audio.dspWidth.coerceIn(0f, 2f), 0f..2f) { value -> changeAudio { it.copy(dspWidth = value) } }
                    RackDescription("0 combines the channels to mono. 1 keeps the stereo width unchanged.")
                    SettingsSliderRow("Balance", if (audio.dspBalance == 0f) "Center" else "${(kotlin.math.abs(audio.dspBalance) * 100).roundToInt()}% ${if (audio.dspBalance < 0f) "left" else "right"}",
                        audio.dspBalance.coerceIn(-1f, 1f), -1f..1f) { value -> changeAudio { it.copy(dspBalance = value) } }
                    RackDbSlider("Left trim", audio.dspTrimLeftDb, -12f..0f) { value -> changeAudio { it.copy(dspTrimLeftDb = value) } }
                    RackDbSlider("Right trim", audio.dspTrimRightDb, -12f..0f) { value -> changeAudio { it.copy(dspTrimRightDb = value) } }
                }
            }
            if (shows(RackNodeKind.SATURATION, "Saturation")) item {
                SettingsGroup { SettingsSliderRow("Tube saturation", "${(audio.dspSaturation * 100).roundToInt()}%", audio.dspSaturation.coerceIn(0f, 1f), 0f..1f) { value -> changeAudio { it.copy(dspSaturation = value) } } }
            }
            if (shows(RackNodeKind.CROSSFEED, "Crossfeed")) item {
                SettingsGroup { SettingsSliderRow("Crossfeed", "${(audio.dspCrossfeed * 100).roundToInt()}%", audio.dspCrossfeed.coerceIn(0f, 1f), 0f..1f) { value -> changeAudio { it.copy(dspCrossfeed = value) } } }
            }
            if (shows(RackNodeKind.COMPRESSOR, "Dynamics")) item {
                SettingsGroup {
                    if (legacy) SettingsSwitchRow(title = "Compressor", checked = audio.dspCompEnabled,
                        onCheckedChange = { value -> changeAudio { it.copy(dspCompEnabled = value) } })
                    RackDbSlider("Threshold", audio.dspCompThreshDb, -40f..0f) { value -> changeAudio { it.copy(dspCompThreshDb = value) } }
                    SettingsSliderRow("Ratio", "%.1f:1".format(audio.dspCompRatio), audio.dspCompRatio.coerceIn(1f, 10f), 1f..10f) { value -> changeAudio { it.copy(dspCompRatio = value) } }
                }
            }
            if (shows(RackNodeKind.LIMITER, "Dynamics")) item {
                SettingsGroup {
                    if (legacy) SettingsSwitchRow(title = "Limiter", checked = audio.dspLimiterEnabled,
                        onCheckedChange = { value -> changeAudio { it.copy(dspLimiterEnabled = value) } })
                    RackDbSlider("Limiter ceiling", audio.dspLimiterCeilingDb, -6f..0f) { value -> changeAudio { it.copy(dspLimiterCeilingDb = value) } }
                    RackDescription("Controls sample peaks with attack and release. Place a separate Limiter stage last to limit the signal after convolution.")
                }
            }
            if (shows(RackNodeKind.DELAY, "Delay")) item {
                SettingsGroup {
                    SettingsSliderRow("Left delay", "%.1f ms".format(audio.dspDelayLeftMs), audio.dspDelayLeftMs.coerceIn(0f, 20f), 0f..20f) { value -> changeAudio { it.copy(dspDelayLeftMs = value) } }
                    SettingsSliderRow("Right delay", "%.1f ms".format(audio.dspDelayRightMs), audio.dspDelayRightMs.coerceIn(0f, 20f), 0f..20f) { value -> changeAudio { it.copy(dspDelayRightMs = value) } }
                }
            }
            if (node.kind == RackNodeKind.CONVOLUTION) item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.FolderOpen, "Shared impulse response", globalAudio.dspConvIrName.ifBlank { "Choose a WAV impulse response" }) { if (!importingIr) onPickImpulse() }
                    if (importingIr) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    RackDescription("The rack supports one convolution stage. Its selected WAV is shared with standard convolution and is included in processing preset exports.")
                    RackDbSlider("Makeup gain", audio.dspConvMakeupDb, -12f..12f) { value -> changeAudio { it.copy(dspConvMakeupDb = value) } }
                }
            }
        }
    }
    bandEdit?.let { index ->
        (if (index == -1) ParamBand(1_000f, 0f, 1f) else audio.dspParametric.getOrNull(index))?.let { band ->
            RackBandDialog(band, if (index < 0) "New parametric band" else "Band ${index + 1}", onDismiss = { bandEdit = null }) { updated ->
                changeAudio { old -> old.copy(dspParametric = if (index == -1) old.dspParametric + updated
                    else old.dspParametric.mapIndexed { i, value -> if (i == index) updated else value }) }
                bandEdit = null
            }
        }
    }
}

@Composable
private fun RackBandRow(index: Int, band: ParamBand, count: Int, canDuplicate: Boolean,
    onEdit: () -> Unit, onMove: (Int) -> Unit, onDuplicate: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 14.dp)) {
                Text("${index + 1}. ${rackFrequency(band.freqHz)}", style = MaterialTheme.typography.titleSmall)
                Text("${rackFilterLabel(band.type)} · ${rackDb(band.gainDb)} · Q %.2f".format(band.q),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit band ${index + 1}") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for band ${index + 1}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = { menu = false; onMove(-1) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = index + 1 < count, onClick = { menu = false; onMove(1) })
                    DropdownMenuItem(text = { Text("Duplicate") }, enabled = canDuplicate, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun RackBandDialog(band: ParamBand, title: String, onDismiss: () -> Unit, onSave: (ParamBand) -> Unit) {
    var frequency by remember { mutableStateOf(band.freqHz.toString()) }
    var gain by remember { mutableStateOf(band.gainDb.toString()) }
    var q by remember { mutableStateOf(band.q.toString()) }
    var type by remember { mutableIntStateOf(band.type) }
    var typeMenu by remember { mutableStateOf(false) }
    fun parsed(value: String) = value.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
    fun valid(value: Float?, original: Float, range: ClosedFloatingPointRange<Float>) = value != null && (value == original || value in range)
    val f = parsed(frequency); val g = parsed(gain); val quality = parsed(q)
    val frequencyValid = valid(f, band.freqHz, 20f..20_000f)
    val gainValid = valid(g, band.gainDb, -15f..15f)
    val qValid = valid(quality, band.q, 0.3f..8f)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Filter: ${rackFilterLabel(type)}", Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, "Choose filter type")
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    listOf("Peak", "Low shelf", "High shelf").forEachIndexed { value, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { type = value; typeMenu = false })
                    }
                }
            }
            OutlinedTextField(frequency, { frequency = it }, label = { Text("Frequency · Hz") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !frequencyValid, supportingText = { Text("20–20,000 Hz") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(gain, { gain = it }, label = { Text("Gain · dB") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !gainValid, supportingText = { Text("−15 to +15 dB") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = { IconButton(onClick = { gain = if (gain.startsWith("-")) gain.drop(1) else "-$gain" }) {
                    Icon(Icons.Filled.Exposure, "Switch gain between boost and cut")
                } })
            OutlinedTextField(q, { q = it }, label = { Text("Q") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !qValid, supportingText = { Text("0.3–8") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }, confirmButton = { TextButton(onClick = { onSave(ParamBand(requireNotNull(f), requireNotNull(g), requireNotNull(quality), type)) },
        enabled = frequencyValid && gainValid && qValid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RackNameDialog(initial: String, title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RackDescription(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun RackDbSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) =
    SettingsSliderRow(label, rackDb(value), value.coerceIn(range), range, onValueChange = onChange)

private fun ProcessingRack.parametricBandCount(): Int = nodes.filter { it.kind == RackNodeKind.EQ || it.kind == RackNodeKind.LEGACY_DSP }.sumOf { it.audio.dspParametric.size }
private fun rackDb(value: Float) = "%+.1f dB".format(value)
private fun rackFrequency(value: Float) = if (value >= 1_000f) "%.2f kHz".format(value / 1_000) else "%.0f Hz".format(value)
private fun rackFilterLabel(type: Int) = when (type) { 1 -> "Low shelf"; 2 -> "High shelf"; else -> "Peak" }
private fun RackNodeKind.label(): String = when (this) {
    RackNodeKind.LEGACY_DSP -> "Legacy DSP block"; RackNodeKind.GAIN -> "Gain"; RackNodeKind.EQ -> "Equalizer"
    RackNodeKind.SATURATION -> "Saturation"; RackNodeKind.STEREO -> "Stereo & trim"; RackNodeKind.CROSSFEED -> "Crossfeed"
    RackNodeKind.COMPRESSOR -> "Compressor"; RackNodeKind.LIMITER -> "Limiter"; RackNodeKind.DELAY -> "Channel delay"
    RackNodeKind.CONVOLUTION -> "Convolution"
}
private fun ProcessingRackNode.summary(): String = when (kind) {
    RackNodeKind.LEGACY_DSP -> "Original effect order"
    RackNodeKind.GAIN -> rackDb(audio.dspPreampDb)
    RackNodeKind.EQ -> "${audio.dspParametric.size} parametric bands · ${eqChannel.name.lowercase()}"
    RackNodeKind.SATURATION -> "${(audio.dspSaturation * 100).roundToInt()}% drive"
    RackNodeKind.STEREO -> "%.2f× width".format(audio.dspWidth)
    RackNodeKind.CROSSFEED -> "${(audio.dspCrossfeed * 100).roundToInt()}%"
    RackNodeKind.COMPRESSOR -> "%.1f:1 · %s".format(audio.dspCompRatio, rackDb(audio.dspCompThreshDb))
    RackNodeKind.LIMITER -> "Ceiling ${rackDb(audio.dspLimiterCeilingDb)}"
    RackNodeKind.DELAY -> "L %.1f · R %.1f ms".format(audio.dspDelayLeftMs, audio.dspDelayRightMs)
    RackNodeKind.CONVOLUTION -> "Makeup ${rackDb(audio.dspConvMakeupDb)}"
}
