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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import java.util.Locale
import kotlin.math.log10

/** One inspector for both Settings and the Now Playing quality shortcut. */
@Composable
fun SignalPathScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenOutput: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val path by container.signalPath.collectAsStateWithLifecycle()

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
                                if (!path.active) "Play a track to inspect its source, processing and output."
                                else path.note,
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
                path.audioTrackUnderruns?.let { count -> item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("AudioTrack underruns: $count", style = MaterialTheme.typography.titleSmall)
                            Text("Reported for the primary player since it was created. This does not measure the crossfade sum or downstream hardware.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Digital sample levels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (measurements.playing) "Latest 100 ms sample windows" else "Paused · last measured sample windows",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MeterReading("Before app processing", measurements.before)
            if (measurements.afterAvailable) MeterReading("After app processing", measurements.after)
            else Text("After-processing measurement unavailable on this path or channel layout.", style = MaterialTheme.typography.bodySmall)
            Text("Peak and RMS use dBFS. Full-scale samples count digital endpoints since the last seek or format reset; they do not prove audible clipping. These are sample peaks, not true peak or loudness.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("After-processing levels include Aurora DSP and convolution, before speed, silence skipping, ReplayGain, player volume and Android effects. Windows are not time-aligned and may be decoded ahead of what you hear.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (measurements.overlappingPlayers) Text("Crossfade: readings show the primary player only, not the combined output.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
