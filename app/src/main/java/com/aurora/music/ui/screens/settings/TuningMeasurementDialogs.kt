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
    onDismiss: () -> Unit, correctionImport: Boolean = false, onAccept: (MeasurementCurve) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(slot.label) }
    val formats = if (correctionImport) listOf(TuningCurveFormat.WAVELET) else if (slot == TuningMeasurementSlot.TARGET)
        listOf(TuningCurveFormat.TEXT, TuningCurveFormat.SQUIG, TuningCurveFormat.AUTOEQ_TARGET, TuningCurveFormat.AUTOEQ_CSV, TuningCurveFormat.AUTOEQ_RAW)
        else listOf(TuningCurveFormat.TEXT, TuningCurveFormat.SQUIG, TuningCurveFormat.AUTOEQ_RAW)
    var format by remember { mutableStateOf(formats.first()) }
    var phaseColumn by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<MeasurementCurve?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun preparePreview() {
        val textSnapshot = source; val nameSnapshot = name; val phaseSnapshot = phaseColumn
        withContext(Dispatchers.Default) {
            TuningCurveAdapters.parse(textSnapshot, nameSnapshot, format, phaseSnapshot, previous?.provenance ?: MeasurementProvenance(), System.currentTimeMillis())
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
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (correctionImport) "Import correction curve" else "Import ${slot.label.lowercase()}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val preview = parsed
            if (preview == null) {
                TuningChoiceRow("Format", formats.map { it.label }, formats.indexOf(format)) { format = formats[it]; error = null }
                if (correctionImport) Text("Creates a parametric fit project. The curve is not a measurement.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { runCatching { picker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }
                    .onFailure { error = "No file picker is available." } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open measurement file") }
                OutlinedTextField(source, { text ->
                    if (text.length <= MeasurementTextImporter.MAX_TEXT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MeasurementTextImporter.MAX_TEXT_BYTES) {
                        source = text; error = null
                    } else error = "Measurement text must be 512 KiB or smaller."
                }, label = { Text("Paste measurement text") }, minLines = 4, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Measurement name") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (format == TuningCurveFormat.TEXT || format == TuningCurveFormat.SQUIG) SettingsSwitchRow(title = "Third column is phase", subtitle = "Unlabeled phase values must be in degrees.",
                    checked = phaseColumn, onCheckedChange = { if (!busy) { phaseColumn = it; error = null } })
            } else {
                TextButton(onClick = { parsed = null }, enabled = !busy) { Text("Edit source text") }
                Text(preview.name, style = MaterialTheme.typography.titleMedium)
                Text("${preview.points.size} points · ${eqFrequency(preview.points.first().frequencyHz)}–${eqFrequency(preview.points.last().frequencyHz)}",
                    style = MaterialTheme.typography.bodyMedium)
                if (preview.points.any { it.phaseDegrees != null }) Text("Phase is saved but not fitted.", style = MaterialTheme.typography.bodySmall)
                if (previous != null) Text("Replaces ${slot.label.lowercase()} and clears the generated correction.", style = MaterialTheme.typography.bodySmall)
                TuningCurveChart(preview.points.map { it.frequencyHz }.toDoubleArray(), listOf(TuningPlotCurve("Imported magnitude",
                    preview.points.map { it.magnitudeDb }.toDoubleArray())), "")
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
