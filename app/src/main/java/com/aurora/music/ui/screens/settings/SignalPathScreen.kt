package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString

import com.aurora.music.R
import com.aurora.music.localization.localizedSignalLabel

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
        SettingsTopBar(appString(R.string.text_signal_path_c3e29b), onBack)
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
                                    !path.active -> appString(R.string.text_nothing_playing_13ae37)
                                    path.preservation == Preservation.PRESERVED -> appString(R.string.text_samples_preserved_091001)
                                    path.preservation == Preservation.MODIFIED -> appString(R.string.text_samples_modified_d04e9f)
                                    else -> appString(R.string.text_sample_preservation_unknown_7de2de)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (path.active) path.note else appString(R.string.signal_start_playback),
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
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) appString(R.string.text_hide_stage_meters_c123cc) else appString(R.string.text_stage_meters_50fb37)) }
                            if (expanded) path.nodeMeters.forEach { meter ->
                                Text(rack.nodes.firstOrNull { it.id == meter.id }?.name ?: appString(R.string.text_stage_ca6d0e), style = MaterialTheme.typography.titleSmall)
                                Text(String.format(Locale.ROOT, appString(R.string.text_peak_1f_dbfs_change_1f_db_e16aea),
                                    20 * log10(meter.peak.coerceAtLeast(1e-10)), meter.changeDb), style = MaterialTheme.typography.bodySmall)
                                if (meter.bandChangesDb.isNotEmpty()) Text(meter.bandChangesDb.joinToString(" · ") {
                                    String.format(Locale.ROOT, appString(R.string.text_1f_db_02557a), it)
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                path.audioTrackUnderruns?.let { count -> item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(appString(R.string.text_audiotrack_underruns_6c9f5b, (count)), style = MaterialTheme.typography.titleSmall)
                            Text(appString(R.string.text_primary_player_since_creation_a2795e),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } }
                path.usbDiagnostics?.let { usb -> item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(appString(R.string.text_usb_transport_39fd7d), style = MaterialTheme.typography.titleSmall)
                            Text(appString(R.string.text_completed_pending_bf3cd1, (usb.completedFrames), (usb.pendingFrames)), style = MaterialTheme.typography.bodySmall)
                            Text(appString(R.string.text_packet_errors_timeouts_e3e49f, (usb.packetErrors), (usb.timeouts)), style = MaterialTheme.typography.bodySmall)
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
                        appString(R.string.text_unknown_means_the_active_playback_path_cannot_report_that_detail_c0b60e),
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Devices, appString(R.string.text_audio_output_2b89cc), appString(R.string.text_choose_a_device_or_output_mode_72ff69), onClick = onOpenOutput)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.ContentCopy, appString(R.string.text_copy_diagnostic_report_7c8feb), appString(R.string.text_playback_formats_and_processing_no_account_credentials_9d08c4)) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText(appString(R.string.text_aurora_signal_path_b2764e), path.toDiagnosticReport()))
                        Toast.makeText(context, appString(R.string.text_signal_path_report_copied_ecc24d), Toast.LENGTH_SHORT).show()
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
            Text(appString(R.string.text_digital_sample_levels_eed86c), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (measurements.playing) appString(R.string.text_latest_100_ms_sample_windows_332ea8) else appString(R.string.text_paused_last_measured_sample_windows_0c768c),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MeterReading(appString(R.string.text_before_app_processing_fb479a), measurements.before)
            if (measurements.afterAvailable) MeterReading(appString(R.string.text_after_app_processing_2b0263), measurements.after)
            else Text(appString(R.string.text_after_processing_measurement_unavailable_on_this_path_or_channel_bf6314), style = MaterialTheme.typography.bodySmall)
            measurements.spectrum?.let { SpectrumChart(it) }
                ?: Text(appString(R.string.text_waiting_for_audio_samples_e18779), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = !details }) { Text(if (details) appString(R.string.text_hide_measurement_details_a40be4) else appString(R.string.text_measurement_details_edeee9)) }
            if (details) Text(appString(R.string.text_sample_peak_and_rms_before_output_volume_and_android_effects_leve_08e2a4),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (measurements.overlappingPlayers) Text(appString(R.string.text_crossfade_primary_player_only_8e7de0),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpectrumChart(spectrum: AudioSpectrum) {
    val before = MaterialTheme.colorScheme.tertiary
    val after = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Text(if (spectrum.afterDb != null) appString(R.string.text_aligned_spectrum_da9b60) else appString(R.string.text_source_spectrum_5d8903), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(appString(R.string.text_before_74f396), color = before, style = MaterialTheme.typography.labelMedium)
        if (spectrum.afterDb != null) Text(appString(R.string.text_after_79ba5e), color = after, style = MaterialTheme.typography.labelMedium)
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
        Text(appString(R.string.text_20_hz_9d51f9), style = MaterialTheme.typography.labelSmall)
        Text(appString(R.string.text_khz_dd177d, (minOf(20_000, spectrum.sampleRate / 2) / 1000)), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MeterReading(label: String, levels: PcmLevels?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (levels == null) Text(appString(R.string.text_waiting_for_a_complete_mono_or_stereo_pcm_window_c07259), style = MaterialTheme.typography.bodyMedium)
        else {
            fun db(value: Double) = if (value <= 0.0) "−∞" else String.format(Locale.getDefault(), "%.1f", 20 * log10(value))
            Text(appString(R.string.text_peak_rms_dbfs_95c5fb, (if (levels.channels == 1) appString(R.string.text_mono_c5c553) else "L"), (db(levels.leftPeak)), (db(levels.leftRms))),
                style = MaterialTheme.typography.bodyMedium)
            if (levels.channels == 2) Text(appString(R.string.text_r_peak_rms_dbfs_885a75, (db(levels.rightPeak)), (db(levels.rightRms))),
                style = MaterialTheme.typography.bodyMedium)
            Text(appString(R.string.text_full_scale_samples_2918e5, (levels.fullScaleSamples)) + if (levels.invalidSamples > 0) appString(R.string.text_invalid_samples_a16776, (levels.invalidSamples)) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PathStageCard(number: Int, stage: SignalStage) {
    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$number  ·  ${stage.title.localizedSignalLabel()}", style = MaterialTheme.typography.labelLarge,
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
    add(format.rateHz?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), appString(R.string.text_1f_khz_92ed69), it / 1000f) }
        ?: appString(R.string.text_rate_unknown_7e2d67))
    add(format.bitDepth?.takeIf { it > 0 }?.let { appString(R.string.text_bit_fd7850, (it)) } ?: appString(R.string.text_depth_unknown_c30135))
    add(when (format.channels) { 1 -> appString(R.string.text_mono_c5c553); 2 -> appString(R.string.text_stereo_f4f390); null, 0 -> appString(R.string.text_channels_unknown_b12d18); else -> appString(R.string.text_channels_f248dd, (format.channels)) })
    format.encoding?.takeIf { it.isNotBlank() }?.let(::add)
}.joinToString(" · ")
