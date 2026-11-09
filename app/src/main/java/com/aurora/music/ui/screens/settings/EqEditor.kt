package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.Canvas
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aurora.music.ui.theme.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.FilterType
import com.aurora.music.data.ParamBand
import com.aurora.music.localization.appString
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.GraphicLayout
import com.aurora.music.playback.engine.PrecisionDspCoeffBuilder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SIMPLE = 0
private const val ADVANCED = 1
private const val MIN_HZ = 20f
private const val MAX_HZ = 20_000f
private const val POINTS = 192
private const val SLIDER_DB = 12f
private val LOG_SPAN = ln(MAX_HZ / MIN_HZ)
private val CURVE_HZ = DoubleArray(POINTS) { MIN_HZ * (MAX_HZ / MIN_HZ).toDouble().pow(it / (POINTS - 1.0)) }
private val GRID_HZ = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

// graphic sliders and parametric bands both apply; the curve always shows what they add up to
@Composable
internal fun EqEditor(
    layoutIndex: Int,
    graphicGains: List<Float>,
    bands: List<ParamBand>,
    maxBands: Int,
    sampleRate: Int,
    onGraphic: (Int, List<Float>) -> Unit,
    onBands: (List<ParamBand>) -> Unit,
    footer: String? = null,
    limitHint: String? = null,
    limitAction: Pair<String, () -> Unit>? = null,
) {
    val layout = DspCoeffBuilder.graphicLayout(layoutIndex, graphicGains)
    val gains = List(layout.freqs.size) { graphicGains.getOrElse(it) { 0f } }
    var mode by rememberSaveable { mutableIntStateOf(if (bands.isNotEmpty() && gains.all { it == 0f }) ADVANCED else SIMPLE) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val current = selected.coerceIn(0, (bands.size - 1).coerceAtLeast(0))
    val latestBands by rememberUpdatedState(bands)
    val latestGains by rememberUpdatedState(gains)
    fun setBand(index: Int, band: ParamBand) = onBands(latestBands.mapIndexed { i, b -> if (i == index) band else b })
    fun setGain(index: Int, gain: Float) = onGraphic(layoutIndex, latestGains.mapIndexed { i, g -> if (i == index) gain else g })
    fun addBand(hz: Float, gain: Float) {
        if (latestBands.size >= maxBands) return
        selected = latestBands.size
        onBands(latestBands + ParamBand(hz, gain, 1f))
        mode = ADVANCED
    }
    Column(Modifier.fillMaxWidth()) {
        SegmentedRow(appString(R.string.text_mode_a7b93d), listOf(appString(R.string.eq_simple), appString(R.string.eq_advanced)), mode) { mode = it }
        EqCurve(layout, gains, bands, sampleRate, mode == ADVANCED, current,
            onSelect = { selected = it }, onBand = ::setBand, onGain = ::setGain, onAdd = ::addBand)
        if (mode == SIMPLE) SimpleControls(layout, layoutIndex, gains, onGraphic, ::setGain)
        else AdvancedControls(bands, current, maxBands, onSelect = { selected = it }, onBand = ::setBand,
            onAdd = { addBand(freeFrequency(latestBands), 0f) },
            onRemove = {
                onBands(latestBands.filterIndexed { i, _ -> i != current })
                selected = (current - 1).coerceAtLeast(0)
            },
            onEditCustom = { editing = true }, limitHint = limitHint, limitAction = limitAction)
        // each mode counts its own layer: the band footer belongs to advanced, simple says what stacks on its sliders
        if (mode == ADVANCED) footer?.let { Hint(it) }
        else if (bands.isNotEmpty()) Hint(appString(R.string.eq_simple_layers, gains.size, bands.size))
    }
    if (editing) bands.getOrNull(current)?.let { band ->
        RackBandDialog(band, appString(R.string.text_band_c6bf38, current + 1), onDismiss = { editing = false }) {
            setBand(current, it)
            editing = false
        }
    }
}

private class Plot(val width: Float, val height: Float, val left: Float, val top: Float, bottom: Float, val range: Float) {
    val w = width - left
    val h = height - top - bottom
    fun x(hz: Float) = left + ln(hz.coerceIn(MIN_HZ, MAX_HZ) / MIN_HZ) / LOG_SPAN * w
    fun y(db: Float) = top + (1f - (db.coerceIn(-range, range) + range) / (2 * range)) * h
    fun hz(x: Float) = (MIN_HZ * exp((x - left) / w * LOG_SPAN)).coerceIn(MIN_HZ, MAX_HZ)
    fun db(y: Float) = ((1f - (y - top) / h) * 2 * range - range).coerceIn(-range, range)
}

@Composable
private fun EqCurve(layout: GraphicLayout, gains: List<Float>, bands: List<ParamBand>, sampleRate: Int, advanced: Boolean,
    selected: Int, onSelect: (Int) -> Unit, onBand: (Int, ParamBand) -> Unit, onGain: (Int, Float) -> Unit, onAdd: (Float, Float) -> Unit) {
    val rate = sampleRate.coerceIn(8_000, 768_000)
    val cache = remember { HashMap<Pair<DspBand, Int>, FloatArray>() }
    if (cache.size > 256) cache.clear()
    fun curve(band: DspBand) = cache.getOrPut(band to rate) { response(band, rate) }
    val graphicCurves = gains.mapIndexed { i, g -> if (g == 0f) null else curve(DspBand(layout.freqs[i], g, layout.q)) }
    val bandCurves = bands.map { curve(DspBand.from(it)) }
    val total = FloatArray(POINTS)
    (graphicCurves.filterNotNull() + bandCurves).forEach { c -> for (i in total.indices) total[i] += c[i] }
    val peak = maxOf(total.maxOf { abs(it) }, gains.maxOfOrNull { abs(it) } ?: 0f,
        bands.maxOfOrNull { if (it.filterType.hasGain) abs(it.gainDb) else 0f } ?: 0f)
    val range = when { peak <= 12f -> 12f; peak <= 18f -> 18f; else -> 24f }
    val handles: List<Pair<Float, Float>?> = if (advanced) bands.mapIndexed { i, b ->
        if (b.filterType == FilterType.CUSTOM_BIQUAD) null else b.freqHz to (if (b.filterType.hasGain) b.gainDb else bandCurves[i].at(b.freqHz))
    } else layout.freqs.mapIndexed { i, hz -> hz to gains[i] }

    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted, fontSize = 10.sp)
    val latestHandles by rememberUpdatedState(handles)
    val latestRange by rememberUpdatedState(range)
    val latestAdvanced by rememberUpdatedState(advanced)
    val latestBands by rememberUpdatedState(bands)
    val select by rememberUpdatedState(onSelect)
    val moveBand by rememberUpdatedState(onBand)
    val moveGain by rememberUpdatedState(onGain)
    val add by rememberUpdatedState(onAdd)

    Canvas(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 12.dp, vertical = 8.dp)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val plot = Plot(size.width.toFloat(), size.height.toFloat(), 34.dp.toPx(), 8.dp.toPx(), 20.dp.toPx(), latestRange)
                val reach = 28.dp.toPx()
                val target = latestHandles.withIndex().filter { it.value != null }.map { (i, h) ->
                    i to (Offset(plot.x(h!!.first), plot.y(h.second)) - down.position).getDistance()
                }.filter { it.second <= reach }.minByOrNull { it.second }?.first ?: return@awaitEachGesture
                down.consume()
                val advancedDrag = latestAdvanced
                val start = latestBands.getOrNull(target)
                if (advancedDrag) select(target)
                drag(down.id) { change ->
                    change.consume()
                    val p = change.position
                    if (advancedDrag && start != null) moveBand(target, start.copy(freqHz = roundHz(plot.hz(p.x)),
                        gainDb = if (start.filterType.hasGain) roundTenth(plot.db(p.y)) else start.gainDb))
                    else if (!advancedDrag) moveGain(target, roundTenth(plot.db(p.y).coerceIn(-SLIDER_DB, SLIDER_DB)))
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { p ->
                    if (!latestAdvanced || latestBands.isEmpty()) return@detectTapGestures
                    val plot = Plot(size.width.toFloat(), size.height.toFloat(), 34.dp.toPx(), 8.dp.toPx(), 20.dp.toPx(), latestRange)
                    latestHandles.withIndex().filter { it.value != null }.minByOrNull { abs(plot.x(it.value!!.first) - p.x) }?.let { select(it.index) }
                },
                onDoubleTap = { p ->
                    if (!latestAdvanced) return@detectTapGestures
                    val plot = Plot(size.width.toFloat(), size.height.toFloat(), 34.dp.toPx(), 8.dp.toPx(), 20.dp.toPx(), latestRange)
                    add(roundHz(plot.hz(p.x)), roundTenth(plot.db(p.y).coerceIn(-15f, 15f)))
                },
            )
        }) {
        val plot = Plot(size.width, size.height, 34.dp.toPx(), 8.dp.toPx(), 20.dp.toPx(), range)
        val grid = muted.copy(alpha = .16f)
        var db = -range
        while (db <= range + .01f) {
            val y = plot.y(db)
            drawLine(if (db == 0f) muted.copy(alpha = .4f) else grid, Offset(plot.left, y), Offset(size.width, y), 1.dp.toPx())
            val text = measurer.measure(if (db > 0f) "+${db.roundToInt()}" else "${db.roundToInt()}", labelStyle)
            drawText(text, topLeft = Offset(plot.left - text.size.width - 6.dp.toPx(), y - text.size.height / 2f))
            db += range / 2
        }
        GRID_HZ.forEach { hz ->
            val x = plot.x(hz)
            drawLine(grid, Offset(x, plot.top), Offset(x, plot.top + plot.h), 1.dp.toPx())
            val text = measurer.measure(shortHz(hz), labelStyle)
            drawText(text, topLeft = Offset(x - text.size.width / 2f, plot.top + plot.h + 4.dp.toPx()))
        }
        fun path(curve: FloatArray) = Path().apply {
            for (i in 0 until POINTS) {
                val x = plot.left + i / (POINTS - 1f) * plot.w
                if (i == 0) moveTo(x, plot.y(curve[i])) else lineTo(x, plot.y(curve[i]))
            }
        }
        if (advanced) bandCurves.forEachIndexed { i, c -> if (i != selected) drawPath(path(c), muted.copy(alpha = .35f), style = Stroke(1.dp.toPx())) }
        else graphicCurves.forEach { c -> if (c != null) drawPath(path(c), muted.copy(alpha = .3f), style = Stroke(1.dp.toPx())) }
        if (advanced) bandCurves.getOrNull(selected)?.let { c ->
            val shape = path(c)
            drawPath(Path().apply {
                addPath(shape)
                lineTo(plot.left + plot.w, plot.y(0f))
                lineTo(plot.left, plot.y(0f))
                close()
            }, accent.copy(alpha = .12f))
            drawPath(shape, accent.copy(alpha = .55f), style = Stroke(1.5.dp.toPx()))
        }
        drawPath(path(total), accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        handles.forEachIndexed { i, h ->
            if (h == null) return@forEachIndexed
            val center = Offset(plot.x(h.first), plot.y(h.second))
            val active = advanced && i == selected
            val on = !advanced || bands[i].isEnabled
            val radius = (if (active) 7 else if (advanced) 5 else 4).dp.toPx()
            drawCircle(if (active) accent else surface, radius, center)
            drawCircle(accent.copy(alpha = if (on) 1f else .4f), radius, center, style = Stroke(2.dp.toPx()))
            if (advanced) {
                val label = measurer.measure("${i + 1}", labelStyle)
                drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - radius - label.size.height - 2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun SimpleControls(layout: GraphicLayout, layoutIndex: Int, gains: List<Float>,
    onGraphic: (Int, List<Float>) -> Unit, onGain: (Int, Float) -> Unit) {
    SegmentedRow(appString(R.string.text_bands_2bc0aa), DspCoeffBuilder.GRAPHIC_LAYOUTS.map { it.name } + appString(R.string.eq_custom),
        layoutIndex.coerceIn(0, DspCoeffBuilder.CUSTOM_LAYOUT)) { target ->
        if (target == layoutIndex) return@SegmentedRow
        // custom starts from the current slider count, so switching to it keeps the curve you had
        val next = if (target == DspCoeffBuilder.CUSTOM_LAYOUT) DspCoeffBuilder.customLayout(layout.freqs.size) else DspCoeffBuilder.GRAPHIC_LAYOUTS[target]
        onGraphic(target, resample(layout, gains, next))
    }
    if (layoutIndex == DspCoeffBuilder.CUSTOM_LAYOUT) SliderCountRow(layout.freqs.size) { count ->
        onGraphic(DspCoeffBuilder.CUSTOM_LAYOUT, resample(layout, gains, DspCoeffBuilder.customLayout(count)))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val fits = maxWidth / layout.freqs.size >= 34.dp
        Row(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())) {
            layout.freqs.forEachIndexed { i, hz ->
                GainSlider(gains[i], shortHz(hz), if (fits) Modifier.weight(1f) else Modifier.width(40.dp)) { onGain(i, it) }
            }
        }
    }
    TextButton(onClick = { onGraphic(layoutIndex, List(gains.size) { 0f }) }, modifier = Modifier.padding(start = 8.dp)) {
        Text(appString(R.string.text_reset_graphic_eq_ace461))
    }
    Hint(appString(R.string.eq_simple_hint))
}

@Composable
private fun SliderCountRow(count: Int, onCount: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(appString(R.string.eq_slider_count), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onCount(count - 1) }, enabled = count > DspCoeffBuilder.MIN_GRAPHIC) {
            Icon(Icons.Filled.Remove, appString(R.string.eq_fewer_sliders))
        }
        Text("$count", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { onCount(count + 1) }, enabled = count < DspCoeffBuilder.MAX_GRAPHIC) {
            Icon(Icons.Filled.Add, appString(R.string.eq_more_sliders))
        }
    }
}

// starts dragging only from the thumb so the page can still scroll over the sliders
@Composable
private fun GainSlider(value: Float, label: String, modifier: Modifier, onChange: (Float) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f)
    val change by rememberUpdatedState(onChange)
    val latest by rememberUpdatedState(value)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%+.1f".format(value), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1)
        Canvas(Modifier.fillMaxWidth().height(170.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, -SLIDER_DB..SLIDER_DB)
                setProgress { target -> change(roundTenth(target.coerceIn(-SLIDER_DB, SLIDER_DB))); true }
            }
            .pointerInput(Unit) {
                val pad = 10.dp.toPx()
                fun at(y: Float) = roundTenth((((1f - (y - pad) / (size.height - 2 * pad)) * 2f - 1f) * SLIDER_DB).coerceIn(-SLIDER_DB, SLIDER_DB))
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val thumb = pad + (1f - (latest + SLIDER_DB) / (2 * SLIDER_DB)) * (size.height - 2 * pad)
                    if (abs(down.position.y - thumb) <= 28.dp.toPx()) {
                        down.consume()
                        drag(down.id) { move -> move.consume(); change(at(move.position.y)) }
                    } else waitForUpOrCancellation()?.let { change(at(it.position.y)) }
                }
            }) {
            val pad = 10.dp.toPx()
            val cx = size.width / 2
            val zero = size.height / 2
            val y = pad + (1f - (value.coerceIn(-SLIDER_DB, SLIDER_DB) + SLIDER_DB) / (2 * SLIDER_DB)) * (size.height - 2 * pad)
            drawLine(track, Offset(cx, pad), Offset(cx, size.height - pad), 4.dp.toPx(), StrokeCap.Round)
            drawLine(accent, Offset(cx, zero), Offset(cx, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(accent, 8.dp.toPx(), Offset(cx, y))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun AdvancedControls(bands: List<ParamBand>, current: Int, maxBands: Int, onSelect: (Int) -> Unit,
    onBand: (Int, ParamBand) -> Unit, onAdd: () -> Unit, onRemove: () -> Unit, onEditCustom: () -> Unit,
    limitHint: String?, limitAction: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            bands.forEachIndexed { i, band -> BandTab(i + 1, i == current, band.isEnabled) { onSelect(i) } }
        }
        IconButton(onClick = onRemove, enabled = bands.isNotEmpty()) { Icon(Icons.Filled.Remove, appString(R.string.text_remove_band_fb769d)) }
        IconButton(onClick = onAdd, enabled = bands.size < maxBands) { Icon(Icons.Filled.Add, appString(R.string.text_add_band_563c3b)) }
    }
    SettingsRowDivider()
    val band = bands.getOrNull(current)
    if (band == null) Hint(appString(R.string.eq_no_bands))
    else key(current) { BandControls(current, band, { onBand(current, it) }, onEditCustom) }
    if (bands.size >= maxBands) {
        Hint(listOfNotNull(appString(R.string.eq_band_limit, maxBands), limitHint).joinToString(" "))
        limitAction?.let { (label, action) -> TextButton(onClick = action, modifier = Modifier.padding(start = 8.dp)) { Text(label) } }
    }
    Hint(appString(R.string.eq_drag_hint))
}

@Composable
private fun BandTab(number: Int, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.paperClip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).semantics { selected = active }
        .padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$number", style = MaterialTheme.typography.titleMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = (if (active) accent else MaterialTheme.colorScheme.onSurface).copy(alpha = if (enabled) 1f else .4f))
        Box(Modifier.padding(top = 4.dp).size(width = 16.dp, height = 2.dp)
            .background(if (active) accent else Color.Transparent, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun BandControls(index: Int, band: ParamBand, onChange: (ParamBand) -> Unit, onEditCustom: () -> Unit) {
    val type = band.filterType
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(appString(R.string.text_filter_4a5de1, type.label), Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, appString(R.string.text_choose_filter_type_6698bc))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                FilterType.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(value.label) }, onClick = { menu = false; if (value != type) onChange(retype(band, value)) })
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        val enabledLabel = appString(R.string.text_enabled_df174a)
        Switch(checked = band.isEnabled, onCheckedChange = { onChange(band.copy(enabled = it)) },
            modifier = Modifier.semantics { contentDescription = enabledLabel })
    }
    if (type == FilterType.CUSTOM_BIQUAD) {
        TextButton(onClick = onEditCustom, modifier = Modifier.padding(start = 8.dp)) { Text(appString(R.string.text_edit_band_903859, index + 1)) }
        return
    }
    ParamControl(appString(R.string.text_frequency_hz_c2281f), band.freqHz, 10f..24_000f, MIN_HZ..MAX_HZ, log = true,
        format = ::formatHz, step = { v, d -> v * 2f.pow(d / 12f) }, round = ::roundHz) { onChange(band.copy(freqHz = it)) }
    if (type.hasQ) ParamControl("Q", band.q, .1f..100f, .1f..20f, log = true, format = { "%.2f".format(it) },
        step = { v, d -> v * 1.1f.pow(d) }, round = { (it * 100).roundToInt() / 100f }) { onChange(band.copy(q = it)) }
    if (type.hasGain) ParamControl(appString(if (type == FilterType.TILT) R.string.text_high_to_low_tilt_db_1ba5c8 else R.string.text_gain_db_455951),
        band.gainDb, -30f..30f, -24f..24f, log = false, format = { "%+.1f".format(it) },
        step = { v, d -> v + d * .5f }, round = ::roundTenth) { onChange(band.copy(gainDb = it)) }
    if (type.orders.size > 1) SegmentedRow(appString(R.string.text_slope_638d02), type.orders.map { appString(R.string.text_db_oct_29dc5a, it * 6) },
        type.orders.indexOf(band.filterOrder).coerceAtLeast(0)) { onChange(band.copy(order = type.orders[it])) }
}

