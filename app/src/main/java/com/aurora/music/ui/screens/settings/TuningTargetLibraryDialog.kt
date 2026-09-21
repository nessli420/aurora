package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.tuning.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
internal fun TuningTargetLibraryDialog(project: TuningProject?, onDismiss: () -> Unit, onUse: (MeasurementCurve) -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as AuroraApplication).container.settingsStore
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    val flow = remember(store) { store.tuningTargets.catch { loadFailed = true; error = it.message; emit(emptyList()) } }
    val targets by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var busy by remember { mutableStateOf(false) }
    var importTarget by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<TuningTarget?>(null) }
    var delete by remember { mutableStateOf<TuningTarget?>(null) }
    var selected by remember { mutableStateOf<MeasurementCurve?>(null) }
    var page by remember { mutableIntStateOf(0) }
    fun action(block: suspend () -> Unit) {
        if (busy || loadFailed) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: appString(R.string.text_could_not_update_targets_df30a7) }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(appString(R.string.text_target_library_45e3b8)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningChoiceRow(appString(R.string.text_source_6da13a), listOf(appString(R.string.text_saved_c0ae8f), appString(R.string.text_published_483bf2), appString(R.string.text_this_project_940087)), page) { page = it }
            when (page) {
                0 -> {
                    OutlinedButton(onClick = { importTarget = true }, enabled = !busy && !loadFailed, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_import_custom_target_6450b5)) }
                    if (targets.isEmpty()) Text(appString(R.string.text_no_saved_targets_dd2df6), style = MaterialTheme.typography.bodySmall)
                    targets.forEach { target ->
                        Text(target.name, style = MaterialTheme.typography.titleSmall)
                        Text(target.curve.provenance.rig.ifBlank { appString(R.string.text_rig_unspecified_6f488f) }, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { selected = target.curve }, enabled = !busy && project != null) { Text(appString(R.string.text_use_1d4d43)) }
                            TextButton(onClick = { edit = target }, enabled = !busy && !loadFailed) { Text(appString(R.string.text_details_dc3dec)) }
                            TextButton(onClick = { delete = target }, enabled = !busy && !loadFailed) { Text(appString(R.string.text_delete_f6fdbe)) }
                        }
                    }
                }
                1 -> {
                    Text(appString(R.string.text_autoeq_snapshots_download_once_then_use_offline_ca6135), style = MaterialTheme.typography.bodySmall)
                    TuningTargetCatalog.published.forEach { source ->
                        Text(source.name, style = MaterialTheme.typography.titleSmall)
                        Text(source.rig, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { action {
                            val curve = TuningTargetCatalog.download(source)
                            val existing = targets.firstOrNull { it.curve.provenance.source == source.url }
                            store.saveTuningTarget(existing?.copy(curve = curve) ?: TuningTargetCatalog.fromCurve(curve)).getOrThrow()
                            page = 0
                        } }, enabled = !busy && !loadFailed) { Text(appString(R.string.text_download_target_d5fd60)) }
                    }
                    Text(appString(R.string.text_import_ief_neutral_or_other_targets_from_their_original_author_1ce32b), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    val curves = listOfNotNull(project?.measurementLeft, project?.measurementRight, project?.target)
                        .filter { it.format != TuningCurveFormat.WAVELET }.distinctBy { it.id }
                    if (curves.isEmpty()) Text(appString(R.string.text_open_a_project_with_measurements_first_5f1e78), style = MaterialTheme.typography.bodySmall)
                    curves.forEach { curve ->
                        Text(curve.name, style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = { selected = curve }, enabled = !busy) { Text(appString(R.string.text_use_as_target_b5e4c2)) }
                            TextButton(onClick = { action { store.saveTuningTarget(withContext(Dispatchers.Default) { TuningTargetCatalog.fromCurve(curve) }).getOrThrow(); page = 0 } }, enabled = !busy && !loadFailed) { Text(appString(R.string.text_save_target_fa5df1)) }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(appString(R.string.text_close_bbfa77)) } })
    if (importTarget) TuningMeasurementImportDialog(TuningMeasurementSlot.TARGET, null, onDismiss = { importTarget = false }) { curve ->
        importTarget = false
        action { store.saveTuningTarget(withContext(Dispatchers.Default) { TuningTargetCatalog.fromCurve(curve) }).getOrThrow() }
    }
    edit?.let { target -> TuningProvenanceDialog(target.curve, onDismiss = { edit = null }) { curve ->
        edit = null
        action { store.saveTuningTarget(target.copy(name = curve.name, curve = curve)).getOrThrow() }
    } }
    delete?.let { target -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(appString(R.string.text_delete_137cdc, (target.name))) },
        text = { Text(appString(R.string.text_projects_keep_their_saved_target_copy_f1d9a0)) },
        confirmButton = { TextButton(onClick = { delete = null; action { store.deleteTuningTarget(target.id).getOrThrow() } }) { Text(appString(R.string.text_delete_f6fdbe)) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text(appString(R.string.text_cancel_77dfd2)) } }) }
    selected?.let { curve -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(appString(R.string.text_use_9d772b, (curve.name))) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appString(R.string.text_target_rig_6556f6, (curve.provenance.rig.ifBlank { appString(R.string.text_unspecified_a6e7eb) })))
            listOfNotNull(project?.measurementLeft, project?.measurementRight).forEach {
                Text("${it.name}: ${it.provenance.rig.ifBlank { appString(R.string.text_rig_unspecified_6f488f) }}", style = MaterialTheme.typography.bodySmall)
            }
            Text(appString(R.string.text_confirm_that_these_measurement_systems_are_compatible_f8e6ca), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { selected = null; onUse(curve) }) { Text(appString(R.string.text_use_target_69136c)) } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text(appString(R.string.text_cancel_77dfd2)) } }) }
}
