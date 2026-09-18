package com.aurora.music.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackCodec
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.tuning.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun TuningProjectsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenRack: () -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as AuroraApplication).container.settingsStore
    var loadError by remember { mutableStateOf<String?>(null) }
    val projectFlow = remember(store) {
        store.tuningProjects.onEach { loadError = null }.catch {
            loadError = it.message ?: "Saved tuning projects could not be decoded."
            emit(emptyList())
        }
    }
    val projects by projectFlow.collectAsStateWithLifecycle<List<TuningProject>?>(initialValue = null)
    val rack by store.processingRack.collectAsStateWithLifecycle<ProcessingRack?>(initialValue = null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<TuningProject?>(null) }
    var baseline by remember { mutableStateOf<TuningProject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var workingText by remember { mutableStateOf("") }
    var fitJob by remember { mutableStateOf<Job?>(null) }
    var history by remember { mutableStateOf<List<TuningProject>>(emptyList()) }
    var appliedUndo by remember { mutableStateOf<Pair<ProcessingRack, ProcessingRack>?>(null) }
    var targetLibrary by remember { mutableStateOf(false) }
    var correctionImport by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<TuningProject?>(null) }
    var delete by remember { mutableStateOf<TuningProject?>(null) }
    var importSlot by remember { mutableStateOf<TuningMeasurementSlot?>(null) }
    var provenanceSlot by remember { mutableStateOf<TuningMeasurementSlot?>(null) }
    var settings by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    var leaveAfterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var importedProject by remember { mutableStateOf<TuningProject?>(null) }
    var review by remember { mutableStateOf<TuningProject?>(null) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedId, projects) {
        val saved = projects?.firstOrNull { it.id == selectedId }
        if (selectedId == null) { draft = null; baseline = null }
        else if (saved != null && (draft?.id != selectedId || draft == baseline)) { draft = saved; baseline = saved }
    }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun update(transform: (TuningProject) -> TuningProject) {
        if (busy || fitJob != null || loadError != null) return
        val original = draft ?: return
        val changed = transform(original)
        if (changed != original) history = (history + original).takeLast(10)
        val previousFit = original.generatedFit
        if (previousFit == null) { draft = changed.copy(generatedFit = null); return }
        busy = true; workingText = "Updating project…"
        scope.launch {
            try {
                val retained = withContext(Dispatchers.Default) {
                    previousFit.takeIf {
                        runCatching { it.inputFingerprint == TuningProjectCodec.inputFingerprint(changed) && it.config == changed.config }.getOrDefault(false)
                    }
                }
                if (draft == original) draft = changed.copy(generatedFit = retained)
            } finally { busy = false }
        }
    }
    fun open(project: TuningProject) { selectedId = project.id; baseline = project; draft = project; history = emptyList() }
    fun closeProject() { selectedId = null; draft = null; baseline = null }
    fun safelyLeave(action: () -> Unit) {
        if (busy) return
        if (fitJob != null) { fitJob?.cancel(); return }
        if (draft != baseline) leaveAfterSave = action else action()
    }
    suspend fun saveCurrent(project: TuningProject): TuningProject {
        check(loadError == null) { "Recover the saved project library before writing changes." }
        val saved = withContext(Dispatchers.Default) { TuningProjectCodec.validate(project.copy(updatedAtMs = System.currentTimeMillis())) }
        store.saveTuningProject(saved).getOrThrow()
        if (draft?.id == saved.id) {
            baseline = saved
            if (draft == project) draft = saved
        }
        return saved
    }
    fun perform(label: String, block: suspend () -> Unit) {
        if (busy || fitJob != null) return
        if (loadError != null) { notify("Recover the saved project library before writing changes."); return }
        busy = true; workingText = label
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: "Could not update this project.") }
            finally { busy = false }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) perform("Exporting project…") {
            val project = store.tuningProjects.first().firstOrNull { it.id == id } ?: error("This project is no longer saved.")
            withContext(Dispatchers.IO) {
                val json = TuningProjectCodec.encodeProject(project)
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the selected destination.")
                output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            notify("Project exported.")
        }
    }
    fun export(project: TuningProject) {
        perform("Saving project for export…") {
            val saved = saveCurrent(project)
            pendingExportId = saved.id
            val name = saved.name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().take(60).ifBlank { "Aurora tuning" }
            try { exportLauncher.launch("$name.json") }
            catch (failure: Exception) { pendingExportId = null; throw IllegalStateException("No document picker is available.", failure) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform("Reading project…") {
            val file = readTuningText(context, uri, TuningProjectCodec.MAX_PROJECT_BYTES)
            val imported = withContext(Dispatchers.Default) { TuningProjectCodec.decodeProject(file.first).getOrThrow() }
            val now = System.currentTimeMillis()
            importedProject = imported.copy(id = UUID.randomUUID().toString(), generatedFit = null, createdAtMs = now, updatedAtMs = now)
        }
    }
    fun generate(project: TuningProject) {
        if (busy || fitJob != null || loadError != null) return
        fitJob = scope.launch {
            try {
                val valid = withContext(Dispatchers.Default) { TuningProjectCodec.validate(project) }
                val result = TuningFitter.fit(valid, valid.config)
                val afterFit = draft
                val currentFingerprint = withContext(Dispatchers.Default) { afterFit?.let { TuningProjectCodec.inputFingerprint(it) } }
                if (draft == afterFit && currentFingerprint == result.inputFingerprint) {
                    afterFit?.let { history = (history + it).takeLast(10) }
                    draft = draft?.copy(generatedFit = result)
                }
                notify("Correction generated.")
            } catch (cancelled: CancellationException) { notify("Fitting cancelled."); throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Could not generate a correction for these inputs.") }
            finally { fitJob = null }
        }
    }
    BackHandler { if (selectedId != null) safelyLeave(::closeProject) else if (!busy) onBack() }
    val current = draft
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(if (selectedId == null) "Tuning projects" else current?.name ?: "Tuning project") {
                if (selectedId != null) safelyLeave(::closeProject) else if (!busy) onBack()
            }
            if (busy) {
                Text(workingText, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            loadError?.let { message ->
                SettingsGroup {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Saved projects could not be loaded", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text(message, style = MaterialTheme.typography.bodySmall)
                        Text("Changes are disabled. Restore a backup to recover the saved library.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenRack) { Text("Open processing rack") }
                    }
                }
            }
            if (selectedId == null) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { create = true }, enabled = !busy && loadError == null, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Text("New tuning project", Modifier.padding(start = 8.dp)) }
                            OutlinedButton(onClick = { runCatching { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                                .onFailure { notify("No file picker is available.") } }, enabled = !busy && loadError == null, modifier = Modifier.fillMaxWidth()) { Text("Import project JSON") }
                        }
                    }
                    when {
                        loadError != null -> Unit
                        projects == null -> item { TuningDescription("Loading saved projects…") }
                        projects!!.isEmpty() -> item { TuningDescription("No saved projects.") }
                        else -> items(projects!!, key = { it.id }) { project ->
                            TuningProjectCard(project, enabled = !busy, onOpen = { open(project) }, onRename = { rename = project },
                                onDelete = { delete = project }, onExport = { export(project) })
                        }
                    }
                    item { SettingsGroup {
                        SettingsNavRow(Icons.Filled.ShowChart, "Import Wavelet correction", onClick = { correctionImport = true })
                        SettingsNavRow(Icons.Filled.LibraryBooks, "Target library", onClick = { targetLibrary = true })
                    } }
                    item { SettingsGroup { SettingsNavRow(Icons.Filled.Tune, "Processing rack", onClick = onOpenRack) } }
                }
            } else if (current != null) {
                TuningProjectEditor(current, current != baseline, enabled = !busy && fitJob == null && loadError == null, fitting = fitJob != null,
                    padding = contentPadding, onSave = { perform("Saving project…") { saveCurrent(current); notify("Project saved.") } },
                    onRename = { rename = current }, onNotes = { notes = true }, onImport = { importSlot = it },
                    onProvenance = { provenanceSlot = it }, onClear = { slot -> update { it.withCurve(slot, null) } },
                    onSettings = { settings = true }, onGenerate = { generate(current) }, onCancelFit = { fitJob?.cancel() },
                    onReview = { review = current; reviewError = null }, onExport = { export(current) },
                    onOpenRack = { safelyLeave(onOpenRack) }, onTargets = { targetLibrary = true },
                    onSaveTarget = { curve -> perform("Saving target...") { store.saveTuningTarget(withContext(Dispatchers.Default) { TuningTargetCatalog.fromCurve(curve) }).getOrThrow(); notify("Target saved.") } },
                    canUndo = history.isNotEmpty(), onUndo = { history.lastOrNull()?.let { draft = it; history = history.dropLast(1) } },
                    canRevert = appliedUndo != null, onRevert = { appliedUndo?.let { (before, after) -> perform("Reverting correction...") {
                        store.revertTuningAppend(after, before).getOrThrow(); appliedUndo = null; notify("Rack restored.")
                    } } })
            } else TuningDescription("Loading project…")
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }

    if (targetLibrary) TuningTargetLibraryDialog(current, onDismiss = { targetLibrary = false }, onUse = { curve ->
        update { it.copy(target = curve) }; targetLibrary = false
    })
    if (correctionImport) TuningMeasurementImportDialog(TuningMeasurementSlot.TARGET, null, onDismiss = { correctionImport = false }, correctionImport = true) { curve ->
        correctionImport = false
        perform("Importing correction...") {
            val project = withContext(Dispatchers.Default) { TuningCurveAdapters.correctionProject(curve, System.currentTimeMillis()) }
            store.saveTuningProject(project).getOrThrow(); open(project)
        }
    }
    if (create) TuningNameDialog("New tuning project", "", "Create", onDismiss = { create = false }) { name ->
        val project = TuningProjectCodec.create(name, System.currentTimeMillis())
        create = false
        perform("Creating project…") { store.saveTuningProject(project).getOrThrow(); open(project) }
    }
    rename?.let { project -> TuningNameDialog("Rename project", project.name, "Save", onDismiss = { rename = null }) { name ->
        rename = null
        if (draft?.id == project.id) update { it.copy(name = name) }
        else perform("Renaming project…") { store.saveTuningProject(project.copy(name = name, updatedAtMs = System.currentTimeMillis())).getOrThrow() }
    } }
    delete?.let { project -> AlertDialog(onDismissRequest = { if (!busy) delete = null }, title = { Text("Delete ${project.name}?") },
        text = { Text("Deletes the project and measurements. Existing rack stages stay unchanged.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { perform("Deleting project…") { store.deleteTuningProject(project.id).getOrThrow(); delete = null } }) { Text("Delete") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { delete = null }) { Text("Cancel") } }) }
    importSlot?.let { slot -> if (current != null) TuningMeasurementImportDialog(slot, current.curve(slot), onDismiss = { importSlot = null }) { curve ->
        update { it.withCurve(slot, curve) }; importSlot = null
    } }
    provenanceSlot?.let { slot -> current?.curve(slot)?.let { curve -> TuningProvenanceDialog(curve, onDismiss = { provenanceSlot = null }) { changed ->
        update { it.withCurve(slot, changed) }; provenanceSlot = null
    } } }
    if (settings && current != null) TuningFitSettingsDialog(current, onDismiss = { settings = false }) { config -> update { it.copy(config = config) }; settings = false }
    if (notes && current != null) TuningNotesDialog(current.notes, onDismiss = { notes = false }) { text -> update { it.copy(notes = text) }; notes = false }
    leaveAfterSave?.let { action -> AlertDialog(onDismissRequest = { if (!busy) leaveAfterSave = null }, title = { Text("Save project changes?") },
        text = { Column {
            TextButton(enabled = !busy, onClick = { leaveAfterSave = null; action() }) { Text("Leave without saving") } } },
        confirmButton = { TextButton(enabled = !busy, onClick = { current?.let { perform("Saving project…") { saveCurrent(it); leaveAfterSave = null; action() } } }) { Text("Save & leave") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { leaveAfterSave = null }) { Text("Keep editing") } }) }
    importedProject?.let { project -> AlertDialog(onDismissRequest = { if (!busy) importedProject = null }, title = { Text("Import tuning project?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text(project.inputSummary(), style = MaterialTheme.typography.bodyMedium)
            Text("Creates a new project. Regenerate correction before applying it.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { perform("Importing project…") { store.saveTuningProject(project).getOrThrow(); importedProject = null; open(project) } }) { Text("Import as new project") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { importedProject = null }) { Text("Cancel") } }) }
    review?.let { project -> TuningApplyDialog(project, rack, busy, reviewError, onDismiss = { if (!busy) review = null }, onApply = {
        perform("Appending correction…") {
            val saved = withContext(Dispatchers.Default) { TuningProjectCodec.validate(project.copy(updatedAtMs = System.currentTimeMillis())) }
            val result = store.appendTuningProjectWithUndo(saved)
            result.onSuccess { snapshots ->
                appliedUndo = snapshots
                baseline = saved
                if (draft == project) draft = saved
                review = null
                notify("Correction added to the rack.")
            }.onFailure { reviewError = it.message ?: "Could not append this correction. The rack was not changed." }
        }
    }) }
}

@Composable
private fun TuningProjectCard(project: TuningProject, enabled: Boolean, onOpen: () -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onOpen).padding(vertical = 16.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(project.inputSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (project.generatedFit == null) "No generated correction" else "Generated correction saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(enabled = enabled, onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions for ${project.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Export project JSON") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun TuningProjectEditor(project: TuningProject, dirty: Boolean, enabled: Boolean, fitting: Boolean, padding: PaddingValues,
    onSave: () -> Unit, onRename: () -> Unit, onNotes: () -> Unit, onImport: (TuningMeasurementSlot) -> Unit,
    onProvenance: (TuningMeasurementSlot) -> Unit, onClear: (TuningMeasurementSlot) -> Unit,
    onSettings: () -> Unit, onGenerate: () -> Unit, onCancelFit: () -> Unit, onReview: () -> Unit,
    onExport: () -> Unit, onOpenRack: () -> Unit, onTargets: () -> Unit, onSaveTarget: (MeasurementCurve) -> Unit,
    canUndo: Boolean, onUndo: () -> Unit, canRevert: Boolean, onRevert: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (dirty) "Unsaved changes" else "Project saved", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onSave, enabled = enabled && dirty) { Text("Save project") }
                }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = onRename, enabled = enabled) { Text("Rename") }
                    TextButton(onClick = onNotes, enabled = enabled) { Text("Project notes") }
                    TextButton(onClick = onUndo, enabled = enabled && canUndo) { Text("Undo") }
                }
                if (project.notes.isNotBlank()) TuningDescription(project.notes)
            }
        }
        item { SettingsSectionTitle("Measurements & target") }
        items(TuningMeasurementSlot.entries) { slot ->
            val curve = project.curve(slot)
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(slot.label, style = MaterialTheme.typography.titleMedium)
                    Text(curve?.let { "${it.name} · ${it.points.size} points\n${eqFrequency(it.points.first().frequencyHz)}–${eqFrequency(it.points.last().frequencyHz)}" }
                        ?: if (slot == TuningMeasurementSlot.TARGET) "Flat relative target · 0 dB" else "No measurement imported", style = MaterialTheme.typography.bodySmall)
                    if (curve?.provenance?.rig?.isNotBlank() == true) Text("Rig: ${curve.provenance.rig}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onImport(slot) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (curve == null) "Import file or paste" else "Replace measurement") }
                    if (slot == TuningMeasurementSlot.TARGET) OutlinedButton(onClick = onTargets, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Choose saved or published target") }
                    if (curve != null) Row {
                        TextButton(onClick = { onProvenance(slot) }, enabled = enabled) { Text("Details & rig notes") }
                        TextButton(onClick = { onClear(slot) }, enabled = enabled) { Text(if (slot == TuningMeasurementSlot.TARGET) "Use flat" else "Remove") }
                    }
                    if (curve != null && curve.format != TuningCurveFormat.WAVELET) TextButton(onClick = { onSaveTarget(curve) }, enabled = enabled) { Text("Save as reusable target") }
                }
            }
        }
        item {
            SettingsSectionTitle("Fit correction")
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.config.channelMode.label(), style = MaterialTheme.typography.titleMedium)
                    Text("${project.config.bandBudget} total bands · ${eqFrequency(project.config.sampleRate.toDouble())}\n${eqFrequency(project.config.minFrequencyHz)}–${eqFrequency(project.config.maxFrequencyHz)} · boost ≤ ${eqNumber(project.config.maxBoostDb)} dB · cut ≤ ${eqNumber(project.config.maxCutDb)} dB",
                        style = MaterialTheme.typography.bodySmall)
                    if (project.config.leftLimits != null || project.config.rightLimits != null) {
                        val left = project.config.forChannel(TuningFitChannel.LEFT)
                        val right = project.config.forChannel(TuningFitChannel.RIGHT)
                        Text("Channel overrides: L +${eqNumber(left.maxBoostDb)}/−${eqNumber(left.maxCutDb)} dB · R +${eqNumber(right.maxBoostDb)}/−${eqNumber(right.maxCutDb)} dB", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (project.config.normalization == TuningNormalization.NONE) "Normalization off" else "Match mean levels over 200–2000 Hz", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onSettings, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Fitting settings") }
                    val missing = project.fitInputProblem()
                    if (missing != null) Text(missing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fitting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Generating correction…", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onCancelFit, modifier = Modifier.fillMaxWidth()) { Text("Cancel fitting") }
                    } else Button(onClick = onGenerate, enabled = enabled && missing == null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (project.generatedFit == null) "Generate correction" else "Generate again")
                    }
                }
            }
        }
        project.generatedFit?.let { fit ->
            item { SettingsSectionTitle("Generated result") }
            item { TuningFitResultCard(fit) }
            item {
                Button(onClick = onReview, enabled = enabled && (fit.preampDb < 0f || fit.channels.any { it.bands.isNotEmpty() } || fit.config.hasChannelAlignment),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("Review & append to rack") }
                if (fit.preampDb >= 0f && fit.channels.all { it.bands.isEmpty() } && !fit.config.hasChannelAlignment) TuningDescription("No correction needed within these limits.")
            }
        }
        item {
            SettingsSectionTitle("Project files & playback")
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Save & export project JSON") }
                    OutlinedButton(onClick = onOpenRack, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Open processing rack") }
                    if (canRevert) OutlinedButton(onClick = onRevert, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Undo last rack append") }
                }
            }
        }
    }
}