@Composable
private fun ParamControl(label: String, value: Float, allowed: ClosedFloatingPointRange<Float>, slider: ClosedFloatingPointRange<Float>,
    log: Boolean, format: (Float) -> String, step: (Float, Int) -> Float, round: (Float) -> Float, onChange: (Float) -> Unit) {
    fun position(v: Float): Float {
        val c = v.coerceIn(slider)
        return if (log) ln(c / slider.start) / ln(slider.endInclusive / slider.start) else (c - slider.start) / (slider.endInclusive - slider.start)
    }
    fun valueAt(p: Float) = if (log) slider.start * (slider.endInclusive / slider.start).pow(p) else slider.start + p * (slider.endInclusive - slider.start)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            NumberField(value, allowed, format) { onChange(round(it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(position(value), { onChange(round(valueAt(it))) }, Modifier.weight(1f))
            IconButton(onClick = { onChange(round(step(value, -1)).coerceIn(allowed)) }) { Icon(Icons.Filled.Remove, appString(R.string.eq_decrease, label)) }
            IconButton(onClick = { onChange(round(step(value, 1)).coerceIn(allowed)) }) { Icon(Icons.Filled.Add, appString(R.string.eq_increase, label)) }
        }
    }
}

@Composable
private fun NumberField(value: Float, allowed: ClosedFloatingPointRange<Float>, format: (Float) -> String, onChange: (Float) -> Unit) {
    var text by remember { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value, focused) { if (!focused) text = format(value) }
    fun parse(input: String) = input.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in allowed }
    OutlinedTextField(text, { input -> text = input; parse(input)?.let(onChange) },
        modifier = Modifier.width(128.dp).onFocusChanged { focused = it.isFocused },
        singleLine = true, isError = parse(text) == null,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
}

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun response(band: DspBand, sampleRate: Int): FloatArray {
    val sections = runCatching { PrecisionDspCoeffBuilder.cascade(band, sampleRate) }.getOrNull() ?: return FloatArray(POINTS)
    return FloatArray(POINTS) { i ->
        val w = 2.0 * PI * CURVE_HZ[i] / sampleRate
        val cw = cos(w); val c2w = cos(2.0 * w); val sw = sin(w); val s2w = sin(2.0 * w)
        sections.sumOf { c ->
            val nr = c.b0 + c.b1 * cw + c.b2 * c2w; val ni = -(c.b1 * sw + c.b2 * s2w)
            val dr = 1.0 + c.a1 * cw + c.a2 * c2w; val di = -(c.a1 * sw + c.a2 * s2w)
            val den = dr * dr + di * di
            if (den > 0.0) 10.0 * log10((nr * nr + ni * ni).coerceAtLeast(1e-30) / den) else 0.0
        }.toFloat()
    }
}

private fun FloatArray.at(hz: Float): Float = this[(ln(hz.coerceIn(MIN_HZ, MAX_HZ) / MIN_HZ) / LOG_SPAN * (POINTS - 1)).roundToInt().coerceIn(0, POINTS - 1)]

// keep the curve when the slider count changes by reading the old curve at the new frequencies
private fun resample(from: GraphicLayout, gains: List<Float>, to: GraphicLayout): List<Float> = to.freqs.map { hz ->
    val f = from.freqs
    val value = when {
        hz <= f.first() -> gains.first()
        hz >= f.last() -> gains.last()
        else -> {
            val i = f.indexOfLast { it <= hz }
            gains[i] + (gains[i + 1] - gains[i]) * ln(hz / f[i]) / ln(f[i + 1] / f[i])
        }
    }
    roundTenth(value)
}

private fun freeFrequency(bands: List<ParamBand>): Float {
    if (bands.isEmpty()) return 1_000f
    val points = (listOf(MIN_HZ, MAX_HZ) + bands.map { it.freqHz.coerceIn(MIN_HZ, MAX_HZ) }).map { ln(it) }.sorted()
    val (low, high) = points.zipWithNext().maxBy { (a, b) -> b - a }
    return roundHz(exp((low + high) / 2))
}

private fun retype(band: ParamBand, type: FilterType) = ParamBand(
    freqHz = band.freqHz,
    gainDb = if (type.hasGain) band.gainDb else 0f,
    q = if (!type.hasQ) .70710677f else if (band.filterType.hasQ) band.q else 1f,
    type = type.code, filterId = type.id, enabled = band.isEnabled, order = type.orders.first(),
    coefficients = if (type == FilterType.CUSTOM_BIQUAD) band.coefficients ?: listOf(1.0, 0.0, 0.0, 0.0, 0.0) else null,
)

private fun roundTenth(v: Float) = (v * 10).roundToInt() / 10f
private fun roundHz(v: Float) = if (v < 1_000f) roundTenth(v) else v.roundToInt().toFloat()
private fun formatHz(v: Float) = when {
    v < 100f -> "%.2f".format(v)
    v < 1_000f -> "%.1f".format(v)
    else -> "%.0f".format(v)
}
private fun shortHz(hz: Float): String = if (hz >= 1_000f) {
    val k = hz / 1_000f
    (if (k == k.roundToInt().toFloat()) "${k.roundToInt()}" else "%.1f".format(k)) + "K"
} else "${hz.roundToInt()}"
