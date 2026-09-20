package com.aurora.music.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.Preservation
import com.aurora.music.data.SignalFormat
import com.aurora.music.data.SignalStage
import com.aurora.music.data.AudioMeasurements
import com.aurora.music.data.PcmLevels
import com.aurora.music.data.AudioSpectrum
import java.util.Locale
import kotlin.math.log10
import kotlin.math.ln

/** One inspector for both Settings and the Now Playing quality shortcut. */
@Composable
fun SignalPathScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenOutput: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val path by container.signalPath.collectAsStateWithLifecycle()
    val rack by container.settingsStore.processingRack.collectAsStateWithLifecycle(com.aurora.music.data.ProcessingRack())

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Signal Path", onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SettingsGroup {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
                        val icon = when {
                            !path.active -> Icons.Filled.MusicNote
                            path.preservation == Preservation.PRESERVED -> Icons.Filled.Verified
                            path.preservation == Preservation.MODIFIED -> Icons.Filled.GraphicEq
                            else -> Icons.Filled.Info
                        }
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                when {
                                    !path.active -> "Nothing playing"
                                    path.preservation == Preservation.PRESERVED -> "Samples preserved"
                                    path.preservation == Preservation.MODIFIED -> "Samples modified"
                                    else -> "Sample preservation unknown"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                path.note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (path.active) path.reasons.distinct().filter { it != path.note }.forEach { reason ->
                                Text(reason, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (path.active) {
                path.measurements?.let { measurements ->
                    item { MeasurementCard(measurements) }
                }
                if (path.nodeMeters.isNotEmpty()) item {
                    var expanded by remember { mutableStateOf(false) }
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide stage meters" else "Stage meters") }
                            if (expanded) path.nodeMeters.forEach { meter ->
                                Text(rack.nodes.firstOrNull { it.id == meter.id }?.name ?: "Stage", style = MaterialTheme.typography.titleSmall)
                                Text(String.format(Locale.ROOT, "Peak %.1f dBFS · Change %.1f dB",
                                    20 * log10(meter.peak.coerceAtLeast(1e-10)), meter.changeDb), style = MaterialTheme.typography.bodySmall)
                                if (meter.bandChangesDb.isNotEmpty()) Text(meter.bandChangesDb.joinToString(" · ") {
                                    String.format(Locale.ROOT, "%.1f dB", it)
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                path.audioTrackUnderruns?.let { count -> item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("AudioTrack underruns: $count", style = MaterialTheme.typography.titleSmall)
                            Text("Primary player, since creation.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } }
                path.usbDiagnostics?.let { usb -> item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("USB transport", style = MaterialTheme.typography.titleSmall)
                            Text("Completed: ${usb.completedFrames} · Pending: ${usb.pendingFrames}", style = MaterialTheme.typography.bodySmall)
                            Text("Packet errors: ${usb.packetErrors} · Timeouts: ${usb.timeouts}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
                val stages = listOf(path.source, path.decoder, path.processing, path.resampling,
                    path.outputStage, path.device, path.latency)
                itemsIndexed(stages) { index, stage ->
                    PathStageCard(index + 1, stage)
                }
                item {
                    Text(
                        "Unknown means the active playback path cannot report that detail. A supported or requested format does not confirm what reaches the device.",
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Devices, "Audio output", "Choose a device or output mode", onClick = onOpenOutput)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.ContentCopy, "Copy diagnostic report", "Playback formats and processing; no account credentials") {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("Aurora Signal Path", path.toDiagnosticReport()))
                        Toast.makeText(context, "Signal Path report copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurementCard(measurements: AudioMeasurements) {
    var details by remember { mutableStateOf(false) }
    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Digital sample levels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (measurements.playing) "Latest 100 ms sample windows" else "Paused · last measured sample windows",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MeterReading("Before app processing", measurements.before)
            if (measurements.afterAvailable) MeterReading("After app processing", measurements.after)
            else Text("After-processing measurement unavailable on this path or channel layout.", style = MaterialTheme.typography.bodySmall)
            measurements.spectrum?.let { SpectrumChart(it) }
                ?: Text("Waiting for audio samples.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide measurement details" else "Measurement details") }
            if (details) Text("Sample peak and RMS, before output volume and Android effects. Level windows are independent; paired spectra match PCM timestamps. Full-scale counts reset on seek or format change. These are not true-peak, loudness or acoustic measurements.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (measurements.overlappingPlayers) Text("Crossfade: primary player only.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpectrumChart(spectrum: AudioSpectrum) {
    val before = MaterialTheme.colorScheme.tertiary
    val after = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text(if (spectrum.afterDb != null) "Aligned spectrum" else "Source spectrum", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Before", color = before, style = MaterialTheme.typography.labelMedium)
        if (spectrum.afterDb != null) Text("After", color = after, style = MaterialTheme.typography.labelMedium)
        Text("−100 to +6 dBFS", style = MaterialTheme.typography.labelSmall)
    }
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val maximum = minOf(20_000.0, spectrum.sampleRate / 2.0)
        val span = ln(maximum / 20)
        for (db in listOf(-80f, -60f, -40f, -20f, 0f)) {
            val y = (6 - db) / 106 * size.height
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        fun draw(values: List<Float>, color: androidx.compose.ui.graphics.Color) {
            val path = Path()
            var first = true
            for (bin in 1 until values.size) {
                val hz = bin * spectrum.sampleRate.toDouble() / ((values.size - 1) * 2)
                if (hz < 20 || hz > maximum) continue
                val x = (ln(hz / 20) / span * size.width).toFloat()
                val y = (6 - values[bin].coerceIn(-100f, 6f)) / 106 * size.height
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(1.5.dp.toPx()))
        }
        draw(spectrum.beforeDb, before)
        spectrum.afterDb?.let { draw(it, after) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("20 Hz", style = MaterialTheme.typography.labelSmall)
        Text("${minOf(20_000, spectrum.sampleRate / 2) / 1000} kHz", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MeterReading(label: String, levels: PcmLevels?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (levels == null) Text("Waiting for a complete mono or stereo PCM window", style = MaterialTheme.typography.bodyMedium)
        else {
            fun db(value: Double) = if (value <= 0.0) "−∞" else String.format(Locale.getDefault(), "%.1f", 20 * log10(value))
            Text("${if (levels.channels == 1) "Mono" else "L"}   Peak ${db(levels.leftPeak)} · RMS ${db(levels.leftRms)} dBFS",
                style = MaterialTheme.typography.bodyMedium)
            if (levels.channels == 2) Text("R   Peak ${db(levels.rightPeak)} · RMS ${db(levels.rightRms)} dBFS",
                style = MaterialTheme.typography.bodyMedium)
            Text("Full-scale samples: ${levels.fullScaleSamples}" + if (levels.invalidSamples > 0) " · Invalid samples: ${levels.invalidSamples}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PathStageCard(number: Int, stage: SignalStage) {
    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$number  ·  ${stage.title}", style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(stage.detail, style = MaterialTheme.typography.bodyMedium)
            stage.format?.let { format ->
                Text(formatDescription(format), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
            }
            if (stage.evidence.isNotBlank()) {
                Text(stage.evidence, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatDescription(format: SignalFormat): String = buildList {
    add(format.rateHz?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.1f kHz", it / 1000f) }
        ?: "Rate unknown")
    add(format.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" } ?: "Depth unknown")
    add(when (format.channels) { 1 -> "Mono"; 2 -> "Stereo"; null, 0 -> "Channels unknown"; else -> "${format.channels} channels" })
    format.encoding?.takeIf { it.isNotBlank() }?.let(::add)
}.joinToString(" · ")