@Composable
private fun TuningFitResultCard(fit: TuningFitResult) {
    var selected by remember(fit) { mutableIntStateOf(0) }
    var details by remember(fit) { mutableStateOf(false) }
    val channel = fit.channels.getOrNull(selected) ?: return
    val frequencies = remember(fit) { fit.frequenciesHz.toDoubleArray() }
    val curves = remember(channel) { listOf(TuningPlotCurve("Measured", channel.measuredDb.toDoubleArray()),
        TuningPlotCurve("Target", channel.targetDb.toDoubleArray()), TuningPlotCurve("Fitted EQ", channel.fittedDb.toDoubleArray()),
        TuningPlotCurve("Predicted", channel.predictedDb.toDoubleArray())) }
    SettingsGroup {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TuningChoiceRow("Displayed channel", fit.channels.map { it.channel.name.channelLabel() }, selected) { selected = it }
            Text("RMS fitting error: ${eqNumber(channel.errorBeforeDb)} → ${eqNumber(channel.errorAfterDb)} dB", style = MaterialTheme.typography.titleSmall)
            Text("${channel.bands.size} peaking filters · coverage ${eqFrequency(fit.minFrequencyHz)}–${eqFrequency(fit.maxFrequencyHz)}",
                style = MaterialTheme.typography.bodySmall)
            Text("Proposed preamp: ${eqDb(fit.preampDb.toDouble())}", style = MaterialTheme.typography.titleSmall)
            Text("Preamp covers this fit only. Other stages may add gain.", style = MaterialTheme.typography.bodySmall)
        }
        TuningCurveChart(frequencies, curves, "EQ response only. Preamp, trim and delay are separate.")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide fit details" else "Fit details & generated filters") }
            if (details) {
                Text("Measured / target level offsets: ${eqDb(fit.measuredNormalizationDb)} / ${eqDb(fit.targetNormalizationDb)}", style = MaterialTheme.typography.bodySmall)
                Text("Fitted response: boost ${eqNumber(fit.peakBoostDb)} dB · cut ${eqNumber(fit.peakCutDb)} dB", style = MaterialTheme.typography.bodySmall)
                fit.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Generated filters", style = MaterialTheme.typography.titleSmall)
                channel.bands.forEachIndexed { index, band ->
                    Text("${index + 1}. ${eqFrequency(band.freqHz.toDouble())} · ${eqDb(band.gainDb.toDouble())} · Q ${eqNumber(band.q.toDouble())}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TuningApplyDialog(project: TuningProject, rack: ProcessingRack?, busy: Boolean, error: String?, onDismiss: () -> Unit, onApply: () -> Unit) {
    val fit = project.generatedFit ?: return
    val addedChannels = fit.channels.filter { it.bands.isNotEmpty() }
    val addedNodes = addedChannels.size + (if (fit.preampDb < 0f) 1 else 0) + fit.config.alignmentNodeCount
    val bands = fit.channels.sumOf { it.bands.size }
    val oldBands = rack?.nodes?.filter { it.kind == RackNodeKind.EQ || it.kind == RackNodeKind.LEGACY_DSP }?.sumOf { it.audio.dspParametric.size } ?: 0
    val capacityOkay = rack != null && rack.nodes.size + addedNodes <= 16 && oldBands + bands <= ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Append this correction?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            if (fit.preampDb < 0f) Text("Gain · ${eqDb(fit.preampDb.toDouble())}", style = MaterialTheme.typography.bodyMedium)
            if (fit.config.leftTrimDb != 0.0 || fit.config.rightTrimDb != 0.0) Text("Trim: L ${eqDb(fit.config.leftTrimDb)}, R ${eqDb(fit.config.rightTrimDb)}", style = MaterialTheme.typography.bodyMedium)
            if (fit.config.leftDelayMs != 0.0 || fit.config.rightDelayMs != 0.0) Text("Delay: L ${eqNumber(fit.config.leftDelayMs)} ms, R ${eqNumber(fit.config.rightDelayMs)} ms", style = MaterialTheme.typography.bodyMedium)
            addedChannels.forEach { Text("EQ · ${it.channel.name.channelLabel()} · ${it.bands.size} bands", style = MaterialTheme.typography.bodyMedium) }
            Text(if (rack?.nodes?.lastOrNull()?.kind == RackNodeKind.LIMITER) "Insert before the rack's final Limiter stage." else "Append at the end of the rack.", style = MaterialTheme.typography.bodySmall)
            Text("Existing stages stay unchanged.", style = MaterialTheme.typography.bodySmall)
            Text(if (rack?.enabled == true) "Rack mode stays active; this changes playback." else "Standard mode stays active. Select Rack mode later to use the added correction.", style = MaterialTheme.typography.bodySmall)
            Text("Other rack stages may need extra headroom.", style = MaterialTheme.typography.bodySmall)
            if (rack != null) Text("After append: ${rack.nodes.size + addedNodes}/16 stages · ${oldBands + bands}/${ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS} parametric bands", style = MaterialTheme.typography.bodySmall)
            if (!capacityOkay) Text(if (rack == null) "Loading the current rack…" else "The rack does not have enough space. Reduce the fit's band budget or remove rack stages first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = !busy && capacityOkay, onClick = onApply) { Text("Append correction") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TuningNameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column {
        OutlinedTextField(name, { name = it.take(80); error = null }, label = { Text("Project name") }, singleLine = true)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { runCatching { onSave(name.trim()) }.onFailure { error = it.message ?: "Check the project name." } }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TuningNotesDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var notes by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Project notes") }, text = {
        OutlinedTextField(notes, { notes = it.take(4096) }, label = { Text("Purpose, listening setup & observations") }, minLines = 5, maxLines = 10)
    }, confirmButton = { TextButton(onClick = { onSave(notes) }) { Text("Use notes") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TuningDescription(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun TuningProject.curve(slot: TuningMeasurementSlot): MeasurementCurve? = when (slot) {
    TuningMeasurementSlot.LEFT -> measurementLeft; TuningMeasurementSlot.RIGHT -> measurementRight; TuningMeasurementSlot.TARGET -> target
}
private fun TuningProject.withCurve(slot: TuningMeasurementSlot, curve: MeasurementCurve?): TuningProject = when (slot) {
    TuningMeasurementSlot.LEFT -> copy(measurementLeft = curve); TuningMeasurementSlot.RIGHT -> copy(measurementRight = curve); TuningMeasurementSlot.TARGET -> copy(target = curve)
}
private fun TuningProject.inputSummary(): String = "Left ${measurementLeft?.points?.size ?: 0} · Right ${measurementRight?.points?.size ?: 0} points · ${if (target == null) "Flat target" else "Custom target"}"
private fun TuningProject.fitInputProblem(): String? = when (config.channelMode) {
    TuningChannelMode.LEFT -> if (measurementLeft == null) "Import the left measurement to fit this channel." else null
    TuningChannelMode.RIGHT -> if (measurementRight == null) "Import the right measurement to fit this channel." else null
    TuningChannelMode.INDEPENDENT -> when {
        measurementLeft == null && measurementRight == null -> "Import at least one measurement to generate a correction."
        measurementLeft != null && measurementRight != null && config.bandBudget < 2 -> "Fitting both channels independently needs a total budget of at least two bands."
        else -> null
    }
    TuningChannelMode.LINKED_AVERAGE -> if (measurementLeft == null || measurementRight == null) "Import both measurements for an explicit linked average." else null
}
private fun String.channelLabel(): String = when (this) { "LEFT" -> "Left"; "RIGHT" -> "Right"; "LINKED_AVERAGE" -> "Linked average → both channels"; else -> replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } }
