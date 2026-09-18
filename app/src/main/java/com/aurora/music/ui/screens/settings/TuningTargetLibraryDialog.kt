package com.aurora.music.ui.screens.settings

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
            catch (failure: Exception) { error = failure.message ?: "Could not update targets." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Target library") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningChoiceRow("Source", listOf("Saved", "Published", "This project"), page) { page = it }
            when (page) {
                0 -> {
                    OutlinedButton(onClick = { importTarget = true }, enabled = !busy && !loadFailed, modifier = Modifier.fillMaxWidth()) { Text("Import custom target") }
                    if (targets.isEmpty()) Text("No saved targets.", style = MaterialTheme.typography.bodySmall)
                    targets.forEach { target ->
                        Text(target.name, style = MaterialTheme.typography.titleSmall)
                        Text(target.curve.provenance.rig.ifBlank { "Rig unspecified" }, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { selected = target.curve }, enabled = !busy && project != null) { Text("Use") }
                            TextButton(onClick = { edit = target }, enabled = !busy && !loadFailed) { Text("Details") }
                            TextButton(onClick = { delete = target }, enabled = !busy && !loadFailed) { Text("Delete") }
                        }
                    }
                }
                1 -> {
                    Text("AutoEq snapshots. Download once, then use offline.", style = MaterialTheme.typography.bodySmall)
                    TuningTargetCatalog.published.forEach { source ->
                        Text(source.name, style = MaterialTheme.typography.titleSmall)
                        Text(source.rig, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { action {
                            val curve = TuningTargetCatalog.download(source)
                            val existing = targets.firstOrNull { it.curve.provenance.source == source.url }
                            store.saveTuningTarget(existing?.copy(curve = curve) ?: TuningTargetCatalog.fromCurve(curve)).getOrThrow()
                            page = 0
                        } }, enabled = !busy && !loadFailed) { Text("Download target") }
                    }
                    Text("Import IEF Neutral or other targets from their original author.", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    val curves = listOfNotNull(project?.measurementLeft, project?.measurementRight, project?.target)
                        .filter { it.format != TuningCurveFormat.WAVELET }.distinctBy { it.id }
                    if (curves.isEmpty()) Text("Open a project with measurements first.", style = MaterialTheme.typography.bodySmall)
                    curves.forEach { curve ->
                        Text(curve.name, style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = { selected = curve }, enabled = !busy) { Text("Use as target") }
                            TextButton(onClick = { action { store.saveTuningTarget(withContext(Dispatchers.Default) { TuningTargetCatalog.fromCurve(curve) }).getOrThrow(); page = 0 } }, enabled = !busy && !loadFailed) { Text("Save target") }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } })
    if (importTarget) TuningMeasurementImportDialog(TuningMeasurementSlot.TARGET, null, onDismiss = { importTarget = false }) { curve ->
        importTarget = false
        action { store.saveTuningTarget(withContext(Dispatchers.Default) { TuningTargetCatalog.fromCurve(curve) }).getOrThrow() }
    }
    edit?.let { target -> TuningProvenanceDialog(target.curve, onDismiss = { edit = null }) { curve ->
        edit = null
        action { store.saveTuningTarget(target.copy(name = curve.name, curve = curve)).getOrThrow() }
    } }
    delete?.let { target -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${target.name}?") },
        text = { Text("Projects keep their saved target copy.") },
        confirmButton = { TextButton(onClick = { delete = null; action { store.deleteTuningTarget(target.id).getOrThrow() } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
    selected?.let { curve -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Use ${curve.name}?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Target rig: ${curve.provenance.rig.ifBlank { "Unspecified" }}")
            listOfNotNull(project?.measurementLeft, project?.measurementRight).forEach {
                Text("${it.name}: ${it.provenance.rig.ifBlank { "Rig unspecified" }}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Confirm that these measurement systems are compatible.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { selected = null; onUse(curve) }) { Text("Use target") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } }) }
}
