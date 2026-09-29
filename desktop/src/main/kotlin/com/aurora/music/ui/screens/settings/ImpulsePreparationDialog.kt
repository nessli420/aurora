package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
            catch (failure: Exception) { error = failure.message ?: appString(R.string.text_could_not_prepare_this_ir_3f250b) }
            finally { busy = false }
        }
    }
    LaunchedEffect(entry.id) { loadPreview(initial) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(appString(R.string.text_prepare_impulse_828a45)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(appString(R.string.text_original_frames_26e777, (frames), (entry.sourceMetadata.summary())), style = MaterialTheme.typography.bodySmall)
            Text(appString(R.string.text_creates_a_32_bit_float_copy_d569be), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it; changed() }, label = { Text(appString(R.string.text_start_frame_71c6c5)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it; changed() }, label = { Text(appString(R.string.text_end_frame_e4064a)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy, modifier = Modifier.weight(1f))
            }
            Text(appString(R.string.text_start_included_end_excluded_a1dbff), style = MaterialTheme.typography.labelSmall)
            val sliderStart = (startFrame ?: 0).coerceIn(0, frames - 1)
            val sliderEnd = (endFrame ?: frames).coerceIn(sliderStart + 1, frames)
            RangeSlider(value = sliderStart.toFloat()..sliderEnd.toFloat(), onValueChange = { range ->
                val left = range.start.roundToInt().coerceIn(0, frames - 1)
                start = left.toString()
                end = range.endInclusive.roundToInt().coerceIn(left + 1, frames).toString()
                changed()
            }, valueRange = 0f..frames.toFloat(), enabled = !busy,
                modifier = Modifier.semantics { contentDescription = appString(R.string.text_trim_range_in_source_frames_d2159a) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.text_normalize_peak_to_1_db_06fc92), style = MaterialTheme.typography.titleSmall)
                    if (entry.sourceMetadata.channels > 1) Text(appString(R.string.text_one_gain_for_all_paths_f4bd76), style = MaterialTheme.typography.bodySmall)
                }
                Switch(normalize, onCheckedChange = { normalize = it; changed() }, enabled = !busy)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.text_minimum_phase_500a84), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(minimumPhase, onCheckedChange = { minimumPhase = it; changed() }, enabled = !busy)
            }
            if (entry.sourceMetadata.channels == 4 && minimumPhase) Text(appString(R.string.text_converts_each_matrix_path_independently_be282e), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(delay, { delay = it; changed() }, label = { Text(appString(R.string.text_delay_ms_3f4bc4)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (options != null) Text(appString(R.string.text_frames_ms_753397, (options.endFrameExclusive - options.startFrame + options.delayFrames), (impulseNumber((options.endFrameExclusive - options.startFrame + options.delayFrames) * 1000.0 / entry.sourceMetadata.sampleRate))),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && options != null, onClick = { options?.let(::loadPreview) }, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_preview_f1fbb2)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            preview?.let { result ->
                ImpulseWaveform(result)
                Text(appString(R.string.text_peak_932437, (result.metadata.peak.peakLabel())), style = MaterialTheme.typography.bodySmall)
            }
            if (options == null) Text(appString(R.string.text_enter_a_range_within_the_original_frames_8c9532), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && options != null && options == reviewed && preview != null, onClick = {
        val submitted = reviewed ?: return@TextButton
        busy = true
        scope.launch {
            try { onSave(submitted) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: appString(R.string.text_could_not_save_the_variant_8badb5) }
            finally { busy = false }
        }
    }) { Text(appString(R.string.text_save_variant_35fac3)) } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
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
    val labels = when (channels.size) { 4 -> listOf("LL", "LR", "RL", "RR"); 2 -> listOf(appString(R.string.text_left_8ae1c3), appString(R.string.text_right_954daa)); else -> listOf(appString(R.string.text_mono_c5c553)) }
    val peak = remember(preview) { channels.flatten().maxOfOrNull { max(abs(it.min), abs(it.max)) }?.coerceAtLeast(0.000001) ?: 1.0 }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        channels.forEachIndexed { index, bins ->
            Text(labels[index], style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(72.dp).semantics {
                contentDescription = appString(R.string.text_impulse_waveform_frames_112fd2, (labels[index]), (preview.metadata.frames))
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
            Text(appString(R.string.text_0_ms_3faf04), style = MaterialTheme.typography.labelSmall)
            Text(appString(R.string.text_ms_1191ce, (impulseNumber(preview.metadata.frames * 1000.0 / preview.metadata.sampleRate))), style = MaterialTheme.typography.labelSmall)
        }
    }
}
