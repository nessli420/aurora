package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import com.aurora.music.data.*
import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.playback.engine.RackNodeMeter
import com.aurora.music.playback.engine.EqResponseCalculator

@Composable
internal fun RackAdvancedControls(node: ProcessingRackNode, onEdit: ((ProcessingRackNode) -> ProcessingRackNode) -> Unit,
    meter: RackNodeMeter? = null, sampleRate: Int? = null) {
    when (node.kind) {
        RackNodeKind.UTILITY -> {
            val u = node.utility ?: RackUtility()
            fun edit(next: RackUtility) = onEdit { it.copy(utility = next) }
            SettingsGroup {
                SettingsSwitchRow(title = "DC blocker", checked = u.dcBlock, onCheckedChange = { edit(u.copy(dcBlock = it)) })
                SettingsSwitchRow(title = "Mono bass", checked = u.monoBassHz > 0, onCheckedChange = { edit(u.copy(monoBassHz = if (it) 120.0 else 0.0)) })
                if (u.monoBassHz > 0) AdvancedSlider("Mono bass cutoff", u.monoBassHz, 20.0..500.0, "Hz") { edit(u.copy(monoBassHz = it)) }
                AdvancedSlider("Left → Left", u.ll, -2.0..2.0, "×") { edit(u.copy(ll = it)) }
                AdvancedSlider("Right → Left", u.lr, -2.0..2.0, "×") { edit(u.copy(lr = it)) }
                AdvancedSlider("Left → Right", u.rl, -2.0..2.0, "×") { edit(u.copy(rl = it)) }
                AdvancedSlider("Right → Right", u.rr, -2.0..2.0, "×") { edit(u.copy(rr = it)) }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = { edit(u.copy(ll = -u.ll, lr = -u.lr)) }) { Text("Invert left") }
                    TextButton(onClick = { edit(u.copy(rl = -u.rl, rr = -u.rr)) }) { Text("Invert right") }
                }
            }
        }
        RackNodeKind.ALIGNMENT_DELAY -> {
            val u = node.utility ?: RackUtility()
            SettingsGroup {
                AdvancedSlider("Alignment delay", u.delayMs, 0.0..100.0, "ms") { value -> onEdit { it.copy(utility = u.copy(delayMs = value)) } }
                Text("Other branches align automatically at each mix.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        RackNodeKind.DYNAMIC_EQ -> {
            val d = node.dynamic ?: RackDynamicEq()
            fun edit(next: RackDynamicEq) = onEdit { it.copy(dynamic = next) }
            SettingsGroup {
                DynamicGainMeter(meter?.changeDb, "Filter change")
                if (meter != null) DynamicResponse(node, meter.changeDb, sampleRate ?: 48000)
                AdvancedSlider("Filter frequency", d.frequencyHz, 20.0..20000.0, "Hz") { edit(d.copy(frequencyHz = it)) }
                AdvancedSlider("Filter Q", d.q, .1..12.0, "") { edit(d.copy(q = it)) }
                AdvancedSlider("Detector frequency", d.detectorHz, 20.0..20000.0, "Hz") { edit(d.copy(detectorHz = it)) }
                AdvancedSlider("Detector Q", d.detectorQ, .1..12.0, "") { edit(d.copy(detectorQ = it)) }
                SettingsSwitchRow(title = "Boost below threshold", checked = d.upward, onCheckedChange = { edit(d.copy(upward = it)) })
                DynamicsControls(d.dynamics, true) { edit(d.copy(dynamics = it)) }
            }
        }
        RackNodeKind.MULTIBAND -> {
            val m = node.multiband ?: RackMultiband()
            var selected by remember(node.id) { mutableIntStateOf(0) }
            fun edit(next: RackMultiband) = onEdit { it.copy(multiband = next) }
            SettingsGroup {
                AdvancedSlider("Low crossover", m.lowHz, 40.0..minOf(2000.0, m.highHz / 2), "Hz") { edit(m.copy(lowHz = it)) }
                AdvancedSlider("High crossover", m.highHz, maxOf(400.0, m.lowHz * 2)..16000.0, "Hz") { edit(m.copy(highHz = it)) }
                SegmentedRow("Band", listOf("Low", "Mid", "High"), selected) { selected = it }
                DynamicGainMeter(meter?.bandChangesDb?.getOrNull(selected), "Band reduction")
                DynamicsControls(m.bands[selected], false) { value -> edit(m.copy(bands = m.bands.mapIndexed { i, band -> if (i == selected) value else band })) }
            }
        }
        RackNodeKind.LOUDNESS -> {
            val l = node.loudness ?: RackLoudness()
            fun edit(next: RackLoudness) = onEdit { it.copy(loudness = next) }
            SettingsGroup {
                DynamicGainMeter(meter?.changeDb, "Current bass compensation")
                AdvancedSlider("Reference volume", l.referenceVolume * 100, 5.0..100.0, "%") { edit(l.copy(referenceVolume = it / 100)) }
                AdvancedSlider("Bass limit", l.bassCapDb, 0.0..12.0, "dB") { edit(l.copy(bassCapDb = it)) }
                AdvancedSlider("Treble limit", l.trebleCapDb, 0.0..6.0, "dB") { edit(l.copy(trebleCapDb = it)) }
                AdvancedSlider("Strength", l.strength * 100, 0.0..100.0, "%") { edit(l.copy(strength = it / 100)) }
                Text("Relative to media volume. This is not an SPL estimate.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        RackNodeKind.SATURATION -> SettingsGroup {
            SegmentedRow("Oversampling", listOf("Off", "2×", "4×", "8×"), listOf(1, 2, 4, 8).indexOf(node.oversampling ?: 1)) { value ->
                onEdit { it.copy(oversampling = listOf(1, 2, 4, 8)[value]) }
            }
        }
        else -> Unit
    }
}

@Composable
private fun DynamicGainMeter(change: Double?, title: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(if (change == null) "$title · No active audio" else "$title · %+.1f dB".format(change), style = MaterialTheme.typography.labelMedium)
        if (change != null) LinearProgressIndicator(progress = { (kotlin.math.abs(change) / 24).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun DynamicResponse(node: ProcessingRackNode, gain: Double, rate: Int) {
    val settings = node.dynamic ?: RackDynamicEq()
    val values = remember(settings.frequencyHz, settings.q, gain, rate, node.wet, node.bypass) {
        EqResponseCalculator.calculateNode(node.copy(kind = RackNodeKind.EQ,
            audio = AudioPrefs(dspParametric = listOf(ParamBand(settings.frequencyHz.toFloat(), gain.toFloat(), settings.q.toFloat())))), rate, 128).magnitudeDb
    }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val center = size.height * .5f
            drawLine(grid, Offset(0f, center), Offset(size.width, center))
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = index.toFloat() / values.lastIndex * size.width
                val y = center - value.coerceIn(-24.0, 24.0).toFloat() / 24 * center
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
        Text("20 Hz–${minOf(20000, rate / 2)} Hz · ±24 dB · Filter only", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DynamicsControls(d: RackDynamics, detectorSolo: Boolean, onChange: (RackDynamics) -> Unit) {
    SegmentedRow("Detector", listOf("Peak", "RMS"), d.detector.ordinal) { onChange(d.copy(detector = RackDetector.entries[it])) }
    AdvancedSlider("Threshold", d.thresholdDb, -80.0..0.0, "dBFS") { onChange(d.copy(thresholdDb = it)) }
    AdvancedSlider("Ratio", d.ratio, 1.0..20.0, ":1") { onChange(d.copy(ratio = it)) }
    AdvancedSlider("Attack", d.attackMs, .1..200.0, "ms") { onChange(d.copy(attackMs = it)) }
    AdvancedSlider("Release", d.releaseMs, 5.0..3000.0, "ms") { onChange(d.copy(releaseMs = it)) }
    AdvancedSlider("Knee", d.kneeDb, 0.0..24.0, "dB") { onChange(d.copy(kneeDb = it)) }
    AdvancedSlider("Maximum change", d.rangeDb, 0.0..24.0, "dB") { onChange(d.copy(rangeDb = it)) }
    AdvancedSlider("Makeup", d.makeupDb, -24.0..12.0, "dB") { onChange(d.copy(makeupDb = it)) }
    SettingsSwitchRow(title = if (detectorSolo) "Listen to detector" else "Solo band", checked = d.solo, onCheckedChange = { onChange(d.copy(solo = it)) })
    SettingsSwitchRow(title = "Mute", checked = d.mute, onCheckedChange = { onChange(d.copy(mute = it)) })
    Text("Stereo linked.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun AdvancedSlider(title: String, value: Double, range: ClosedFloatingPointRange<Double>, suffix: String, onChange: (Double) -> Unit) =
    SettingsSliderRow(title, "%.1f %s".format(value, suffix), value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
        range.start.toFloat()..range.endInclusive.toFloat()) { onChange(it.toDouble()) }

@Composable
internal fun RackRoutingDialog(title: String, available: List<ProcessingRackNode>, initial: List<RackInput>?, onDismiss: () -> Unit,
    onSave: (List<RackInput>?) -> Unit) {
    var inputs by remember { mutableStateOf(initial ?: listOf(RackInput(available.lastOrNull()?.id ?: RackInput.INPUT))) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            inputs.forEachIndexed { index, edge ->
                var sourceMenu by remember(index) { mutableStateOf(false) }
                var channelMenu by remember(index) { mutableStateOf(false) }
                fun edit(value: RackInput) { inputs = inputs.mapIndexed { i, old -> if (i == index) value else old } }
                Row {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { sourceMenu = true }) { Text(available.firstOrNull { it.id == edge.source }?.name ?: "Input") }
                        DropdownMenu(sourceMenu, { sourceMenu = false }) {
                            DropdownMenuItem({ Text("Input") }, onClick = { edit(edge.copy(source = RackInput.INPUT)); sourceMenu = false })
                            available.forEach { source -> DropdownMenuItem({ Text(source.name) }, onClick = { edit(edge.copy(source = source.id)); sourceMenu = false }) }
                        }
                    }
                    Box {
                        TextButton(onClick = { channelMenu = true }) { Text(edge.channel.label()) }
                        DropdownMenu(channelMenu, { channelMenu = false }) {
                            RackChannel.entries.forEach { channel -> DropdownMenuItem({ Text(channel.label()) }, onClick = { edit(edge.copy(channel = channel)); channelMenu = false }) }
                        }
                    }
                }
                AdvancedSlider("Input ${index + 1} gain", edge.gainDb, -60.0..12.0, "dB") { edit(edge.copy(gainDb = it)) }
                if (inputs.size > 1) TextButton(onClick = { inputs = inputs.filterIndexed { i, _ -> i != index } }) { Text("Remove input") }
            }
            if (inputs.size < 4) TextButton(onClick = { inputs = inputs + RackInput() }) { Text("Add input") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        if (inputs.map { it.source to it.channel }.distinct().size != inputs.size) error = "Choose distinct inputs." else onSave(inputs)
    }) { Text("Save") } }, dismissButton = {
        Row { TextButton(onClick = { onSave(null) }) { Text("Serial") }; TextButton(onClick = onDismiss) { Text("Cancel") } }
    })
}

private fun RackChannel.label() = when (this) {
    RackChannel.STEREO -> "Stereo"; RackChannel.LEFT -> "Left"; RackChannel.RIGHT -> "Right"
    RackChannel.MID -> "Mid"; RackChannel.SIDE -> "Side"; RackChannel.ENCODE_MS -> "Encode M/S"; RackChannel.DECODE_MS -> "Decode M/S"
}

@Composable
internal fun RackImpulsePicker(node: ProcessingRackNode, library: List<ImpulseLibraryEntry>, onEdit: ((ProcessingRackNode) -> ProcessingRackNode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { menu = true }) { Text(library.firstOrNull { it.id == node.impulseId }?.name ?: if (node.impulseId == null) "Use standard impulse" else "Missing impulse") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Use standard impulse") }, onClick = { onEdit { it.copy(impulseId = null) }; menu = false })
            library.forEach { entry -> DropdownMenuItem({ Text(entry.name + if (entry.prepared != null) " · Prepared" else " · Source") }, onClick = { onEdit { it.copy(impulseId = entry.id) }; menu = false }) }
        }
    }
}
