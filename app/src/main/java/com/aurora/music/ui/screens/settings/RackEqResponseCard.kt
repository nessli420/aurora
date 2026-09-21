package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackEqChannel
import com.aurora.music.playback.engine.EqResponse
import com.aurora.music.playback.engine.EqResponseCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Calculated response of one dedicated EQ stage, from the production coefficient bank. */
@Composable
internal fun RackEqResponseCard(node: ProcessingRackNode, decoderRate: Int?, rackEnabled: Boolean) {
    val sampleRate = decoderRate?.takeIf { it in 8_000..768_000 } ?: 48_000
    var response by remember { mutableStateOf<EqResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var view by rememberSaveable(node.id) { mutableIntStateOf(0) }
    var selectedPoint by rememberSaveable(node.id) { mutableIntStateOf(289) }
    LaunchedEffect(node, sampleRate) {
        response = null
        error = null
        try { response = withContext(Dispatchers.Default) { EqResponseCalculator.calculateNode(node, sampleRate) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: appString(R.string.text_could_not_calculate_this_eq_response_223c17) }
    }
    SettingsGroup {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(appString(R.string.text_calculated_stage_response_afc25a), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (decoderRate != null) appString(R.string.text_current_decoder_sample_rate_6f675e, (eqFrequency(sampleRate.toDouble()))) else
                appString(R.string.text_48_khz_preview_decoder_sample_rate_unavailable_7020e9), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(appString(R.string.text_graphic_and_parametric_eq_including_this_stage_s_wet_dry_and_bypa_5f1367),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (node.eqChannel != RackEqChannel.BOTH) Text(
                if (node.eqChannel == RackEqChannel.LEFT) appString(R.string.text_left_channel_only_right_passes_unchanged_681627) else appString(R.string.text_right_channel_only_left_passes_unchanged_6e2bfe),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (!rackEnabled) Text(appString(R.string.text_rack_inactive_preview_only_41e71b), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary)
        }
        SegmentedRow(appString(R.string.text_response_6e617e), listOf(appString(R.string.text_magnitude_f2c18d), appString(R.string.text_phase_f6371a), appString(R.string.text_group_delay_e59469)), view) { view = it }
        val data = response
        when {
            error != null -> Text(requireNotNull(error), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            data == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
            else -> {
                val values = when (view) { 1 -> data.phaseDegrees; 2 -> data.groupDelayMs; else -> data.magnitudeDb }
                val unit = when (view) { 1 -> "°"; 2 -> "ms"; else -> appString(R.string.text_db_e44622) }
                val point = selectedPoint.coerceIn(data.frequenciesHz.indices)
                val bounds = remember(data, view) { responseBounds(values, view) }
                val valueLabel = if (values[point].isFinite()) "${responseNumber(values[point], view)} $unit" else appString(R.string.text_undefined_at_response_null_7b5427)
                val frequencyLabel = eqFrequency(data.frequenciesHz[point])
                Text(when (view) { 1 -> appString(R.string.text_unwrapped_phase_degrees_7f6535); 2 -> appString(R.string.text_group_delay_milliseconds_8fe73b); else -> appString(R.string.text_magnitude_db_573817) },
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                ResponsePlot(data, values, point, bounds, view, "$frequencyLabel: $valueLabel") { selectedPoint = it }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(frequencyLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(valueLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Slider(value = point.toFloat(), onValueChange = { selectedPoint = it.roundToInt() },
                        valueRange = 0f..data.frequenciesHz.lastIndex.toFloat(),
                        modifier = Modifier.semantics { contentDescription = appString(R.string.text_selected_response_frequency_0f1ae3, (frequencyLabel), (valueLabel)) })
                    Text(appString(R.string.text_tap_the_graph_or_adjust_the_frequency_slider_to_inspect_a_point_f_1ff543),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (view == 2) Text(appString(R.string.text_this_is_the_eq_s_group_delay_it_does_not_measure_device_or_playba_31e689),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (view != 0) Text(appString(R.string.text_gaps_mark_undefined_values_below_db_eb6309, (eqNumber(data.phaseUndefinedBelowDb))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(data.scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ResponsePlot(response: EqResponse, values: DoubleArray, selected: Int,
    bounds: Pair<Double, Double>, view: Int, description: String, onSelect: (Int) -> Unit) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.tertiary
    val bottom = bounds.first
    val top = bounds.second
    Column(Modifier.padding(start = 12.dp, end = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(200.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(70.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(responseNumber(top, view), style = MaterialTheme.typography.labelSmall)
                Text(responseNumber((top + bottom) / 2, view), style = MaterialTheme.typography.labelSmall)
                Text(responseNumber(bottom, view), style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = appString(R.string.text_calculated_eq_response_6dd321, (description)) }
                .pointerInput(values.size) {
                    detectTapGestures { point ->
                        onSelect((point.x / size.width.coerceAtLeast(1) * values.lastIndex).roundToInt().coerceIn(values.indices))
                    }
                }) {
                repeat(5) { grid ->
                    val fraction = grid / 4f
                    drawLine(gridColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), 1.dp.toPx())
                    drawLine(gridColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), 1.dp.toPx())
                }
                fun x(index: Int) = size.width * index / values.lastIndex.toFloat()
                fun y(value: Double) = (size.height * (1.0 - (value - bottom) / (top - bottom))).toFloat()
                if (bottom < 0.0 && top > 0.0) drawLine(gridColor, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 2.dp.toPx())
                val curve = Path()
                var drawing = false
                values.forEachIndexed { index, value ->
                    if (value.isFinite()) {
                        if (drawing) curve.lineTo(x(index), y(value)) else curve.moveTo(x(index), y(value))
                        drawing = true
                    } else drawing = false
                }
                drawPath(curve, lineColor, style = Stroke(2.dp.toPx()))
                drawLine(markerColor.copy(alpha = .65f), Offset(x(selected), 0f), Offset(x(selected), size.height), 1.dp.toPx())
                if (values[selected].isFinite()) drawCircle(markerColor, 4.dp.toPx(), Offset(x(selected), y(values[selected])))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 70.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortFrequency(response.minFrequencyHz), style = MaterialTheme.typography.labelSmall)
            Text(shortFrequency(sqrt(response.minFrequencyHz * response.maxFrequencyHz)), style = MaterialTheme.typography.labelSmall)
            Text(shortFrequency(response.maxFrequencyHz), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun responseBounds(values: DoubleArray, view: Int): Pair<Double, Double> {
    val finite = values.filter { it.isFinite() }
    val minimum = minOf(0.0, finite.minOrNull() ?: 0.0)
    val maximum = maxOf(0.0, finite.maxOrNull() ?: 0.0)
    val step = when (view) {
        1 -> 30.0
        2 -> if (maximum - minimum < 1e-10) .1 else 10.0.pow(floor(log10(maximum - minimum)))
        else -> 6.0
    }
    val bottom = floor(minimum / step) * step
    val top = ceil(maximum / step) * step
    return if (top - bottom < step) bottom - step to top + step else bottom to top
}

private fun responseNumber(value: Double, view: Int): String = if (view == 2)
    String.format(Locale.getDefault(), "%.4g", value) else eqNumber(value)
private fun shortFrequency(value: Double): String = if (value >= 1_000) appString(R.string.text_k_hz_b3d9ce, ((value / 1_000).roundToInt())) else appString(R.string.text_hz_648ee5, (value.roundToInt()))
