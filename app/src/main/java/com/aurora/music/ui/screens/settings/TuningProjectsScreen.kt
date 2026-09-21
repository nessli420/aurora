package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
            loadError = it.message ?: appString(R.string.text_saved_tuning_projects_could_not_be_decoded_a1167f)
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
        busy = true; workingText = appString(R.string.text_updating_project_ab05d2)
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
        check(loadError == null) { appString(R.string.text_recover_the_saved_project_library_before_writing_changes_816728) }
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
        if (loadError != null) { notify(appString(R.string.text_recover_the_saved_project_library_before_writing_changes_816728)); return }
        busy = true; workingText = label
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: appString(R.string.text_could_not_update_this_project_68b4fe)) }
            finally { busy = false }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) perform(appString(R.string.text_exporting_project_89978a)) {
            val project = store.tuningProjects.first().firstOrNull { it.id == id } ?: error(appString(R.string.text_this_project_is_no_longer_saved_33a1db))
            withContext(Dispatchers.IO) {
                val json = TuningProjectCodec.encodeProject(project)
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error(appString(R.string.text_could_not_open_the_selected_destination_982a0a))
                output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            notify(appString(R.string.text_project_exported_4c0502))
        }
    }
    fun export(project: TuningProject) {
        perform(appString(R.string.text_saving_project_for_export_cf6162)) {
            val saved = saveCurrent(project)
            pendingExportId = saved.id
            val name = saved.name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().take(60).ifBlank { appString(R.string.text_aurora_tuning_5ee0b6) }
            try { exportLauncher.launch("$name.json") }
            catch (failure: Exception) { pendingExportId = null; throw IllegalStateException(appString(R.string.text_no_document_picker_is_available_a8fedf), failure) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform(appString(R.string.text_reading_project_68617b)) {
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
                notify(appString(R.string.text_correction_generated_21f0fd))
            } catch (cancelled: CancellationException) { notify(appString(R.string.text_fitting_cancelled_4b8e81)); throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: appString(R.string.text_could_not_generate_a_correction_for_these_inputs_95156d)) }
            finally { fitJob = null }
        }
    }
    BackHandler { if (selectedId != null) safelyLeave(::closeProject) else if (!busy) onBack() }
    val current = draft
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(if (selectedId == null) appString(R.string.text_tuning_projects_e28bb7) else current?.name ?: appString(R.string.text_tuning_project_fe107d)) {
                if (selectedId != null) safelyLeave(::closeProject) else if (!busy) onBack()
            }
            if (busy) {
                Text(workingText, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            loadError?.let { message ->
                SettingsGroup {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(appString(R.string.text_saved_projects_could_not_be_loaded_14d15f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text(message, style = MaterialTheme.typography.bodySmall)
                        Text(appString(R.string.text_changes_are_disabled_restore_a_backup_to_recover_the_saved_librar_ceffca), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenRack) { Text(appString(R.string.text_open_processing_rack_728e5d)) }
                    }
                }
            }
            if (selectedId == null) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { create = true }, enabled = !busy && loadError == null, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Text(appString(R.string.text_new_tuning_project_97a69f), Modifier.padding(start = 8.dp)) }
                            OutlinedButton(onClick = { runCatching { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                                .onFailure { notify(appString(R.string.text_no_file_picker_is_available_fb12a4)) } }, enabled = !busy && loadError == null, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_import_project_json_4f2253)) }
                        }
                    }
                    when {
                        loadError != null -> Unit
                        projects == null -> item { TuningDescription(appString(R.string.text_loading_saved_projects_b9d89d)) }
                        projects!!.isEmpty() -> item { TuningDescription(appString(R.string.text_no_saved_projects_7102b1)) }
                        else -> items(projects!!, key = { it.id }) { project ->
                            TuningProjectCard(project, enabled = !busy, onOpen = { open(project) }, onRename = { rename = project },
                                onDelete = { delete = project }, onExport = { export(project) })
                        }
                    }
                    item { SettingsGroup {
                        SettingsNavRow(Icons.Filled.ShowChart, appString(R.string.text_import_wavelet_correction_2fe17e), onClick = { correctionImport = true })
                        SettingsNavRow(Icons.Filled.LibraryBooks, appString(R.string.text_target_library_45e3b8), onClick = { targetLibrary = true })
                    } }
                    item { SettingsGroup { SettingsNavRow(Icons.Filled.Tune, appString(R.string.text_processing_rack_f7dff1), onClick = onOpenRack) } }
                }
            } else if (current != null) {
                TuningProjectEditor(current, current != baseline, enabled = !busy && fitJob == null && loadError == null, fitting = fitJob != null,
                    padding = contentPadding, onSave = { perform(appString(R.string.text_saving_project_69e697)) { saveCurrent(current); notify(appString(R.string.text_project_saved_bebad7)) } },
                    onRename = { rename = current }, onNotes = { notes = true }, onImport = { importSlot = it },
                    onProvenance = { provenanceSlot = it }, onClear = { slot -> update { it.withCurve(slot, null) } },
                    onSettings = { settings = true }, onGenerate = { generate(current) }, onCancelFit = { fitJob?.cancel() },
                    onReview = { review = current; reviewError = null }, onExport = { export(current) },
                    onOpenRack = { safelyLeave(onOpenRack) }, onTargets = { targetLibrary = true },
                    onSaveTarget = { curve -> perform(appString(R.string.text_saving_target_0672fd)) { store.saveTuningTarget(withContext(Dispatchers.Default) { TuningTargetCatalog.fromCurve(curve) }).getOrThrow(); notify(appString(R.string.text_target_saved_79a16c)) } },
                    canUndo = history.isNotEmpty(), onUndo = { history.lastOrNull()?.let { draft = it; history = history.dropLast(1) } },
                    canRevert = appliedUndo != null, onRevert = { appliedUndo?.let { (before, after) -> perform(appString(R.string.text_reverting_correction_6de6e2)) {
                        store.revertTuningAppend(after, before).getOrThrow(); appliedUndo = null; notify(appString(R.string.text_rack_restored_64ad64))
                    } } })
            } else TuningDescription(appString(R.string.text_loading_project_d3b8f4))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }

    if (targetLibrary) TuningTargetLibraryDialog(current, onDismiss = { targetLibrary = false }, onUse = { curve ->
        update { it.copy(target = curve) }; targetLibrary = false
    })
    if (correctionImport) TuningMeasurementImportDialog(TuningMeasurementSlot.TARGET, null, onDismiss = { correctionImport = false }, correctionImport = true) { curve ->
        correctionImport = false
        perform(appString(R.string.text_importing_correction_05d728)) {
            val project = withContext(Dispatchers.Default) { TuningCurveAdapters.correctionProject(curve, System.currentTimeMillis()) }
            store.saveTuningProject(project).getOrThrow(); open(project)
        }
    }
    if (create) TuningNameDialog(appString(R.string.text_new_tuning_project_97a69f), "", appString(R.string.text_create_6e157c), onDismiss = { create = false }) { name ->
        val project = TuningProjectCodec.create(name, System.currentTimeMillis())
        create = false
        perform(appString(R.string.text_creating_project_e1bab2)) { store.saveTuningProject(project).getOrThrow(); open(project) }
    }
    rename?.let { project -> TuningNameDialog(appString(R.string.text_rename_project_633018), project.name, appString(R.string.text_save_efc007), onDismiss = { rename = null }) { name ->
        rename = null
        if (draft?.id == project.id) update { it.copy(name = name) }
        else perform(appString(R.string.text_renaming_project_8cfc7a)) { store.saveTuningProject(project.copy(name = name, updatedAtMs = System.currentTimeMillis())).getOrThrow() }
    } }
    delete?.let { project -> AlertDialog(onDismissRequest = { if (!busy) delete = null }, title = { Text(appString(R.string.text_delete_137cdc, (project.name))) },
        text = { Text(appString(R.string.text_deletes_the_project_and_measurements_existing_rack_stages_stay_un_f93f18)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { perform(appString(R.string.text_deleting_project_d0e644)) { store.deleteTuningProject(project.id).getOrThrow(); delete = null } }) { Text(appString(R.string.text_delete_f6fdbe)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { delete = null }) { Text(appString(R.string.text_cancel_77dfd2)) } }) }
    importSlot?.let { slot -> if (current != null) TuningMeasurementImportDialog(slot, current.curve(slot), onDismiss = { importSlot = null }) { curve ->
        update { it.withCurve(slot, curve) }; importSlot = null
    } }
    provenanceSlot?.let { slot -> current?.curve(slot)?.let { curve -> TuningProvenanceDialog(curve, onDismiss = { provenanceSlot = null }) { changed ->
        update { it.withCurve(slot, changed) }; provenanceSlot = null
    } } }
    if (settings && current != null) TuningFitSettingsDialog(current, onDismiss = { settings = false }) { config -> update { it.copy(config = config) }; settings = false }
    if (notes && current != null) TuningNotesDialog(current.notes, onDismiss = { notes = false }) { text -> update { it.copy(notes = text) }; notes = false }
    leaveAfterSave?.let { action -> AlertDialog(onDismissRequest = { if (!busy) leaveAfterSave = null }, title = { Text(appString(R.string.text_save_project_changes_026247)) },
        text = { Column {
            TextButton(enabled = !busy, onClick = { leaveAfterSave = null; action() }) { Text(appString(R.string.text_leave_without_saving_634bae)) } } },
        confirmButton = { TextButton(enabled = !busy, onClick = { current?.let { perform(appString(R.string.text_saving_project_69e697)) { saveCurrent(it); leaveAfterSave = null; action() } } }) { Text(appString(R.string.text_save_leave_fc9cb3)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { leaveAfterSave = null }) { Text(appString(R.string.text_keep_editing_d4d9e3)) } }) }
    importedProject?.let { project -> AlertDialog(onDismissRequest = { if (!busy) importedProject = null }, title = { Text(appString(R.string.text_import_tuning_project_2df594)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            Text(project.inputSummary(), style = MaterialTheme.typography.bodyMedium)
            Text(appString(R.string.text_creates_a_new_project_regenerate_correction_before_applying_it_423e23), style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { perform(appString(R.string.text_importing_project_5b661a)) { store.saveTuningProject(project).getOrThrow(); importedProject = null; open(project) } }) { Text(appString(R.string.text_import_as_new_project_d6935b)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { importedProject = null }) { Text(appString(R.string.text_cancel_77dfd2)) } }) }
    review?.let { project -> TuningApplyDialog(project, rack, busy, reviewError, onDismiss = { if (!busy) review = null }, onApply = {
        perform(appString(R.string.text_appending_correction_7349e5)) {
            val saved = withContext(Dispatchers.Default) { TuningProjectCodec.validate(project.copy(updatedAtMs = System.currentTimeMillis())) }
            val result = store.appendTuningProjectWithUndo(saved)
            result.onSuccess { snapshots ->
                appliedUndo = snapshots
                baseline = saved
                if (draft == project) draft = saved
                review = null
                notify(appString(R.string.text_correction_added_to_the_rack_66be55))
            }.onFailure { reviewError = it.message ?: appString(R.string.text_could_not_append_this_correction_the_rack_was_not_changed_9f04fe) }
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
                Text(if (project.generatedFit == null) appString(R.string.text_no_generated_correction_1d3dac) else appString(R.string.text_generated_correction_saved_b3c6de), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(enabled = enabled, onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, appString(R.string.text_actions_for_cf6f1b, (project.name))) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(appString(R.string.text_rename_d3f4cb)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_export_project_json_57cf46)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(appString(R.string.text_delete_f6fdbe)) }, onClick = { menu = false; onDelete() })
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
                    Text(if (dirty) appString(R.string.text_unsaved_changes_292672) else appString(R.string.text_project_saved_3a58a0), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onSave, enabled = enabled && dirty) { Text(appString(R.string.text_save_project_2dd802)) }
                }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = onRename, enabled = enabled) { Text(appString(R.string.text_rename_d3f4cb)) }
                    TextButton(onClick = onNotes, enabled = enabled) { Text(appString(R.string.text_project_notes_7b2dc5)) }
                    TextButton(onClick = onUndo, enabled = enabled && canUndo) { Text(appString(R.string.text_undo_39fc72)) }
                }
                if (project.notes.isNotBlank()) TuningDescription(project.notes)
            }
        }
        item { SettingsSectionTitle(appString(R.string.text_measurements_target_b6da10)) }
        items(TuningMeasurementSlot.entries) { slot ->
            val curve = project.curve(slot)
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(slot.label, style = MaterialTheme.typography.titleMedium)
                    Text(curve?.let { appString(R.string.text_points_ee82a2, (it.name), (it.points.size), (eqFrequency(it.points.first().frequencyHz)), (eqFrequency(it.points.last().frequencyHz))) }
                        ?: if (slot == TuningMeasurementSlot.TARGET) appString(R.string.text_flat_relative_target_0_db_1f020f) else appString(R.string.text_no_measurement_imported_1dca9a), style = MaterialTheme.typography.bodySmall)
                    if (curve?.provenance?.rig?.isNotBlank() == true) Text(appString(R.string.text_rig_404b11, (curve.provenance.rig)), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onImport(slot) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (curve == null) appString(R.string.text_import_file_or_paste_3aaac6) else appString(R.string.text_replace_measurement_1a74ba)) }
                    if (slot == TuningMeasurementSlot.TARGET) OutlinedButton(onClick = onTargets, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_choose_saved_or_published_target_d050a2)) }
                    if (curve != null) Row {
                        TextButton(onClick = { onProvenance(slot) }, enabled = enabled) { Text(appString(R.string.text_details_rig_notes_d17254)) }
                        TextButton(onClick = { onClear(slot) }, enabled = enabled) { Text(if (slot == TuningMeasurementSlot.TARGET) appString(R.string.text_use_flat_ed2b5f) else appString(R.string.text_remove_e96390)) }
                    }
                    if (curve != null && curve.format != TuningCurveFormat.WAVELET) TextButton(onClick = { onSaveTarget(curve) }, enabled = enabled) { Text(appString(R.string.text_save_as_reusable_target_8c3b0f)) }
                }
            }
        }
        item {
            SettingsSectionTitle(appString(R.string.text_fit_correction_7446db))
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.config.channelMode.label(), style = MaterialTheme.typography.titleMedium)
                    Text(appString(R.string.text_total_bands_boost_db_cut_db_2d77f8, (project.config.bandBudget), (eqFrequency(project.config.sampleRate.toDouble())), (eqFrequency(project.config.minFrequencyHz)), (eqFrequency(project.config.maxFrequencyHz)), (eqNumber(project.config.maxBoostDb)), (eqNumber(project.config.maxCutDb))),
                        style = MaterialTheme.typography.bodySmall)
                    if (project.config.leftLimits != null || project.config.rightLimits != null) {
                        val left = project.config.forChannel(TuningFitChannel.LEFT)
                        val right = project.config.forChannel(TuningFitChannel.RIGHT)
                        Text(appString(R.string.text_channel_overrides_l_db_r_db_59779e, (eqNumber(left.maxBoostDb)), (eqNumber(left.maxCutDb)), (eqNumber(right.maxBoostDb)), (eqNumber(right.maxCutDb))), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (project.config.normalization == TuningNormalization.NONE) appString(R.string.text_normalization_off_3e378d) else appString(R.string.text_match_mean_levels_over_200_2000_hz_31a6ab), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onSettings, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_fitting_settings_d58cce)) }
                    val missing = project.fitInputProblem()
                    if (missing != null) Text(missing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fitting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(appString(R.string.text_generating_correction_b3c463), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onCancelFit, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_cancel_fitting_492826)) }
                    } else Button(onClick = onGenerate, enabled = enabled && missing == null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (project.generatedFit == null) appString(R.string.text_generate_correction_3e0605) else appString(R.string.text_generate_again_3efce7))
                    }
                }
            }
        }
        project.generatedFit?.let { fit ->
            item { SettingsSectionTitle(appString(R.string.text_generated_result_091d15)) }
            item { TuningFitResultCard(fit) }
            item {
                Button(onClick = onReview, enabled = enabled && (fit.preampDb < 0f || fit.channels.any { it.bands.isNotEmpty() } || fit.config.hasChannelAlignment),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text(appString(R.string.text_review_append_to_rack_8cbb43)) }
                if (fit.preampDb >= 0f && fit.channels.all { it.bands.isEmpty() } && !fit.config.hasChannelAlignment) TuningDescription(appString(R.string.text_no_correction_needed_within_these_limits_0fdcba))
            }
        }
        item {
            SettingsSectionTitle(appString(R.string.text_project_files_playback_3c21f3))
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_save_export_project_json_9f2b2c)) }
                    OutlinedButton(onClick = onOpenRack, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_open_processing_rack_728e5d)) }
                    if (canRevert) OutlinedButton(onClick = onRevert, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_undo_last_rack_append_babadf)) }
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
    val curves = remember(channel) { listOf(TuningPlotCurve(appString(R.string.text_measured_955037), channel.measuredDb.toDoubleArray()),
        TuningPlotCurve(appString(R.string.text_target_61ad50), channel.targetDb.toDoubleArray()), TuningPlotCurve(appString(R.string.text_fitted_eq_3bc5ee), channel.fittedDb.toDoubleArray()),
        TuningPlotCurve(appString(R.string.text_predicted_a49225), channel.predictedDb.toDoubleArray())) }
    SettingsGroup {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TuningChoiceRow(appString(R.string.text_displayed_channel_2ae515), fit.channels.map { it.channel.name.channelLabel() }, selected) { selected = it }
            Text(appString(R.string.text_rms_fitting_error_db_fc001a, (eqNumber(channel.errorBeforeDb)), (eqNumber(channel.errorAfterDb))), style = MaterialTheme.typography.titleSmall)
            Text(appString(R.string.text_peaking_filters_coverage_550db0, (channel.bands.size), (eqFrequency(fit.minFrequencyHz)), (eqFrequency(fit.maxFrequencyHz))),
                style = MaterialTheme.typography.bodySmall)
            Text(appString(R.string.text_proposed_preamp_3473b1, (eqDb(fit.preampDb.toDouble()))), style = MaterialTheme.typography.titleSmall)
            Text(appString(R.string.text_preamp_covers_this_fit_only_other_stages_may_add_gain_7864ec), style = MaterialTheme.typography.bodySmall)
        }
        TuningCurveChart(frequencies, curves, appString(R.string.text_eq_response_only_preamp_trim_and_delay_are_separate_3441df))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButton(onClick = { details = !details }) { Text(if (details) appString(R.string.text_hide_fit_details_1c2734) else appString(R.string.text_fit_details_generated_filters_e9ff9a)) }
            if (details) {
                Text(appString(R.string.text_measured_target_level_offsets_640c07, (eqDb(fit.measuredNormalizationDb)), (eqDb(fit.targetNormalizationDb))), style = MaterialTheme.typography.bodySmall)
                Text(appString(R.string.text_fitted_response_boost_db_cut_db_fd80a4, (eqNumber(fit.peakBoostDb)), (eqNumber(fit.peakCutDb))), style = MaterialTheme.typography.bodySmall)
                fit.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(appString(R.string.text_generated_filters_5e5075), style = MaterialTheme.typography.titleSmall)
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.text_append_this_correction_b6edc0)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium)
            if (fit.preampDb < 0f) Text(appString(R.string.text_gain_2d17e8, (eqDb(fit.preampDb.toDouble()))), style = MaterialTheme.typography.bodyMedium)
            if (fit.config.leftTrimDb != 0.0 || fit.config.rightTrimDb != 0.0) Text(appString(R.string.text_trim_l_r_d8c438, (eqDb(fit.config.leftTrimDb)), (eqDb(fit.config.rightTrimDb))), style = MaterialTheme.typography.bodyMedium)
            if (fit.config.leftDelayMs != 0.0 || fit.config.rightDelayMs != 0.0) Text(appString(R.string.text_delay_l_ms_r_ms_82e61b, (eqNumber(fit.config.leftDelayMs)), (eqNumber(fit.config.rightDelayMs))), style = MaterialTheme.typography.bodyMedium)
            addedChannels.forEach { Text(appString(R.string.text_eq_bands_6b80aa, (it.channel.name.channelLabel()), (it.bands.size)), style = MaterialTheme.typography.bodyMedium) }
            Text(if (rack?.nodes?.lastOrNull()?.kind == RackNodeKind.LIMITER) appString(R.string.text_insert_before_the_rack_s_final_limiter_stage_99ab75) else appString(R.string.text_append_at_the_end_of_the_rack_989ed5), style = MaterialTheme.typography.bodySmall)
            Text(appString(R.string.text_existing_stages_stay_unchanged_fc1b79), style = MaterialTheme.typography.bodySmall)
            Text(if (rack?.enabled == true) appString(R.string.text_rack_mode_stays_active_this_changes_playback_5703ec) else appString(R.string.text_standard_mode_stays_active_select_rack_mode_later_to_use_the_adde_5a9ebc), style = MaterialTheme.typography.bodySmall)
            Text(appString(R.string.text_other_rack_stages_may_need_extra_headroom_8776c9), style = MaterialTheme.typography.bodySmall)
            if (rack != null) Text(appString(R.string.text_after_append_16_stages_parametric_bands_590e32, (rack.nodes.size + addedNodes), (oldBands + bands), (ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS)), style = MaterialTheme.typography.bodySmall)
            if (!capacityOkay) Text(if (rack == null) appString(R.string.text_loading_the_current_rack_24a39a) else appString(R.string.text_the_rack_does_not_have_enough_space_reduce_the_fit_s_band_budget_f8764d),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = !busy && capacityOkay, onClick = onApply) { Text(appString(R.string.text_append_correction_58f87b)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

@Composable
private fun TuningNameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column {
        OutlinedTextField(name, { name = it.take(80); error = null }, label = { Text(appString(R.string.text_project_name_ab9773)) }, singleLine = true)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { runCatching { onSave(name.trim()) }.onFailure { error = it.message ?: appString(R.string.text_check_the_project_name_44cbe0) } }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

@Composable
private fun TuningNotesDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var notes by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.text_project_notes_7b2dc5)) }, text = {
        OutlinedTextField(notes, { notes = it.take(4096) }, label = { Text(appString(R.string.text_purpose_listening_setup_observations_16e335)) }, minLines = 5, maxLines = 10)
    }, confirmButton = { TextButton(onClick = { onSave(notes) }) { Text(appString(R.string.text_use_notes_415641)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
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
private fun TuningProject.inputSummary(): String = appString(R.string.text_left_right_points_a4e1b9, (measurementLeft?.points?.size ?: 0), (measurementRight?.points?.size ?: 0), (if (target == null) appString(R.string.text_flat_target_7a50d3) else appString(R.string.text_custom_target_a34c83)))
private fun TuningProject.fitInputProblem(): String? = when (config.channelMode) {
    TuningChannelMode.LEFT -> if (measurementLeft == null) appString(R.string.text_import_the_left_measurement_to_fit_this_channel_99c0c4) else null
    TuningChannelMode.RIGHT -> if (measurementRight == null) appString(R.string.text_import_the_right_measurement_to_fit_this_channel_c6c2d4) else null
    TuningChannelMode.INDEPENDENT -> when {
        measurementLeft == null && measurementRight == null -> appString(R.string.text_import_at_least_one_measurement_to_generate_a_correction_1ea427)
        measurementLeft != null && measurementRight != null && config.bandBudget < 2 -> appString(R.string.text_fitting_both_channels_independently_needs_a_total_budget_of_at_le_ca7670)
        else -> null
    }
    TuningChannelMode.LINKED_AVERAGE -> if (measurementLeft == null || measurementRight == null) appString(R.string.text_import_both_measurements_for_an_explicit_linked_average_aab3d2) else null
}
private fun String.channelLabel(): String = when (this) { "LEFT" -> appString(R.string.text_left_8ae1c3); "RIGHT" -> appString(R.string.text_right_954daa); "LINKED_AVERAGE" -> appString(R.string.text_linked_average_both_channels_6de047); else -> replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } }
