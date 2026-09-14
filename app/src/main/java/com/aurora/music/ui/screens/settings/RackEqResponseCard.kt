package com.aurora.music.ui.screens.settings

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
        catch (failure: Exception) { error = failure.message ?: "Could not calculate this EQ response." }
    }
    SettingsGroup {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Calculated stage response", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (decoderRate != null) "${eqFrequency(sampleRate.toDouble())} · current decoder sample rate" else
                "48 kHz preview · decoder sample rate unavailable", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Graphic and parametric EQ, including this stage's wet/dry and bypass settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (node.eqChannel != RackEqChannel.BOTH) Text(
                if (node.eqChannel == RackEqChannel.LEFT) "Left channel only · right passes unchanged" else "Right channel only · left passes unchanged",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (!rackEnabled) Text("Rack inactive · preview only", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary)
        }
        SegmentedRow("Response", listOf("Magnitude", "Phase", "Group delay"), view) { view = it }
        val data = response
        when {
            error != null -> Text(requireNotNull(error), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            data == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
            else -> {
                val values = when (view) { 1 -> data.phaseDegrees; 2 -> data.groupDelayMs; else -> data.magnitudeDb }
                val unit = when (view) { 1 -> "°"; 2 -> "ms"; else -> "dB" }
                val point = selectedPoint.coerceIn(data.frequenciesHz.indices)
                val bounds = remember(data, view) { responseBounds(values, view) }
                val valueLabel = if (values[point].isFinite()) "${responseNumber(values[point], view)} $unit" else "Undefined at response null"
                val frequencyLabel = eqFrequency(data.frequenciesHz[point])
                Text(when (view) { 1 -> "Unwrapped phase (degrees)"; 2 -> "Group delay (milliseconds)"; else -> "Magnitude (dB)" },
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                ResponsePlot(data, values, point, bounds, view, "$frequencyLabel: $valueLabel") { selectedPoint = it }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(frequencyLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(valueLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Slider(value = point.toFloat(), onValueChange = { selectedPoint = it.roundToInt() },
                        valueRange = 0f..data.frequenciesHz.lastIndex.toFloat(),
                        modifier = Modifier.semantics { contentDescription = "Selected response frequency. $frequencyLabel: $valueLabel" })
                    Text("Tap the graph or adjust the frequency slider to inspect a point. Frequency uses a logarithmic scale.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (view == 2) Text("This is the EQ's group delay; it does not measure device or playback latency.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (view != 0) Text("Gaps mark undefined values below ${eqNumber(data.phaseUndefinedBelowDb)} dB.",
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
            Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "Calculated EQ response. $description" }
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
private fun shortFrequency(value: Double): String = if (value >= 1_000) "${(value / 1_000).roundToInt()}k Hz" else "${value.roundToInt()} Hz"
