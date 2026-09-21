package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.data.FilterType
import com.aurora.music.data.ParsedEq
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackEqTextCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

@Composable
internal fun RackEqImportDialog(rack: ProcessingRack, onDismiss: () -> Unit,
    onAppend: suspend (ParsedEq, String) -> Result<Unit>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(appString(R.string.text_imported_eq_1d62f7)) }
    var parsed by remember { mutableStateOf<ParsedEq?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun parseText(source: String) {
        RackEqTextCodec.parse(source).onSuccess { parsed = it; error = null }
            .onFailure { parsed = null; error = it.message ?: appString(R.string.text_could_not_read_this_eq_text_b560e5) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val source = withContext(Dispatchers.IO) {
                        val displayName = runCatching {
                            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            }
                        }.getOrNull()
                        val content = context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= RackEqTextCodec.MAX_TEXT_BYTES) { appString(R.string.text_eq_text_must_be_256_kib_or_smaller_7631fb) }
                                output.write(buffer, 0, count)
                            }
                            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                        } ?: throw IllegalStateException(appString(R.string.text_could_not_open_the_selected_file_d412e6))
                        content to displayName
                    }
                    text = source.first
                    source.second?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotBlank() }?.let { name = it.take(80) }
                    parseText(source.first)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    parsed = null
                    error = if (failure is java.nio.charset.CharacterCodingException) appString(R.string.text_choose_a_utf_8_text_file_9ed3dc) else
                        failure.message ?: appString(R.string.text_could_not_read_the_selected_file_ec63f7)
                } finally { busy = false }
            }
        }
    }
    val preview = parsed
    val appendPlan = remember(rack, preview, name) {
        preview?.let { RackEqTextCodec.appendToRack(rack, it, name) }
    }
    val planError = appendPlan?.exceptionOrNull()?.message
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(appString(R.string.text_import_parametric_eq_658f13)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (preview == null) {
            Text(appString(R.string.text_open_equalizer_apo_text_autoeq_peq_json_or_aurora_eq_json_9a6150),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                runCatching { picker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }
                    .onFailure { error = appString(R.string.text_no_file_picker_is_available_fb12a4) }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_open_eq_text_file_33f801)) }
            OutlinedTextField(text, { candidate ->
                if (candidate.length <= RackEqTextCodec.MAX_TEXT_BYTES && candidate.toByteArray(Charsets.UTF_8).size <= RackEqTextCodec.MAX_TEXT_BYTES) {
                    text = candidate; parsed = null; error = null
                } else { parsed = null; error = appString(R.string.text_eq_text_must_be_256_kib_or_smaller_7631fb) }
            }, label = { Text(appString(R.string.text_eq_text_7e59d7)) }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 6, enabled = !busy,
                placeholder = { Text("Preamp: -3 dB\nFilter 1: ON PK Fc 1000 Hz Gain 2 dB Q 1") })
            } else TextButton(onClick = { parsed = null; error = null }, enabled = !busy) { Text(appString(R.string.text_edit_source_text_f113e8)) }
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(appString(R.string.text_name_for_new_stages_1e46f9)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (preview != null) {
                Text(appString(R.string.text_import_preview_fabb5c), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(appString(R.string.text_parametric_bands_preamp_6cb314, (preview.bands.size), (eqDb(preview.preampDb.toDouble()))), style = MaterialTheme.typography.bodyMedium)
                Text(if (preview.preampDb == 0f) appString(R.string.text_adds_one_equalizer_stage_at_the_end_of_the_rack_fdf7b1) else
                    appString(R.string.text_adds_gain_equalizer_at_the_end_of_the_rack_5266e9, (eqDb(preview.preampDb.toDouble()))), style = MaterialTheme.typography.bodySmall)
                Text(if (rack.enabled) appString(R.string.text_this_rack_is_active_appended_stages_will_affect_playback_92bdc8) else
                    appString(R.string.text_the_rack_stays_inactive_until_you_select_rack_mode_44737b), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    preview.bands.forEachIndexed { index, band ->
                        Text("${index + 1}. ${eqFilterName(band.type)} · ${eqFrequency(band.freqHz.toDouble())} · ${eqDb(band.gainDb.toDouble())} · Q ${eqNumber(band.q.toDouble())}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!busy) (error ?: planError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && text.isNotBlank() && (preview == null || appendPlan?.isSuccess == true), onClick = {
            if (preview == null) parseText(text)
            else {
                busy = true
                scope.launch {
                    try {
                        onAppend(preview, name).onSuccess { onDismiss() }.onFailure {
                            error = it.message ?: appString(R.string.text_could_not_append_these_stages_the_rack_was_not_changed_f1638c)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: appString(R.string.text_could_not_append_these_stages_f06d11) }
                    finally { busy = false }
                }
            }
        }) { Text(if (preview == null) appString(R.string.text_preview_f1fbb2) else appString(R.string.text_append_stages_b1532d)) }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

@Composable
internal fun RackEqExportDialog(node: ProcessingRackNode, onDismiss: () -> Unit,
    onSaveText: (String) -> Unit, onOpenPresets: () -> Unit) {
    val encoded = remember(node) { runCatching { RackEqTextCodec.encode(ParsedEq(0f, node.audio.dspParametric)) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.text_export_parametric_eq_text_39190f)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(node.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(appString(R.string.text_exports_parametric_filters_with_preamp_0_db_0ec77d, (node.audio.dspParametric.size)), style = MaterialTheme.typography.bodyMedium)
            Text(appString(R.string.text_parametric_bands_only_use_a_processing_preset_for_routing_and_the_64f11f),
                style = MaterialTheme.typography.bodySmall)
            if (node.audio.dspParametric.any { it.type >= FilterType.TILT.code }) Text(appString(R.string.text_exports_aurora_eq_json_for_these_filter_types_368863), style = MaterialTheme.typography.bodySmall)
            if (node.audio.dspGraphicBands.any { it != 0f }) Text(appString(R.string.text_this_stage_has_nonzero_graphic_eq_settings_that_the_text_file_wil_878b50),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            encoded.exceptionOrNull()?.let { Text(it.message ?: appString(R.string.text_these_filters_cannot_be_represented_in_the_eq_text_format_025451),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onOpenPresets) { Text(appString(R.string.text_open_full_processing_presets_8abd2a)) }
        }
    }, confirmButton = { TextButton(onClick = { onSaveText(encoded.getOrThrow()) }, enabled = encoded.isSuccess) { Text(appString(R.string.text_save_text_file_5aabc3)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

internal fun eqNumber(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
internal fun eqDb(value: Double): String = String.format(Locale.getDefault(), appString(R.string.text_2f_db_3bd998), value)
internal fun eqFrequency(value: Double): String = if (value >= 1_000) appString(R.string.text_khz_dd177d, (eqNumber(value / 1_000))) else appString(R.string.text_hz_648ee5, (eqNumber(value)))
private fun eqFilterName(type: Int): String = FilterType.fromLegacy(type).label
