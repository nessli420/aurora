package com.aurora.music.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aurora.music.data.tuning.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal enum class TuningMeasurementSlot(val label: String) {
    LEFT("Left measurement"), RIGHT("Right measurement"), TARGET("Custom target")
}

@Composable
internal fun TuningMeasurementImportDialog(slot: TuningMeasurementSlot, previous: MeasurementCurve?,
    onDismiss: () -> Unit, onAccept: (MeasurementCurve) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(slot.label) }
    var phaseColumn by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<MeasurementCurve?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun preparePreview() {
        val textSnapshot = source; val nameSnapshot = name; val phaseSnapshot = phaseColumn
        withContext(Dispatchers.Default) {
            MeasurementTextImporter.parse(textSnapshot, nameSnapshot, phaseSnapshot, previous?.provenance ?: MeasurementProvenance(), System.currentTimeMillis())
        }.onSuccess { parsed = it; error = null }
            .onFailure { parsed = null; error = it.message ?: "Could not parse this measurement." }
    }
    fun parse() {
        if (busy) return
        busy = true
        scope.launch {
            try { preparePreview() }
            catch (cancelled: CancellationException) { throw cancelled }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val read = readTuningText(context, uri, MeasurementTextImporter.MAX_TEXT_BYTES)
                    source = read.first
                    read.second?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }?.let { name = it.take(80) }
                    preparePreview()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Could not read this measurement."; parsed = null }
                finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Import ${slot.label.lowercase()}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val preview = parsed
            if (preview == null) {
                Text("One measurement per import: frequency in Hz, magnitude in dB, and optional phase in degrees. Ascending frequencies; decimal points; whitespace, comma or semicolon columns.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { runCatching { picker.launch(arrayOf("text/*", "application/octet-stream")) }
                    .onFailure { error = "No file picker is available." } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open measurement file") }
                OutlinedTextField(source, { text ->
                    if (text.length <= MeasurementTextImporter.MAX_TEXT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MeasurementTextImporter.MAX_TEXT_BYTES) {
                        source = text; error = null
                    } else error = "Measurement text must be 512 KiB or smaller."
                }, label = { Text("Paste measurement text") }, minLines = 4, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Measurement name") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                SettingsSwitchRow(title = "Third column is phase", subtitle = "Confirm degrees for an unlabeled third column; it is not another channel",
                    checked = phaseColumn, onCheckedChange = { if (!busy) { phaseColumn = it; error = null } })
            } else {
                TextButton(onClick = { parsed = null }, enabled = !busy) { Text("Edit source text") }
                Text(preview.name, style = MaterialTheme.typography.titleMedium)
                Text("${preview.points.size} points · ${eqFrequency(preview.points.first().frequencyHz)}–${eqFrequency(preview.points.last().frequencyHz)}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Destination: ${slot.label}. Magnitude values stay in dB exactly as imported. No channels are combined here.", style = MaterialTheme.typography.bodySmall)
                if (preview.points.any { it.phaseDegrees != null }) Text("Phase is retained with the source. Fitting uses magnitude response only.", style = MaterialTheme.typography.bodySmall)
                if (previous != null) Text("This replaces the project's current ${slot.label.lowercase()} and clears its generated correction.", style = MaterialTheme.typography.bodySmall)
                TuningCurveChart(preview.points.map { it.frequencyHz }.toDoubleArray(), listOf(TuningPlotCurve("Imported magnitude",
                    preview.points.map { it.magnitudeDb }.toDoubleArray())), "Imported measurement magnitude; this preview does not verify the measuring rig.")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && source.isNotBlank() && name.isNotBlank(), onClick = {
        val preview = parsed
        if (preview == null) parse() else onAccept(preview)
    }) { Text(if (parsed == null) "Preview" else "Use ${if (slot == TuningMeasurementSlot.TARGET) "target" else "measurement"}") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
internal fun TuningProvenanceDialog(curve: MeasurementCurve, onDismiss: () -> Unit, onSave: (MeasurementCurve) -> Unit) {
    var name by remember { mutableStateOf(curve.name) }
    var rig by remember { mutableStateOf(curve.provenance.rig) }
    var source by remember { mutableStateOf(curve.provenance.source) }
    var notes by remember { mutableStateOf(curve.provenance.notes) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Measurement details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(rig, { rig = it.take(1024) }, label = { Text("Rig / microphone / coupler") }, modifier = Modifier.fillMaxWidth(), maxLines = 3, enabled = !busy)
            OutlinedTextField(source, { source = it.take(1024) }, label = { Text("Source / author") }, modifier = Modifier.fillMaxWidth(), maxLines = 3, enabled = !busy)
            OutlinedTextField(notes, { notes = it.take(4096) }, label = { Text("Position, calibration & notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = {
        val next = curve.copy(name = name.trim(), provenance = MeasurementProvenance(rig, source, notes))
        busy = true
        scope.launch {
            try { onSave(withContext(Dispatchers.Default) { TuningProjectCodec.validateCurve(next) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not save measurement details." }
            finally { busy = false }
        }
    }) { Text(if (busy) "Validating…" else "Save details") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

internal suspend fun readTuningText(context: Context, uri: Uri, limitBytes: Int): Pair<String, String?> = withContext(Dispatchers.IO) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open the selected file.")
    val text = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limitBytes) { "File exceeds the supported ${limitBytes / 1024} KiB limit." }
            output.write(buffer, 0, count)
        }
        try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Choose a UTF-8 text file.") }
    }
    text to name
}
