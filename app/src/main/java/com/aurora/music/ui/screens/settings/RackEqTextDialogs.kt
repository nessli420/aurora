package com.aurora.music.ui.screens.settings

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
    var name by remember { mutableStateOf("Imported EQ") }
    var parsed by remember { mutableStateOf<ParsedEq?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun parseText(source: String) {
        RackEqTextCodec.parse(source).onSuccess { parsed = it; error = null }
            .onFailure { parsed = null; error = it.message ?: "Could not read this EQ text." }
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
                                require(output.size() + count <= RackEqTextCodec.MAX_TEXT_BYTES) { "EQ text must be 256 KiB or smaller." }
                                output.write(buffer, 0, count)
                            }
                            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                        } ?: throw IllegalStateException("Could not open the selected file.")
                        content to displayName
                    }
                    text = source.first
                    source.second?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotBlank() }?.let { name = it.take(80) }
                    parseText(source.first)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    parsed = null
                    error = if (failure is java.nio.charset.CharacterCodingException) "Choose a UTF-8 text file." else
                        failure.message ?: "Could not read the selected file."
                } finally { busy = false }
            }
        }
    }
    val preview = parsed
    val appendPlan = remember(rack, preview, name) {
        preview?.let { RackEqTextCodec.appendToRack(rack, it, name) }
    }
    val planError = appendPlan?.exceptionOrNull()?.message
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Import parametric EQ") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (preview == null) {
            Text("Open or paste Equalizer APO / AutoEq text with Preamp and PK, LSC or HSC filters. Each filter needs frequency, gain and Q.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                runCatching { picker.launch(arrayOf("text/*", "application/octet-stream")) }
                    .onFailure { error = "No file picker is available." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open EQ text file") }
            OutlinedTextField(text, { candidate ->
                if (candidate.length <= RackEqTextCodec.MAX_TEXT_BYTES && candidate.toByteArray(Charsets.UTF_8).size <= RackEqTextCodec.MAX_TEXT_BYTES) {
                    text = candidate; parsed = null; error = null
                } else { parsed = null; error = "EQ text must be 256 KiB or smaller." }
            }, label = { Text("EQ text") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 6, enabled = !busy,
                placeholder = { Text("Preamp: -3 dB\nFilter 1: ON PK Fc 1000 Hz Gain 2 dB Q 1") })
            } else TextButton(onClick = { parsed = null; error = null }, enabled = !busy) { Text("Edit source text") }
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name for new stages") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (preview != null) {
                Text("Import preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("${preview.bands.size} parametric bands · preamp ${eqDb(preview.preampDb.toDouble())}", style = MaterialTheme.typography.bodyMedium)
                Text(if (preview.preampDb == 0f) "Adds one Equalizer stage at the end of the rack." else
                    "Adds Gain (${eqDb(preview.preampDb.toDouble())}) → Equalizer at the end of the rack.", style = MaterialTheme.typography.bodySmall)
                Text(if (rack.enabled) "This rack is active; appended stages will affect playback." else
                    "The rack stays inactive until you select Rack mode.", style = MaterialTheme.typography.bodySmall)
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
                            error = it.message ?: "Could not append these stages. The rack was not changed."
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Could not append these stages." }
                    finally { busy = false }
                }
            }
        }) { Text(if (preview == null) "Preview" else "Append stages") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
internal fun RackEqExportDialog(node: ProcessingRackNode, onDismiss: () -> Unit,
    onSaveText: (String) -> Unit, onOpenPresets: () -> Unit) {
    val encoded = remember(node) { runCatching { RackEqTextCodec.encode(ParsedEq(0f, node.audio.dspParametric)) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Export parametric EQ text") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(node.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Exports ${node.audio.dspParametric.size} parametric filters with Preamp: 0 dB.", style = MaterialTheme.typography.bodyMedium)
            Text("Graphic EQ, channel routing, wet/dry, bypass, gain and other stages are not included. Use a processing preset to save the complete sound settings.",
                style = MaterialTheme.typography.bodySmall)
            if (node.audio.dspGraphicBands.any { it != 0f }) Text("This stage has nonzero graphic EQ settings that the text file will omit.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            encoded.exceptionOrNull()?.let { Text(it.message ?: "These filters cannot be represented in the EQ text format.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onOpenPresets) { Text("Open full processing presets") }
        }
    }, confirmButton = { TextButton(onClick = { onSaveText(encoded.getOrThrow()) }, enabled = encoded.isSuccess) { Text("Save text file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

internal fun eqNumber(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
internal fun eqDb(value: Double): String = String.format(Locale.getDefault(), "%+.2f dB", value)
internal fun eqFrequency(value: Double): String = if (value >= 1_000) "${eqNumber(value / 1_000)} kHz" else "${eqNumber(value)} Hz"
private fun eqFilterName(type: Int): String = when (type) { 1 -> "Low shelf"; 2 -> "High shelf"; else -> "Peak" }
