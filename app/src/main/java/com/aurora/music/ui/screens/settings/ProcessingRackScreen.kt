package com.aurora.music.ui.screens.settings

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
import com.aurora.music.playback.DspCoeffBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

private data class RackSave(val version: Int, val rack: ProcessingRack, val completion: CompletableDeferred<Result<Unit>>? = null)
private data class RackNameTarget(val nodeId: String?, val name: String)
private enum class RackTemplate { LEGACY, RECOMMENDED }

@Composable
fun ProcessingRackScreen(contentPadding: PaddingValues, onBack: () -> Unit,
    onOpenPresets: () -> Unit, onOpenSignalPath: () -> Unit, onOpenTuning: () -> Unit, onOpenImpulses: () -> Unit) {
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
    var addMenu by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var importEq by remember { mutableStateOf(false) }
    var exportEq by remember { mutableStateOf<ProcessingRackNode?>(null) }
    var routingTarget by remember { mutableStateOf<String?>(null) }
    var subchainsOpen by remember { mutableStateOf(false) }
    val subchains by store.rackSubchains.collectAsStateWithLifecycle(initialValue = emptyList())
    val impulseLibrary by store.impulseLibrary.collectAsStateWithLifecycle(initialValue = emptyList())
    val rackAssets by store.processingRackAssets.collectAsStateWithLifecycle(initialValue = emptyList())
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
                    contentPadding, onBack = { editingId = null },
                    onEdit = { transform -> changeNode(edited.id, transform) },
                    onRename = { nameTarget = RackNameTarget(edited.id, edited.name) },
                    onPickImpulse = { leave(onOpenImpulses) },
                    decoderRate = signalPath.decoder.format?.rateHz?.takeIf { signalPath.active && it in 8_000..768_000 },
                    onExportEq = { exportEq = edited },
                    onRouting = { routingTarget = edited.id }, impulseLibrary = (impulseLibrary + rackAssets).distinctBy { it.id },
                    nodeMeter = signalPath.nodeMeters.firstOrNull { it.id == edited.id })
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
                                SettingsSwitchRow(title = "Automatic headroom", subtitle = "Reduce input gain when the rack boosts the signal.",
                                    checked = current.autoHeadroom, onCheckedChange = { enabled -> change { it.copy(autoHeadroom = enabled) } })
                                SettingsNavRow(Icons.Filled.AccountTree, "Output mix", if (current.output == null) "Last stage" else "${current.output.size} inputs") { routingTarget = "output" }
                                RackDescription(if (current.enabled)
                                    "Stages run from top to bottom."
                                else "Select Rack to use these stages.")
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(current.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text("${current.nodes.size}/16 stages · ${current.parametricBandCount()}/256 parametric bands",
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
                                canDuplicate = current.nodes.size < 16 && (node.kind != RackNodeKind.CONVOLUTION || current.nodes.count { it.kind == RackNodeKind.CONVOLUTION } < 4) &&
                                    (node.kind !in listOf(RackNodeKind.EQ, RackNodeKind.LEGACY_DSP) ||
                                        current.parametricBandCount() + node.audio.dspParametric.size <= ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS),
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
                                            enabled = kind != RackNodeKind.CONVOLUTION || current.nodes.count { it.kind == kind } < 4,
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
                                SettingsNavRow(Icons.Filled.FileDownload, "Import EQ text", "Load filters and preamp") {
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
                                SettingsNavRow(Icons.Filled.Layers, "Saved subchains", "Save or append stages") { subchainsOpen = true }
                                SettingsRowDivider()
                                SettingsDestinationRow(Icons.Filled.Bookmark, SettingsDestinations.processingPresets, onClick = { leave(onOpenPresets) })
                                SettingsRowDivider()
                                SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath, onClick = { leave(onOpenSignalPath) })
                            }
                            RackDescription(if (requestedVersion != savedVersion) "Saving changes…" else
                                "Changes save automatically.")
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
            runCatching { exportEqLauncher.launch("$filename.${if (text.trimStart().startsWith("{")) "json" else "txt"}") }.onSuccess { exportEq = null }.onFailure {
                pendingEqExport = null
                scope.launch { snackbar.showSnackbar("No document picker is available.") }
            }
        }, onOpenPresets = { exportEq = null; leave(onOpenPresets) })
    }
    routingTarget?.let { target -> current?.let { value ->
        val index = value.nodes.indexOfFirst { it.id == target }
        val output = target == "output"
        if (output || index >= 0) RackRoutingDialog(if (output) "Output mix" else "Stage inputs",
            if (output) value.nodes else value.nodes.take(index),
            if (output) value.output else value.nodes[index].inputs,
            onDismiss = { routingTarget = null }, onSave = { inputs ->
                change { rack -> if (output) rack.copy(output = inputs) else rack.copy(nodes = rack.nodes.map { if (it.id == target) it.copy(inputs = inputs) else it }) }
                routingTarget = null
            })
    } }
    if (subchainsOpen && current != null) RackSubchainsDialog(current, subchains,
        onDismiss = { subchainsOpen = false },
        onSave = { chain -> scope.launch { store.saveRackSubchain(chain).onFailure { snackbar.showSnackbar(it.message ?: "Could not save subchain.") } } },
        onDelete = { id -> scope.launch { store.deleteRackSubchain(id) } },
        onAppend = { chain -> scope.launch {
            snapshotFlow { requestedVersion == savedVersion }.first { it }
            store.appendRackSubchain(chain).onSuccess { subchainsOpen = false }
                .onFailure { snackbar.showSnackbar(it.message ?: "Could not append subchain.") }
        } })
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
    padding: PaddingValues, onBack: () -> Unit,
    onEdit: ((ProcessingRackNode) -> ProcessingRackNode) -> Unit, onRename: () -> Unit, onPickImpulse: () -> Unit,
    decoderRate: Int?, onExportEq: () -> Unit, onRouting: () -> Unit,
    impulseLibrary: List<com.aurora.music.data.ir.ImpulseLibraryEntry>, nodeMeter: com.aurora.music.playback.engine.RackNodeMeter?) {
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
                    SettingsNavRow(Icons.Filled.AccountTree, "Stage inputs", if (node.inputs == null) "Previous stage" else "${node.inputs.size} inputs", onClick = onRouting)
                    TextButton(onClick = onRename, modifier = Modifier.padding(start = 12.dp)) { Text("Rename stage") }
                }
            }
            item { RackAdvancedControls(node, onEdit, nodeMeter, decoderRate) }
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
                    RackDescription("${audio.dspParametric.size}/${if (legacy) 12 else 64} bands · $totalBands/256 across rack")
                }
                itemsIndexed(audio.dspParametric) { index, band ->
                    RackBandRow(index, band, audio.dspParametric.size, canDuplicate = totalBands < ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS && audio.dspParametric.size < (if (legacy) 12 else 64),
                        onEdit = { bandEdit = index }, onToggle = { changeAudio { old -> old.copy(dspParametric = old.dspParametric.mapIndexed { i, b -> if (i == index) b.copy(enabled = !b.isEnabled) else b }) } }, onMove = { step -> changeAudio { old ->
                            val list = old.dspParametric.toMutableList()
                            if (index in list.indices && index + step in list.indices) list.add(index + step, list.removeAt(index))
                            old.copy(dspParametric = list)
                        } }, onDuplicate = { changeAudio { old -> old.copy(dspParametric = old.dspParametric.toMutableList().also { it.add(index + 1, band) }) } },
                        onRemove = { changeAudio { old -> old.copy(dspParametric = old.dspParametric.filterIndexed { i, _ -> i != index }) } })
                }
                item {
                    OutlinedButton(onClick = { bandEdit = -1 }, enabled = totalBands < ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS && audio.dspParametric.size < (if (legacy) 12 else 64),
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
                    RackDescription("Place a Limiter last to protect the final output.")
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
                    SettingsNavRow(Icons.Filled.FolderOpen, "Impulse library", globalAudio.dspConvIrName.ifBlank { "Select a WAV" }, onClick = onPickImpulse)
                    RackImpulsePicker(node, impulseLibrary, onEdit)
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
    onEdit: () -> Unit, onToggle: () -> Unit, onMove: (Int) -> Unit, onDuplicate: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 14.dp)) {
                val type = band.filterType
                Text("${index + 1}. ${if (type == FilterType.CUSTOM_BIQUAD) type.label else rackFrequency(band.freqHz)}",
                    style = MaterialTheme.typography.titleSmall)
                Text(buildList {
                    add(if (type == FilterType.CUSTOM_BIQUAD) "Normalized coefficients" else type.label)
                    if (type.hasGain) add(rackDb(band.gainDb))
                    if (type.hasQ) add("Q %.2f".format(band.q))
                    if (type.orders.size > 1) add("${band.filterOrder * 6} dB/oct")
                    if (!band.isEnabled) add("Bypassed")
                }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit band ${index + 1}") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for band ${index + 1}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (band.isEnabled) "Bypass" else "Enable") }, onClick = { menu = false; onToggle() })
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
internal fun RackBandDialog(band: ParamBand, title: String, onDismiss: () -> Unit, onSave: (ParamBand) -> Unit) {
    var frequency by remember { mutableStateOf(band.freqHz.toString()) }
    var gain by remember { mutableStateOf(band.gainDb.toString()) }
    var q by remember { mutableStateOf(band.q.toString()) }
    var type by remember { mutableIntStateOf(band.type) }
    var typeMenu by remember { mutableStateOf(false) }
    var order by remember { mutableIntStateOf(band.filterOrder) }
    var enabled by remember { mutableStateOf(band.isEnabled) }
    val filterType = FilterType.fromLegacy(type)
    val custom = filterType == FilterType.CUSTOM_BIQUAD
    var coefficientText by remember { mutableStateOf((band.coefficients ?: listOf(1.0, 0.0, 0.0, 0.0, 0.0)).map(Double::toString)) }
    val coefficients = coefficientText.map { it.toDoubleOrNull() }
    val coefficientError = if (custom) runCatching {
        ParamBandCodec.validateCoefficients(coefficients.map { requireNotNull(it) { "Enter five valid numbers." } })
    }.exceptionOrNull()?.message else null
    fun parsed(value: String) = value.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
    fun valid(value: Float?, original: Float, range: ClosedFloatingPointRange<Float>) = value != null && (value == original || value in range)
    val f = parsed(frequency); val g = parsed(gain); val quality = parsed(q)
    val frequencyValid = valid(f, band.freqHz, 10f..24_000f)
    val gainValid = !filterType.hasGain || valid(g, band.gainDb, -30f..30f)
    val qValid = !filterType.hasQ || valid(quality, band.q, 0.1f..100f)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Filter: ${rackFilterLabel(type)}", Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, "Choose filter type")
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    FilterType.entries.forEach { value ->
                        DropdownMenuItem(text = { Text(value.label) }, onClick = { type = value.code; order = value.orders.first(); typeMenu = false })
                    }
                }
            }
            if (!custom) OutlinedTextField(frequency, { frequency = it }, label = { Text("Frequency · Hz") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !frequencyValid, supportingText = { Text("10-24,000 Hz") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (filterType.hasGain) OutlinedTextField(gain, { gain = it }, label = { Text(if (filterType == FilterType.TILT) "High-to-low tilt · dB" else "Gain · dB") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !gainValid, supportingText = { Text("-30 to +30 dB") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = { IconButton(onClick = { gain = if (gain.startsWith("-")) gain.drop(1) else "-$gain" }) {
                    Icon(Icons.Filled.Exposure, "Switch gain between boost and cut")
                } })
            if (filterType.hasQ) OutlinedTextField(q, { q = it }, label = { Text("Q") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !qValid, supportingText = { Text("0.1-100") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (filterType.orders.size > 1) {
                SegmentedRow("Slope", filterType.orders.map { "${it * 6} dB/oct" }, filterType.orders.indexOf(order)) { index -> order = filterType.orders[index] }
            }
            if (custom) {
                Text("Normalized coefficients; a0 = 1. Values stay fixed when the sample rate changes.", style = MaterialTheme.typography.bodySmall)
                listOf("b0", "b1", "b2", "a1", "a2").forEachIndexed { index, label ->
                    OutlinedTextField(coefficientText[index], { value -> coefficientText = coefficientText.mapIndexed { i, old -> if (i == index) value else old } },
                        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        trailingIcon = { IconButton(onClick = { coefficientText = coefficientText.mapIndexed { i, value ->
                            if (i != index) value else if (value.startsWith("-")) value.drop(1) else "-$value"
                        } }) { Icon(Icons.Filled.Exposure, "Change coefficient sign") } })
                }
                coefficientError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            SettingsSwitchRow(title = "Enabled", checked = enabled, onCheckedChange = { enabled = it })
        }
    }, confirmButton = { TextButton(onClick = { onSave(ParamBand(requireNotNull(f), if (filterType.hasGain) requireNotNull(g) else 0f,
        if (filterType.hasQ) requireNotNull(quality) else .70710677f, type, filterType.id, enabled, order,
        if (custom) coefficients.map { requireNotNull(it) } else null)) },
        enabled = frequencyValid && gainValid && qValid && coefficientError == null) { Text("Save") } },
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
private fun rackFilterLabel(type: Int) = FilterType.fromLegacy(type).label
private fun bandSummary(band: ParamBand): String = buildString {
    append(band.filterType.label)
    if (!band.isEnabled) append(" · Bypassed")
    if (band.filterType.hasGain) append(" · ${rackDb(band.gainDb)}")
    if (band.filterType.hasQ) append(" · Q %.2f".format(band.q))
    else if (band.filterType.orders.size > 1) append(" · ${band.filterOrder * 6} dB/oct")
}
private fun RackNodeKind.label(): String = when (this) {
    RackNodeKind.LEGACY_DSP -> "Legacy DSP block"; RackNodeKind.GAIN -> "Gain"; RackNodeKind.EQ -> "Equalizer"
    RackNodeKind.SATURATION -> "Saturation"; RackNodeKind.STEREO -> "Stereo & trim"; RackNodeKind.CROSSFEED -> "Crossfeed"
    RackNodeKind.COMPRESSOR -> "Compressor"; RackNodeKind.LIMITER -> "Limiter"; RackNodeKind.DELAY -> "Channel delay"
    RackNodeKind.CONVOLUTION -> "Convolution"
    RackNodeKind.UTILITY -> "Channel utility"
    RackNodeKind.DYNAMIC_EQ -> "Dynamic equalizer"
    RackNodeKind.MULTIBAND -> "Multiband compressor"
    RackNodeKind.LOUDNESS -> "Adaptive loudness"
    RackNodeKind.ALIGNMENT_DELAY -> "Alignment delay"
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
    RackNodeKind.UTILITY -> "Matrix, polarity and mono bass"
    RackNodeKind.DYNAMIC_EQ -> "%.0f Hz · %.1f dB range".format((dynamic ?: RackDynamicEq()).frequencyHz, (dynamic ?: RackDynamicEq()).dynamics.rangeDb)
    RackNodeKind.MULTIBAND -> "Three linked stereo bands"
    RackNodeKind.LOUDNESS -> "Relative volume compensation"
    RackNodeKind.ALIGNMENT_DELAY -> "%.1f ms".format((utility ?: RackUtility()).delayMs)
}
