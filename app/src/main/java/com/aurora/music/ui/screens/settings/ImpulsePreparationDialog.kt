package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ir.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImpulsePreparationDialog(entry: ImpulseLibraryEntry, onDismiss: () -> Unit,
    onSave: suspend (ImpulsePreparation) -> Unit) {
    val initial = entry.prepared?.preparation ?: ImpulsePreparation(0, entry.sourceMetadata.frames)
    var start by remember { mutableStateOf(initial.startFrame.toString()) }
    var end by remember { mutableStateOf(initial.endFrameExclusive.toString()) }
    var normalize by remember { mutableStateOf(initial.normalization == ImpulseNormalization.PEAK_MINUS_1_DB) }
    var minimumPhase by remember { mutableStateOf(initial.minimumPhase) }
    var delay by remember { mutableStateOf(String.format(java.util.Locale.ROOT, "%.6f",
        initial.delayFrames * 1000.0 / entry.sourceMetadata.sampleRate).trimEnd('0').trimEnd('.')) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImpulsePreview?>(null) }
    var reviewed by remember { mutableStateOf<ImpulsePreparation?>(null) }
    val scope = rememberCoroutineScope()
    val frames = entry.sourceMetadata.frames
    val startFrame = start.toIntOrNull()
    val endFrame = end.toIntOrNull()
    val delayMs = delay.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }
    val options = if (startFrame != null && endFrame != null && delayMs != null && startFrame in 0 until frames && endFrame in startFrame + 1..frames) {
        ImpulsePreparation(startFrame, endFrame, if (normalize) ImpulseNormalization.PEAK_MINUS_1_DB else ImpulseNormalization.NONE,
            minimumPhase, (delayMs * entry.sourceMetadata.sampleRate / 1000.0).roundToInt())
    } else null
    fun changed() { preview = null; reviewed = null; error = null }
    fun loadPreview(submitted: ImpulsePreparation) {
        busy = true
        error = null
        scope.launch {
            try {
                preview = withContext(Dispatchers.IO) { ImpulseLibraryFiles.previewPrepared(entry, submitted).getOrThrow() }
                reviewed = submitted
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not prepare this IR." }
            finally { busy = false }
        }
    }
    LaunchedEffect(entry.id) { loadPreview(initial) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Prepare impulse") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Original: $frames frames · ${entry.sourceMetadata.summary()}", style = MaterialTheme.typography.bodySmall)
            Text("Creates a 32-bit float copy.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it; changed() }, label = { Text("Start frame") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it; changed() }, label = { Text("End frame") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy, modifier = Modifier.weight(1f))
            }
            Text("Start included. End excluded.", style = MaterialTheme.typography.labelSmall)
            val sliderStart = (startFrame ?: 0).coerceIn(0, frames - 1)
            val sliderEnd = (endFrame ?: frames).coerceIn(sliderStart + 1, frames)
            RangeSlider(value = sliderStart.toFloat()..sliderEnd.toFloat(), onValueChange = { range ->
                val left = range.start.roundToInt().coerceIn(0, frames - 1)
                start = left.toString()
                end = range.endInclusive.roundToInt().coerceIn(left + 1, frames).toString()
                changed()
            }, valueRange = 0f..frames.toFloat(), enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Trim range in source frames" })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Normalize peak to −1 dB", style = MaterialTheme.typography.titleSmall)
                    if (entry.sourceMetadata.channels > 1) Text("One gain for all paths.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(normalize, onCheckedChange = { normalize = it; changed() }, enabled = !busy)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Minimum phase", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(minimumPhase, onCheckedChange = { minimumPhase = it; changed() }, enabled = !busy)
            }
            if (entry.sourceMetadata.channels == 4 && minimumPhase) Text("Converts each matrix path independently.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(delay, { delay = it; changed() }, label = { Text("Delay (ms)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (options != null) Text("${options.endFrameExclusive - options.startFrame + options.delayFrames} frames · ${impulseNumber((options.endFrameExclusive - options.startFrame + options.delayFrames) * 1000.0 / entry.sourceMetadata.sampleRate)} ms",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && options != null, onClick = { options?.let(::loadPreview) }, modifier = Modifier.fillMaxWidth()) { Text("Preview") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            preview?.let { result ->
                ImpulseWaveform(result)
                Text("Peak: ${result.metadata.peak.peakLabel()}", style = MaterialTheme.typography.bodySmall)
            }
            if (options == null) Text("Enter a range within the original frames.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && options != null && options == reviewed && preview != null, onClick = {
        val submitted = reviewed ?: return@TextButton
        busy = true
        scope.launch {
            try { onSave(submitted) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not save the variant." }
            finally { busy = false }
        }
    }) { Text("Save variant") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun ImpulseWaveform(preview: ImpulsePreview) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val channels = when (preview.metadata.channels) {
        4 -> listOf(preview.left, preview.leftToRight, preview.rightToLeft, preview.right)
        2 -> listOf(preview.left, preview.right)
        else -> listOf(preview.left)
    }
    val labels = when (channels.size) { 4 -> listOf("LL", "LR", "RL", "RR"); 2 -> listOf("Left", "Right"); else -> listOf("Mono") }
    val peak = remember(preview) { channels.flatten().maxOfOrNull { max(abs(it.min), abs(it.max)) }?.coerceAtLeast(0.000001) ?: 1.0 }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        channels.forEachIndexed { index, bins ->
            Text(labels[index], style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(72.dp).semantics {
                contentDescription = "${labels[index]} impulse waveform, ${preview.metadata.frames} frames"
            }) {
                val center = size.height / 2f
                drawLine(axisColor, Offset(0f, center), Offset(size.width, center), 1.dp.toPx())
                bins.forEachIndexed { position, bin ->
                    val x = (position + 0.5f) * size.width / bins.size
                    val top = center - (bin.max / peak * center * 0.9).toFloat()
                    val bottom = center - (bin.min / peak * center * 0.9).toFloat()
                    val width = (size.width / bins.size).coerceAtLeast(1f)
                    if (bin.min == bin.max && bin.min != 0.0) {
                        drawCircle(colors[index % colors.size], radius = (width / 2f).coerceIn(1.dp.toPx(), 3.dp.toPx()), center = Offset(x, top))
                    } else drawLine(colors[index % colors.size], Offset(x, top), Offset(x, bottom), width)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 ms", style = MaterialTheme.typography.labelSmall)
            Text("${impulseNumber(preview.metadata.frames * 1000.0 / preview.metadata.sampleRate)} ms", style = MaterialTheme.typography.labelSmall)
        }
    }
}
