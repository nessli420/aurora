package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.*

internal data class TuningPlotCurve(val label: String, val magnitudeDb: DoubleArray)

@Composable
internal fun TuningCurveChart(frequencies: DoubleArray, curves: List<TuningPlotCurve>, scope: String) {
    if (frequencies.size < 2 || curves.isEmpty() || curves.any { it.magnitudeDb.size != frequencies.size }) return
    var selected by remember(frequencies) { mutableIntStateOf(frequencies.size / 2) }
    var visible by remember(curves.map { it.label }) { mutableStateOf(curves.map { it.label != appString(R.string.text_correction_7f2640) && it.label != appString(R.string.text_fitted_eq_3bc5ee) }) }
    val palette = listOf(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val lowLog = ln(frequencies.first())
    val spanLog = ln(frequencies.last()) - lowLog
    val point = selected.coerceIn(frequencies.indices)
    val plotted = curves.filterIndexed { i, _ -> visible[i] }.flatMap { it.magnitudeDb.asIterable() }.filter { it.isFinite() }
    val lowest = plotted.minOrNull() ?: -6.0
    val highest = plotted.maxOrNull() ?: 6.0
    val margin = maxOf(1.0, (highest - lowest) * .08)
    val bottom = floor((lowest - margin) / 3.0) * 3.0
    val top = ceil((highest + margin) / 3.0) * 3.0
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(curves) { index, curve ->
                FilterChip(selected = visible[index], onClick = { visible = visible.mapIndexed { i, old -> if (i == index) !old else old } },
                    label = { Text(curve.label, color = palette[index % palette.size]) })
            }
        }
        Row(Modifier.fillMaxWidth().height(200.dp)) {
            Column(Modifier.width(54.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(eqNumber(top), style = MaterialTheme.typography.labelSmall)
                Text(appString(R.string.text_db_e44622), style = MaterialTheme.typography.labelSmall)
                Text(eqNumber(bottom), style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = appString(R.string.text_magnitude_curves_selected_95d402, (eqFrequency(frequencies[point])), (scope)) }
                .pointerInput(frequencies) {
                    detectTapGestures { tap ->
                        val wanted = exp(lowLog + (tap.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * spanLog)
                        val found = frequencies.binarySearch(wanted)
                        selected = if (found >= 0) found else {
                            val upper = (-found - 1).coerceIn(frequencies.indices)
                            val lower = (upper - 1).coerceAtLeast(0)
                            if (abs(ln(frequencies[upper] / wanted)) < abs(ln(frequencies[lower] / wanted))) upper else lower
                        }
                    }
                }) {
                fun x(index: Int) = (((ln(frequencies[index]) - lowLog) / spanLog) * size.width).toFloat()
                fun y(value: Double) = ((1 - (value - bottom) / (top - bottom)) * size.height).toFloat()
                repeat(5) { i ->
                    drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
                    drawLine(grid, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), 1.dp.toPx())
                }
                curves.forEachIndexed { index, curve ->
                    if (visible[index]) {
                        val path = Path()
                        var drawing = false
                        curve.magnitudeDb.forEachIndexed { i, value ->
                            if (value.isFinite()) {
                                if (drawing) path.lineTo(x(i), y(value)) else path.moveTo(x(i), y(value))
                                drawing = true
                            } else drawing = false
                        }
                        val dash = when (index) { 1 -> floatArrayOf(9.dp.toPx(), 5.dp.toPx()); 2 -> floatArrayOf(3.dp.toPx(), 3.dp.toPx()); else -> null }
                        drawPath(path, palette[index % palette.size], style = Stroke((if (index == 3) 3 else 2).dp.toPx(),
                            pathEffect = dash?.let { PathEffect.dashPathEffect(it) }))
                    }
                }
                drawLine(grid, Offset(x(point), 0f), Offset(x(point), size.height), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 54.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(eqFrequency(frequencies.first()), style = MaterialTheme.typography.labelSmall)
            Text(eqFrequency(frequencies.last()), style = MaterialTheme.typography.labelSmall)
        }
        Text(eqFrequency(frequencies[point]), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        curves.forEachIndexed { index, curve ->
            Row(Modifier.fillMaxWidth()) {
                Text(curve.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = palette[index % palette.size])
                val value = curve.magnitudeDb[point]
                Text(if (value.isFinite()) eqDb(value) else appString(R.string.text_undefined_0646f4), style = MaterialTheme.typography.bodySmall)
            }
        }
        Slider(value = point.toFloat(), onValueChange = { selected = it.roundToInt() }, valueRange = 0f..frequencies.lastIndex.toFloat(),
            modifier = Modifier.semantics { contentDescription = appString(R.string.text_selected_measurement_frequency_c5919f, (eqFrequency(frequencies[point]))) })
        if (scope.isNotBlank()) Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
