package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
    val saves = remember { Channel<RackSave>(Channel.UNLIMITED) }
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
        for (first in saves) {
            var latest = first
            val completions = mutableListOf<CompletableDeferred<Result<Unit>>>()
            first.completion?.let(completions::add)
            while (true) {
                val next = saves.tryReceive().getOrNull() ?: break
                latest = next
                next.completion?.let(completions::add)
            }
            val result = store.setProcessingRack(latest.rack)
            if (latest.version == requestedVersion) {
                savedVersion = latest.version
                if (result.isFailure) {
                    rack = store.processingRack.first()
                    scope.launch { snackbar.showSnackbar(result.exceptionOrNull()?.message ?: appString(R.string.text_could_not_save_the_rack_5f8b53)) }
                }
            }
            completions.forEach { it.complete(result) }
        }
    }
    fun change(transform: (ProcessingRack) -> ProcessingRack) {
        val current = rack ?: return
        val next = runCatching { ProcessingRackCodec.validate(transform(current)) }
        next.onSuccess {
            rack = it
            requestedVersion++
            saves.trySend(RackSave(requestedVersion, it))
        }.onFailure { error -> scope.launch { snackbar.showSnackbar(error.message ?: appString(R.string.text_could_not_update_the_rack_55efb1)) } }
    }
    fun changeNode(id: String, transform: (ProcessingRackNode) -> ProcessingRackNode) {
        change { current -> current.copy(nodes = current.nodes.map { if (it.id == id) transform(it) else it }) }
    }
    suspend fun appendEq(profile: ParsedEq, name: String): Result<Unit> {
        snapshotFlow { requestedVersion == savedVersion }.first { it }
        val current = rack ?: return Result.failure(IllegalStateException(appString(R.string.text_the_rack_is_still_loading_0720e5)))
        val next = RackEqTextCodec.appendToRack(current, profile, name).getOrElse { return Result.failure(it) }
        val completion = CompletableDeferred<Result<Unit>>()
        requestedVersion++
        rack = next
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
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error(appString(R.string.text_could_not_open_the_selected_location_988ef0))
                    output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                snackbar.showSnackbar(appString(R.string.text_parametric_eq_text_exported_257cfa))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: appString(R.string.text_could_not_export_eq_text_27ecd7)) }
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
                SettingsTopBar(appString(R.string.text_processing_rack_f7dff1)) { leave(onBack) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (current == null) item { Text(appString(R.string.text_loading_your_rack_cb2909), Modifier.padding(20.dp)) }
                    else {
                        item {
                            SettingsGroup {
                                SegmentedRow(appString(R.string.text_processing_mode_7f4d30), listOf(appString(R.string.text_standard_2dfa66), appString(R.string.text_rack_f93caa)), if (current.enabled) 1 else 0) { choice ->
                                    change { it.copy(enabled = choice == 1) }
                                }
                                SettingsSwitchRow(title = appString(R.string.text_automatic_headroom_830667), subtitle = appString(R.string.text_reduce_input_gain_when_the_rack_boosts_the_signal_2a95ae),
                                    checked = current.autoHeadroom, onCheckedChange = { enabled -> change { it.copy(autoHeadroom = enabled) } })
                                SettingsNavRow(Icons.Filled.AccountTree, appString(R.string.text_output_mix_a04a0e), if (current.output == null) appString(R.string.text_last_stage_58b1e2) else appString(R.string.text_inputs_43db9d, (current.output.size))) { routingTarget = "output" }
                                RackDescription(if (current.enabled)
                                    appString(R.string.text_stages_run_from_top_to_bottom_008582)
                                else appString(R.string.text_select_rack_to_use_these_stages_e161dd))
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(current.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(appString(R.string.text_16_stages_256_parametric_bands_cc3e87, (current.nodes.size), (current.parametricBandCount())),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { nameTarget = RackNameTarget(null, current.name) }) {
                                    Icon(Icons.Filled.Edit, appString(R.string.text_rename_rack_519beb))
                                }
                            }
                        }
                        itemsIndexed(current.nodes, key = { _, node -> node.id }) { index, node ->
                            RackNodeCard(node, index, current.nodes.size, onEdit = { editingId = node.id },
                                onBypass = { bypass -> changeNode(node.id) { it.copy(bypass = bypass) } },
                                onMove = { moveNode(node.id, it) },
                                onRename = { nameTarget = RackNameTarget(node.id, node.name) },
                                canDuplicate = current.nodes.size < 16 && (node.kind !in listOf(RackNodeKind.CONVOLUTION, RackNodeKind.SPACE) || current.nodes.count { it.kind == node.kind } < 4) &&
                                    (node.kind !in listOf(RackNodeKind.EQ, RackNodeKind.LEGACY_DSP) ||
                                        current.parametricBandCount() + node.audio.dspParametric.size <= ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS),
                                onDuplicate = { change { it.copy(nodes = it.nodes + node.copy(
                                    id = UUID.randomUUID().toString(), name = appString(R.string.text_copy_37e469, (node.name.take(70))))) } },
                                onRemove = { removeTarget = node })
                        }
                        item {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                Button(onClick = { addMenu = true }, enabled = current.nodes.size < 16, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Filled.Add, null)
                                    Text(appString(R.string.text_add_stage_931c98), Modifier.padding(start = 8.dp))
                                }
                                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false },
                                    modifier = Modifier.heightIn(max = 400.dp)) {
                                    RackNodeKind.entries.forEach { kind ->
                                        DropdownMenuItem(text = { Text(kind.label()) },
                                            enabled = kind !in listOf(RackNodeKind.CONVOLUTION, RackNodeKind.SPACE) || current.nodes.count { it.kind == kind } < 4,
                                            onClick = {
                                                addMenu = false
                                                val node = ProcessingRackNode(UUID.randomUUID().toString(), kind.label(), kind,
                                                    wet = if (kind == RackNodeKind.SPACE) .25f else 1f,
                                                    audio = AudioPrefs(dspLimiterEnabled = false, dspLimiterCeilingDb = -1f))
                                                change { it.copy(nodes = it.nodes + node) }
                                                editingId = node.id
                                            })
                                    }
                                }
                            }
                            if (current.nodes.isEmpty()) RackDescription(appString(R.string.text_add_a_stage_to_begin_an_empty_rack_passes_audio_through_5edd41))
                        }
                        item {
                            SettingsSectionTitle(appString(R.string.text_starting_points_8df66e))
                            SettingsGroup {
                                SettingsDestinationRow(Icons.Filled.ShowChart, SettingsDestinations.tuning, onClick = { leave(onOpenTuning) })
                                SettingsRowDivider()
                                SettingsNavRow(Icons.Filled.FileDownload, appString(R.string.text_import_eq_text_337ca7), appString(R.string.text_load_filters_and_preamp_ae1cab)) {
                                    importEq = true
                                }
                                SettingsRowDivider()
                                SettingsNavRow(Icons.Filled.History, appString(R.string.text_copy_standard_settings_d03497), appString(R.string.text_copy_software_dsp_and_current_channel_settings_7c4573)) {
                                    template = RackTemplate.LEGACY
                                }
                                SettingsRowDivider()
                                SettingsNavRow(Icons.Filled.AutoAwesome, appString(R.string.text_recommended_order_642c4b), appString(R.string.text_separate_stages_with_a_final_limiter_after_convolution_32ae6f)) {
                                    template = RackTemplate.RECOMMENDED
                                }
                            }
                        }
                        item {
                            SettingsSectionTitle(appString(R.string.text_save_inspect_3831d9))
                            SettingsGroup {
                                SettingsNavRow(Icons.Filled.Layers, appString(R.string.text_saved_subchains_46c948), appString(R.string.text_save_or_append_stages_5f0cc3)) { subchainsOpen = true }
                                SettingsRowDivider()
                                SettingsDestinationRow(Icons.Filled.Bookmark, SettingsDestinations.processingPresets, onClick = { leave(onOpenPresets) })
                                SettingsRowDivider()
                                SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath, onClick = { leave(onOpenSignalPath) })
                            }
                            RackDescription(if (requestedVersion != savedVersion) appString(R.string.text_saving_changes_804053) else
                                appString(R.string.text_changes_save_automatically_b3fdb8))
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
                scope.launch { snackbar.showSnackbar(appString(R.string.text_no_document_picker_is_available_a8fedf)) }
            }
        }, onOpenPresets = { exportEq = null; leave(onOpenPresets) })
    }
    routingTarget?.let { target -> current?.let { value ->
        val index = value.nodes.indexOfFirst { it.id == target }
        val output = target == "output"
        if (output || index >= 0) RackRoutingDialog(if (output) appString(R.string.text_output_mix_a04a0e) else appString(R.string.text_stage_inputs_c5a9f4),
            if (output) value.nodes else value.nodes.take(index),
            if (output) value.output else value.nodes[index].inputs,
            onDismiss = { routingTarget = null }, onSave = { inputs ->
                change { rack -> if (output) rack.copy(output = inputs) else rack.copy(nodes = rack.nodes.map { if (it.id == target) it.copy(inputs = inputs) else it }) }
                routingTarget = null
            })
    } }
    if (subchainsOpen && current != null) RackSubchainsDialog(current, subchains,
        onDismiss = { subchainsOpen = false },
        onSave = { chain -> scope.launch { store.saveRackSubchain(chain).onFailure { snackbar.showSnackbar(it.message ?: appString(R.string.text_could_not_save_subchain_03c16f)) } } },
        onDelete = { id -> scope.launch { store.deleteRackSubchain(id) } },
        onAppend = { chain -> scope.launch {
            snapshotFlow { requestedVersion == savedVersion }.first { it }
            store.appendRackSubchain(chain).onSuccess { subchainsOpen = false }
                .onFailure { snackbar.showSnackbar(it.message ?: appString(R.string.text_could_not_append_subchain_b7d429)) }
        } })
    nameTarget?.let { target ->
        RackNameDialog(target.name, if (target.nodeId == null) appString(R.string.text_rename_rack_519beb) else appString(R.string.text_rename_stage_1df1d4), onDismiss = { nameTarget = null }) { name ->
            if (target.nodeId == null) change { it.copy(name = name) }
            else changeNode(target.nodeId) { it.copy(name = name) }
            nameTarget = null
        }
    }
    removeTarget?.let { target ->
        AlertDialog(onDismissRequest = { removeTarget = null }, title = { Text(appString(R.string.text_remove_436e1a, (target.name))) },
            text = { Text(appString(R.string.text_this_removes_the_stage_and_its_settings_from_this_rack_03575f)) },
            confirmButton = { TextButton(onClick = {
                change { it.copy(nodes = it.nodes.filterNot { node -> node.id == target.id }) }
                removeTarget = null
            }) { Text(appString(R.string.text_remove_e96390)) } }, dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(appString(R.string.text_cancel_77dfd2)) } })
    }
    template?.let { selected ->
        AlertDialog(onDismissRequest = { template = null }, title = { Text(appString(R.string.text_replace_this_rack_957859)) },
            text = { Text(if (selected == RackTemplate.LEGACY)
                appString(R.string.text_copy_your_standard_software_eq_effects_and_mono_setting_into_an_e_4eccb4) +
                    if (audio.dspMode == DspMode.SYSTEM) appString(R.string.text_android_system_eq_is_separate_and_is_not_copied_enabling_the_rack_15b92e) else ""
            else appString(R.string.text_copy_your_standard_sound_settings_into_separate_stages_and_place_61d4ee)) },
            confirmButton = { TextButton(onClick = {
                change { old -> (if (selected == RackTemplate.LEGACY) ProcessingRack.legacy(audio, playback.monoAudio)
                    else ProcessingRack.recommended(audio, playback.monoAudio)).copy(enabled = old.enabled) }
                template = null
            }) { Text(appString(R.string.text_replace_rack_d12c1a)) } }, dismissButton = { TextButton(onClick = { template = null }) { Text(appString(R.string.text_cancel_77dfd2)) } })
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
                Text(if (node.bypass) appString(R.string.text_bypassed_7a944b) else appString(R.string.text_wet_1df0cd, (node.summary()), ((node.wet * 100).roundToInt())),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, appString(R.string.text_actions_for_cf6f1b, (node.name))) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(appString(R.string.text_edit_settings_aea819)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_rename_d3f4cb)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_duplicate_972d57)) }, enabled = canDuplicate, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_remove_e96390)) }, onClick = { menu = false; onRemove() })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(Icons.Filled.ArrowUpward, appString(R.string.text_move_up_a55bd8, (node.name))) }
            IconButton(onClick = { onMove(1) }, enabled = index + 1 < count) { Icon(Icons.Filled.ArrowDownward, appString(R.string.text_move_down_533269, (node.name))) }
            TextButton(onClick = onEdit) { Text(appString(R.string.text_edit_530164)) }
            Spacer(Modifier.weight(1f))
            Text(appString(R.string.text_bypass_497852), style = MaterialTheme.typography.labelMedium)
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
    var legacySection by rememberSaveable(node.id) { mutableStateOf(appString(R.string.text_equalizer_3b64a9)) }
    var sectionMenu by remember { mutableStateOf(false) }
    var bandEdit by remember { mutableStateOf<Int?>(null) }
    var showGraphic by rememberSaveable(node.id) { mutableStateOf(false) }
    var showResponse by rememberSaveable(node.id) { mutableStateOf(false) }
    fun changeAudio(transform: (AudioPrefs) -> AudioPrefs) = onEdit { it.copy(audio = transform(it.audio)) }
    fun shows(kind: RackNodeKind, section: String) = node.kind == kind || legacy && legacySection == section
    val eq = shows(RackNodeKind.EQ, appString(R.string.text_equalizer_3b64a9))
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(node.name, onBack)
        LazyColumn(Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SettingsGroup {
                    SettingsSwitchRow(title = appString(R.string.text_bypass_stage_2c4421), checked = node.bypass,
                        subtitle = if (rackEnabled) appString(R.string.text_keep_the_settings_while_passing_this_stage_unchanged_4d9857) else appString(R.string.text_this_rack_is_currently_inactive_f790d2),
                        onCheckedChange = { bypass -> onEdit { it.copy(bypass = bypass) } })
                    SettingsSliderRow(appString(R.string.text_wet_dry_6769a1), appString(R.string.text_wet_76d013, ((node.wet * 100).roundToInt())), node.wet, 0f..1f) { wet -> onEdit { it.copy(wet = wet) } }
                    SettingsNavRow(Icons.Filled.AccountTree, appString(R.string.text_stage_inputs_c5a9f4), if (node.inputs == null) appString(R.string.text_previous_stage_252786) else appString(R.string.text_inputs_43db9d, (node.inputs.size)), onClick = onRouting)
                    TextButton(onClick = onRename, modifier = Modifier.padding(start = 12.dp)) { Text(appString(R.string.text_rename_stage_1df1d4)) }
                }
            }
            item { RackAdvancedControls(node, onEdit, nodeMeter, decoderRate) }
            if (legacy) item {
                SettingsGroup {
                    Box {
                        SettingsNavRow(Icons.Filled.Tune, appString(R.string.text_legacy_dsp_settings_9a91d5), legacySection) { sectionMenu = true }
                        DropdownMenu(expanded = sectionMenu, onDismissRequest = { sectionMenu = false }) {
                            listOf(appString(R.string.text_equalizer_3b64a9), appString(R.string.text_gain_96dd91), appString(R.string.text_stereo_f4f390), appString(R.string.text_crossfeed_e6b7b4), appString(R.string.text_saturation_20a32b), appString(R.string.text_dynamics_7d5536), appString(R.string.text_delay_b4c200)).forEach { section ->
                                DropdownMenuItem(text = { Text(section) }, onClick = { legacySection = section; sectionMenu = false })
                            }
                        }
                    }
                    RackDescription(appString(R.string.text_these_settings_belong_to_this_stage_the_original_eq_gain_saturati_989a35))
                }
            }
            if (eq) {
                if (!legacy) {
                    item {
                        SettingsGroup {
                            SegmentedRow(appString(R.string.text_channels_18e03e), listOf(appString(R.string.text_both_1f4698), appString(R.string.text_left_8ae1c3), appString(R.string.text_right_954daa)), node.eqChannel.ordinal) { selected ->
                                onEdit { it.copy(eqChannel = RackEqChannel.entries[selected]) }
                            }
                            SettingsNavRow(Icons.Filled.ShowChart, appString(R.string.text_calculated_response_a4a732), if (showResponse) appString(R.string.text_hide_graph_0a1c33) else appString(R.string.text_magnitude_phase_and_group_delay_for_this_stage_b32404)) {
                                showResponse = !showResponse
                            }
                            SettingsRowDivider()
                            SettingsNavRow(Icons.Filled.FileUpload, appString(R.string.text_export_parametric_eq_text_39190f), appString(R.string.text_review_what_the_text_file_includes_before_saving_feb089), onClick = onExportEq)
                        }
                    }
                    if (showResponse) item { RackEqResponseCard(node, decoderRate, rackEnabled) }
                }
                val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(audio.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
                item {
                    SettingsGroup {
                        SettingsNavRow(Icons.Filled.Tune, appString(R.string.text_graphic_eq_3df203), appString(R.string.text_controls_af1fce, (layout.name), (if (showGraphic) appString(R.string.text_hide_34d8b6) else appString(R.string.text_show_d97d1e)))) { showGraphic = !showGraphic }
                        if (showGraphic) {
                            SegmentedRow(appString(R.string.text_layout_972ad8), DspCoeffBuilder.GRAPHIC_LAYOUTS.map { it.name }, audio.dspGraphicLayout) { selected ->
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
                    SettingsSectionTitle(appString(R.string.text_parametric_eq_02ed4c, (audio.dspParametric.size), (if (audio.dspParametric.size == 1) "band" else "bands")))
                    RackDescription(appString(R.string.text_bands_256_across_rack_39ea04, (audio.dspParametric.size), (if (legacy) 12 else 64), (totalBands)))
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
                        Icon(Icons.Filled.Add, null); Text(appString(R.string.text_add_parametric_band_0c91fd), Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (shows(RackNodeKind.GAIN, appString(R.string.text_gain_96dd91))) item {
                SettingsGroup { RackDbSlider(appString(R.string.text_preamp_fa89ca), audio.dspPreampDb, -12f..12f) { value -> changeAudio { it.copy(dspPreampDb = value) } } }
            }
            if (shows(RackNodeKind.STEREO, appString(R.string.text_stereo_f4f390))) item {
                SettingsGroup {
                    SettingsSliderRow(appString(R.string.text_stereo_width_336051), "%.2f×".format(audio.dspWidth), audio.dspWidth.coerceIn(0f, 2f), 0f..2f) { value -> changeAudio { it.copy(dspWidth = value) } }
                    RackDescription(appString(R.string.text_0_combines_the_channels_to_mono_1_keeps_the_stereo_width_unchange_18be43))
                    SettingsSliderRow(appString(R.string.text_balance_90eef6), if (audio.dspBalance == 0f) appString(R.string.text_center_a23911) else "${(kotlin.math.abs(audio.dspBalance) * 100).roundToInt()}% ${if (audio.dspBalance < 0f) "left" else "right"}",
                        audio.dspBalance.coerceIn(-1f, 1f), -1f..1f) { value -> changeAudio { it.copy(dspBalance = value) } }
                    RackDbSlider(appString(R.string.text_left_trim_9ad513), audio.dspTrimLeftDb, -12f..0f) { value -> changeAudio { it.copy(dspTrimLeftDb = value) } }
                    RackDbSlider(appString(R.string.text_right_trim_cadffb), audio.dspTrimRightDb, -12f..0f) { value -> changeAudio { it.copy(dspTrimRightDb = value) } }
                }
            }
            if (shows(RackNodeKind.SATURATION, appString(R.string.text_saturation_20a32b))) item {
                SettingsGroup { SettingsSliderRow(appString(R.string.text_tube_saturation_8c63f8), "${(audio.dspSaturation * 100).roundToInt()}%", audio.dspSaturation.coerceIn(0f, 1f), 0f..1f) { value -> changeAudio { it.copy(dspSaturation = value) } } }
            }
            if (shows(RackNodeKind.CROSSFEED, appString(R.string.text_crossfeed_e6b7b4))) item {
                SettingsGroup { SettingsSliderRow(appString(R.string.text_crossfeed_e6b7b4), "${(audio.dspCrossfeed * 100).roundToInt()}%", audio.dspCrossfeed.coerceIn(0f, 1f), 0f..1f) { value -> changeAudio { it.copy(dspCrossfeed = value) } } }
            }
            if (shows(RackNodeKind.COMPRESSOR, appString(R.string.text_dynamics_7d5536))) item {
                SettingsGroup {
                    if (legacy) SettingsSwitchRow(title = appString(R.string.text_compressor_b23f61), checked = audio.dspCompEnabled,
                        onCheckedChange = { value -> changeAudio { it.copy(dspCompEnabled = value) } })
                    RackDbSlider(appString(R.string.text_threshold_c51f7b), audio.dspCompThreshDb, -40f..0f) { value -> changeAudio { it.copy(dspCompThreshDb = value) } }
                    SettingsSliderRow(appString(R.string.text_ratio_794f65), "%.1f:1".format(audio.dspCompRatio), audio.dspCompRatio.coerceIn(1f, 10f), 1f..10f) { value -> changeAudio { it.copy(dspCompRatio = value) } }
                }
            }
            if (shows(RackNodeKind.LIMITER, appString(R.string.text_dynamics_7d5536))) item {
                SettingsGroup {
                    if (legacy) SettingsSwitchRow(title = appString(R.string.text_limiter_20fee6), checked = audio.dspLimiterEnabled,
                        onCheckedChange = { value -> changeAudio { it.copy(dspLimiterEnabled = value) } })
                    RackDbSlider(appString(R.string.text_limiter_ceiling_b7d8bd), audio.dspLimiterCeilingDb, -6f..0f) { value -> changeAudio { it.copy(dspLimiterCeilingDb = value) } }
                    RackDescription(appString(R.string.text_place_a_limiter_last_to_protect_the_final_output_b017dd))
                }
            }
            if (shows(RackNodeKind.DELAY, appString(R.string.text_delay_b4c200))) item {
                SettingsGroup {
                    SettingsSliderRow(appString(R.string.text_left_delay_c54844), appString(R.string.text_1f_ms_57bb04).format(audio.dspDelayLeftMs), audio.dspDelayLeftMs.coerceIn(0f, 20f), 0f..20f) { value -> changeAudio { it.copy(dspDelayLeftMs = value) } }
                    SettingsSliderRow(appString(R.string.text_right_delay_ca7838), appString(R.string.text_1f_ms_57bb04).format(audio.dspDelayRightMs), audio.dspDelayRightMs.coerceIn(0f, 20f), 0f..20f) { value -> changeAudio { it.copy(dspDelayRightMs = value) } }
                }
            }
            if (node.kind == RackNodeKind.CONVOLUTION) item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.FolderOpen, appString(R.string.text_impulse_library_ff955d), globalAudio.dspConvIrName.ifBlank { appString(R.string.text_select_a_wav_779ee2) }, onClick = onPickImpulse)
                    RackImpulsePicker(node, impulseLibrary, onEdit)
                    Text(appString(R.string.text_measured_headphone_spatial_filters_use_true_stereo_wav_impulses_f68789), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    RackDbSlider(appString(R.string.text_makeup_gain_bced0d), audio.dspConvMakeupDb, -12f..12f) { value -> changeAudio { it.copy(dspConvMakeupDb = value) } }
                }
            }
        }
    }
    bandEdit?.let { index ->
        (if (index == -1) ParamBand(1_000f, 0f, 1f) else audio.dspParametric.getOrNull(index))?.let { band ->
            RackBandDialog(band, if (index < 0) appString(R.string.text_new_parametric_band_0bc727) else appString(R.string.text_band_c6bf38, (index + 1)), onDismiss = { bandEdit = null }) { updated ->
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
                    add(if (type == FilterType.CUSTOM_BIQUAD) appString(R.string.text_normalized_coefficients_eb7bc6) else type.label)
                    if (type.hasGain) add(rackDb(band.gainDb))
                    if (type.hasQ) add("Q %.2f".format(band.q))
                    if (type.orders.size > 1) add(appString(R.string.text_db_oct_29dc5a, (band.filterOrder * 6)))
                    if (!band.isEnabled) add(appString(R.string.text_bypassed_7a944b))
                }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, appString(R.string.text_edit_band_903859, (index + 1))) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, appString(R.string.text_actions_for_band_c9bc9b, (index + 1))) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (band.isEnabled) appString(R.string.text_bypass_497852) else appString(R.string.text_enable_20063a)) }, onClick = { menu = false; onToggle() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_move_up_b4f57c)) }, enabled = index > 0, onClick = { menu = false; onMove(-1) })
                    DropdownMenuItem(text = { Text(appString(R.string.text_move_down_260ff8)) }, enabled = index + 1 < count, onClick = { menu = false; onMove(1) })
                    DropdownMenuItem(text = { Text(appString(R.string.text_duplicate_972d57)) }, enabled = canDuplicate, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_remove_e96390)) }, onClick = { menu = false; onRemove() })
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
        ParamBandCodec.validateCoefficients(coefficients.map { requireNotNull(it) { appString(R.string.text_enter_five_valid_numbers_17625b) } })
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
                    Text(appString(R.string.text_filter_4a5de1, (rackFilterLabel(type))), Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, appString(R.string.text_choose_filter_type_6698bc))
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    FilterType.entries.forEach { value ->
                        DropdownMenuItem(text = { Text(value.label) }, onClick = { type = value.code; order = value.orders.first(); typeMenu = false })
                    }
                }
            }
            if (!custom) OutlinedTextField(frequency, { frequency = it }, label = { Text(appString(R.string.text_frequency_hz_c2281f)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !frequencyValid, supportingText = { Text(appString(R.string.text_10_24_000_hz_70fbdf)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (filterType.hasGain) OutlinedTextField(gain, { gain = it }, label = { Text(if (filterType == FilterType.TILT) appString(R.string.text_high_to_low_tilt_db_1ba5c8) else appString(R.string.text_gain_db_455951)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !gainValid, supportingText = { Text(appString(R.string.text_30_to_30_db_54c909)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = { IconButton(onClick = { gain = if (gain.startsWith("-")) gain.drop(1) else "-$gain" }) {
                    Icon(Icons.Filled.Exposure, appString(R.string.text_switch_gain_between_boost_and_cut_ec1830))
                } })
            if (filterType.hasQ) OutlinedTextField(q, { q = it }, label = { Text("Q") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = !qValid, supportingText = { Text("0.1-100") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (filterType.orders.size > 1) {
                SegmentedRow(appString(R.string.text_slope_638d02), filterType.orders.map { appString(R.string.text_db_oct_29dc5a, (it * 6)) }, filterType.orders.indexOf(order)) { index -> order = filterType.orders[index] }
            }
            if (custom) {
                Text(appString(R.string.text_normalized_coefficients_a0_1_values_stay_fixed_when_the_sample_ra_d8bac7), style = MaterialTheme.typography.bodySmall)
                listOf("b0", "b1", "b2", "a1", "a2").forEachIndexed { index, label ->
                    OutlinedTextField(coefficientText[index], { value -> coefficientText = coefficientText.mapIndexed { i, old -> if (i == index) value else old } },
                        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        trailingIcon = { IconButton(onClick = { coefficientText = coefficientText.mapIndexed { i, value ->
                            if (i != index) value else if (value.startsWith("-")) value.drop(1) else "-$value"
                        } }) { Icon(Icons.Filled.Exposure, appString(R.string.text_change_coefficient_sign_8ad3f7)) } })
                }
                coefficientError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            SettingsSwitchRow(title = appString(R.string.text_enabled_df174a), checked = enabled, onCheckedChange = { enabled = it })
        }
    }, confirmButton = { TextButton(onClick = { onSave(ParamBand(requireNotNull(f), if (filterType.hasGain) requireNotNull(g) else 0f,
        if (filterType.hasQ) requireNotNull(quality) else .70710677f, type, filterType.id, enabled, order,
        if (custom) coefficients.map { requireNotNull(it) } else null)) },
        enabled = frequencyValid && gainValid && qValid && coefficientError == null) { Text(appString(R.string.text_save_efc007)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

@Composable
private fun RackNameDialog(initial: String, title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it.take(80) }, label = { Text(appString(R.string.text_name_709a23)) }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(appString(R.string.text_save_efc007)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

@Composable
private fun RackDescription(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun RackDbSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) =
    SettingsSliderRow(label, rackDb(value), value.coerceIn(range), range, onValueChange = onChange)

private fun ProcessingRack.parametricBandCount(): Int = nodes.filter { it.kind == RackNodeKind.EQ || it.kind == RackNodeKind.LEGACY_DSP }.sumOf { it.audio.dspParametric.size }
private fun rackDb(value: Float) = appString(R.string.text_1f_db_731a6b).format(value)
private fun rackFrequency(value: Float) = if (value >= 1_000f) appString(R.string.text_2f_khz_720dba).format(value / 1_000) else appString(R.string.text_0f_hz_7d94a7).format(value)
private fun rackFilterLabel(type: Int) = FilterType.fromLegacy(type).label
private fun bandSummary(band: ParamBand): String = buildString {
    append(band.filterType.label)
    if (!band.isEnabled) append(appString(R.string.text_bypassed_4b6893))
    if (band.filterType.hasGain) append(" · ${rackDb(band.gainDb)}")
    if (band.filterType.hasQ) append(" · Q %.2f".format(band.q))
    else if (band.filterType.orders.size > 1) append(appString(R.string.text_db_oct_f8e265, (band.filterOrder * 6)))
}
private fun RackNodeKind.label(): String = when (this) {
    RackNodeKind.LEGACY_DSP -> appString(R.string.text_legacy_dsp_block_cfa47f); RackNodeKind.GAIN -> appString(R.string.text_gain_96dd91); RackNodeKind.EQ -> appString(R.string.text_equalizer_3b64a9)
    RackNodeKind.SATURATION -> appString(R.string.text_saturation_20a32b); RackNodeKind.STEREO -> appString(R.string.text_stereo_trim_63a03b); RackNodeKind.CROSSFEED -> appString(R.string.text_crossfeed_e6b7b4)
    RackNodeKind.COMPRESSOR -> appString(R.string.text_compressor_b23f61); RackNodeKind.LIMITER -> appString(R.string.text_limiter_20fee6); RackNodeKind.DELAY -> appString(R.string.text_channel_delay_209025)
    RackNodeKind.CONVOLUTION -> appString(R.string.text_convolution_1c746e)
    RackNodeKind.UTILITY -> appString(R.string.text_channel_utility_8a147b)
    RackNodeKind.DYNAMIC_EQ -> appString(R.string.text_dynamic_equalizer_6f01b5)
    RackNodeKind.MULTIBAND -> appString(R.string.text_multiband_compressor_07d886)
    RackNodeKind.LOUDNESS -> appString(R.string.text_adaptive_loudness_dbbc9d)
    RackNodeKind.ALIGNMENT_DELAY -> appString(R.string.text_alignment_delay_56ff3c)
    RackNodeKind.DYNAMICS -> appString(R.string.text_gate_expander_de_esser_27c036)
    RackNodeKind.TONE -> appString(R.string.text_tone_376990)
    RackNodeKind.SPACE -> appString(R.string.text_delay_reverb_8c8bb6)
    RackNodeKind.MODULATION -> appString(R.string.text_modulation_f3c1a2)
}
private fun ProcessingRackNode.summary(): String = when (kind) {
    RackNodeKind.LEGACY_DSP -> appString(R.string.text_original_effect_order_949d7d)
    RackNodeKind.GAIN -> rackDb(audio.dspPreampDb)
    RackNodeKind.EQ -> appString(R.string.text_parametric_bands_68b3db, (audio.dspParametric.size), (eqChannel.name.lowercase()))
    RackNodeKind.SATURATION -> appString(R.string.text_drive_7a34a5, ((audio.dspSaturation * 100).roundToInt()))
    RackNodeKind.STEREO -> appString(R.string.text_2f_width_52a8cc).format(audio.dspWidth)
    RackNodeKind.CROSSFEED -> "${(audio.dspCrossfeed * 100).roundToInt()}%"
    RackNodeKind.COMPRESSOR -> "%.1f:1 · %s".format(audio.dspCompRatio, rackDb(audio.dspCompThreshDb))
    RackNodeKind.LIMITER -> appString(R.string.text_ceiling_ef2e49, (rackDb(audio.dspLimiterCeilingDb)))
    RackNodeKind.DELAY -> appString(R.string.text_l_1f_r_1f_ms_00e48f).format(audio.dspDelayLeftMs, audio.dspDelayRightMs)
    RackNodeKind.CONVOLUTION -> appString(R.string.text_makeup_ae8f55, (rackDb(audio.dspConvMakeupDb)))
    RackNodeKind.UTILITY -> appString(R.string.text_matrix_polarity_and_mono_bass_f23368)
    RackNodeKind.DYNAMIC_EQ -> appString(R.string.text_0f_hz_1f_db_range_f6e243).format((dynamic ?: RackDynamicEq()).frequencyHz, (dynamic ?: RackDynamicEq()).dynamics.rangeDb)
    RackNodeKind.MULTIBAND -> appString(R.string.text_three_linked_stereo_bands_8f36e9)
    RackNodeKind.LOUDNESS -> appString(R.string.text_relative_volume_compensation_119ef9)
    RackNodeKind.ALIGNMENT_DELAY -> appString(R.string.text_1f_ms_57bb04).format((utility ?: RackUtility()).delayMs)
    RackNodeKind.DYNAMICS -> (dynamics ?: RackDynamicsEffect()).mode.name.lowercase().replaceFirstChar { it.uppercase() }
    RackNodeKind.TONE -> (tone ?: RackTone()).mode.name.lowercase().replaceFirstChar { it.uppercase() }
    RackNodeKind.SPACE -> (space ?: RackSpace()).mode.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    RackNodeKind.MODULATION -> (modulation ?: RackModulation()).mode.name.lowercase().replaceFirstChar { it.uppercase() }
}
