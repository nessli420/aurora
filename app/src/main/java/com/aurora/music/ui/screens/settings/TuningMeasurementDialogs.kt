package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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

internal enum class TuningMeasurementSlot(@androidx.annotation.StringRes private val labelRes: Int) {
    LEFT(R.string.text_left_measurement_4f65e1), RIGHT(R.string.text_right_measurement_fbacf6), TARGET(R.string.text_custom_target_a34c83);
    val label: String get() = appString(labelRes)
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
            .onFailure { parsed = null; error = it.message ?: appString(R.string.text_could_not_parse_this_measurement_29d1d2) }
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
                catch (failure: Exception) { error = failure.message ?: appString(R.string.text_could_not_read_this_measurement_301a3c); parsed = null }
                finally { busy = false }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (correctionImport) appString(R.string.text_import_correction_curve_1f6dae) else appString(R.string.text_import_1219ef, (slot.label.lowercase()))) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val preview = parsed
            if (preview == null) {
                TuningChoiceRow(appString(R.string.text_format_041a5d), formats.map { it.label }, formats.indexOf(format)) { format = formats[it]; error = null }
                if (correctionImport) Text(appString(R.string.text_creates_a_parametric_fit_project_the_curve_is_not_a_measurement_f425ac), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { runCatching { picker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }
                    .onFailure { error = appString(R.string.text_no_file_picker_is_available_fb12a4) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_open_measurement_file_30741a)) }
                OutlinedTextField(source, { text ->
                    if (text.length <= MeasurementTextImporter.MAX_TEXT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MeasurementTextImporter.MAX_TEXT_BYTES) {
                        source = text; error = null
                    } else error = appString(R.string.text_measurement_text_must_be_512_kib_or_smaller_36d9de)
                }, label = { Text(appString(R.string.text_paste_measurement_text_f87e1b)) }, minLines = 4, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(appString(R.string.text_measurement_name_18f280)) }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (format == TuningCurveFormat.TEXT || format == TuningCurveFormat.SQUIG) SettingsSwitchRow(title = appString(R.string.text_third_column_is_phase_bf98b7), subtitle = appString(R.string.text_unlabeled_phase_values_must_be_in_degrees_3b36ee),
                    checked = phaseColumn, onCheckedChange = { if (!busy) { phaseColumn = it; error = null } })
            } else {
                TextButton(onClick = { parsed = null }, enabled = !busy) { Text(appString(R.string.text_edit_source_text_f113e8)) }
                Text(preview.name, style = MaterialTheme.typography.titleMedium)
                Text(appString(R.string.text_points_d46e78, (preview.points.size), (eqFrequency(preview.points.first().frequencyHz)), (eqFrequency(preview.points.last().frequencyHz))),
                    style = MaterialTheme.typography.bodyMedium)
                if (preview.points.any { it.phaseDegrees != null }) Text(appString(R.string.text_phase_is_saved_but_not_fitted_3bc04a), style = MaterialTheme.typography.bodySmall)
                if (previous != null) Text(appString(R.string.text_replaces_and_clears_the_generated_correction_a7baba, (slot.label.lowercase())), style = MaterialTheme.typography.bodySmall)
                TuningCurveChart(preview.points.map { it.frequencyHz }.toDoubleArray(), listOf(TuningPlotCurve(appString(R.string.text_imported_magnitude_269bf4),
                    preview.points.map { it.magnitudeDb }.toDoubleArray())), "")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && source.isNotBlank() && name.isNotBlank(), onClick = {
        val preview = parsed
        if (preview == null) parse() else onAccept(preview)
    }) { Text(if (parsed == null) appString(R.string.text_preview_f1fbb2) else appString(R.string.text_use_5cc455, (if (slot == TuningMeasurementSlot.TARGET) "target" else "measurement"))) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(appString(R.string.text_cancel_77dfd2)) } })
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
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(appString(R.string.text_measurement_details_edeee9)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(appString(R.string.text_name_709a23)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(rig, { rig = it.take(1024) }, label = { Text(appString(R.string.text_rig_microphone_coupler_ccd9a6)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3, enabled = !busy)
            OutlinedTextField(source, { source = it.take(1024) }, label = { Text(appString(R.string.text_source_author_f7d881)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3, enabled = !busy)
            OutlinedTextField(notes, { notes = it.take(4096) }, label = { Text(appString(R.string.text_position_calibration_notes_57c1cf)) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, enabled = !busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = {
        val next = curve.copy(name = name.trim(), provenance = MeasurementProvenance(rig, source, notes))
        busy = true
        scope.launch {
            try { onSave(withContext(Dispatchers.Default) { TuningProjectCodec.validateCurve(next) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: appString(R.string.text_could_not_save_measurement_details_5fd202) }
            finally { busy = false }
        }
    }) { Text(if (busy) appString(R.string.text_validating_c07434) else appString(R.string.text_save_details_470658)) } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

internal suspend fun readTuningText(context: Context, uri: Uri, limitBytes: Int): Pair<String, String?> = withContext(Dispatchers.IO) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException(appString(R.string.text_could_not_open_the_selected_file_d412e6))
    val text = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limitBytes) { appString(R.string.text_file_exceeds_the_supported_kib_limit_120a5e, (limitBytes / 1024)) }
            output.write(buffer, 0, count)
        }
        try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException(appString(R.string.text_choose_a_utf_8_text_file_9ed3dc)) }
    }
    text to name
}
