package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.VisualizerPrefs
import com.aurora.music.data.VisualizerStyle
import com.aurora.music.data.VizBackground
import com.aurora.music.data.VizColor
import com.aurora.music.ui.screens.visualizer.VisualizerCanvas
import com.aurora.music.ui.screens.visualizer.VizColors
import kotlinx.coroutines.launch

private val SWATCHES = listOf(
    0xFF7C4DFF, 0xFF00E5FF, 0xFF28D572, 0xFFFF5252, 0xFFFFC107,
    0xFFFF4081, 0xFF40C4FF, 0xFFB388FF, 0xFF18FFFF, 0xFFFFFFFF,
).map { it.toInt() }

@Composable
fun VisualizerSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val controller = container.visualizer
    val prefs by store.visualizerPrefs.collectAsStateWithLifecycle(initialValue = VisualizerPrefs())
    val scope = rememberCoroutineScope()
    fun save(p: VisualizerPrefs) { scope.launch { store.setVisualizer(p) } }

    // Drive the analyser so the preview reacts to whatever is playing.
    DisposableEffect(Unit) { controller.start(); onDispose { controller.stop() } }
    LaunchedEffect(prefs.barCount, prefs.fftSize, prefs.smoothing, prefs.sensitivity, prefs.minHz, prefs.maxHz, prefs.peakHold, prefs.fpsCap) {
        controller.applyPrefs(prefs)
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Visualizer", onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item { VisualizerPreview(prefs) }

            item { SettingsSectionTitle("Style") }
            item {
                LazyRow(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    items((0 until VisualizerStyle.count).toList()) { s ->
                        val selected = s == prefs.style
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { save(prefs.copy(style = s)) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Text(
                                VisualizerStyle.label(s),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            item { SettingsSectionTitle("Colour") }
            item {
                SettingsGroup {
                    SegmentedRow("Colour source", listOf("Accent", "Custom", "Gradient", "Album"), prefs.colorSource) { save(prefs.copy(colorSource = it)) }
                }
            }
            if (prefs.colorSource == VizColor.CUSTOM || prefs.colorSource == VizColor.GRADIENT) {
                item { Swatches("Primary", prefs.primaryColor) { save(prefs.copy(primaryColor = it)) } }
            }
            if (prefs.colorSource == VizColor.GRADIENT) {
                item { Swatches("Secondary", prefs.secondaryColor) { save(prefs.copy(secondaryColor = it)) } }
            }

            item { SettingsSectionTitle("Background") }
            item {
                SettingsGroup {
                    SegmentedRow("Backdrop", listOf("Black", "Gradient", "Album blur"), prefs.background) { save(prefs.copy(background = it)) }
                }
            }

            item { SettingsSectionTitle("Spectrum") }
            item {
                SettingsGroup {
                    SettingsSliderRow("Bar count", "${prefs.barCount}", prefs.barCount.toFloat(), 16f..160f, steps = 0) { save(prefs.copy(barCount = it.toInt())) }
                    SettingsRowDivider()
                    SettingsSliderRow("Smoothing", "${(prefs.smoothing * 100).toInt()}%", prefs.smoothing, 0f..0.95f) { save(prefs.copy(smoothing = it)) }
                    SettingsRowDivider()
                    SettingsSliderRow("Sensitivity", String.format("%.2fx", prefs.sensitivity), prefs.sensitivity, 0.25f..4f) { save(prefs.copy(sensitivity = it)) }
                    SettingsRowDivider()
                    SettingsSwitchRow(null, "Peak hold", "Falling caps on the bars", prefs.peakHold) { save(prefs.copy(peakHold = it)) }
                    SettingsRowDivider()
                    SettingsSwitchRow(null, "Mirror", "Reflect the spectrum", prefs.mirror) { save(prefs.copy(mirror = it)) }
                }
            }

            item { SettingsSectionTitle("Frequency range") }
            item {
                SettingsGroup {
                    SettingsSliderRow("Low cut", "${prefs.minHz} Hz", prefs.minHz.toFloat(), 10f..500f) { save(prefs.copy(minHz = it.toInt())) }
                    SettingsRowDivider()
                    SettingsSliderRow("High cut", "${prefs.maxHz / 1000} kHz", prefs.maxHz.toFloat(), 2000f..22000f) { save(prefs.copy(maxHz = it.toInt())) }
                }
            }

            item { SettingsSectionTitle("Motion & quality") }
            item {
                SettingsGroup {
                    val fftIdx = when (prefs.fftSize) { 1024 -> 0; 4096 -> 2; else -> 1 }
                    SegmentedRow("FFT resolution", listOf("1024", "2048", "4096"), fftIdx) {
                        save(prefs.copy(fftSize = when (it) { 0 -> 1024; 2 -> 4096; else -> 2048 }))
                    }
                    SettingsRowDivider()
                    val fpsIdx = when (prefs.fpsCap) { 30 -> 0; 90 -> 2; 120 -> 3; else -> 1 }
                    SegmentedRow("Frame rate", listOf("30", "60", "90", "120"), fpsIdx) {
                        save(prefs.copy(fpsCap = when (it) { 0 -> 30; 2 -> 90; 3 -> 120; else -> 60 }))
                    }
                    SettingsRowDivider()
                    SettingsSwitchRow(null, "Rotate radial styles", "Slow spin for radial / combo", prefs.rotate) { save(prefs.copy(rotate = it)) }
                    SettingsRowDivider()
                    SettingsSliderRow("Particles", "${prefs.particleCount}", prefs.particleCount.toFloat(), 20f..400f) { save(prefs.copy(particleCount = it.toInt())) }
                }
            }

            item { SettingsSectionTitle("Overlay") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(null, "Album art centre", "Show artwork in radial styles", prefs.showAlbumArt) { save(prefs.copy(showAlbumArt = it)) }
                    SettingsRowDivider()
                    SettingsSwitchRow(null, "Track info", "Title & artist over the visual", prefs.showTrackInfo) { save(prefs.copy(showTrackInfo = it)) }
                }
            }
        }
    }
}

@Composable
private fun VisualizerPreview(prefs: VisualizerPrefs) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val controller = container.visualizer
    val colors = when (prefs.colorSource) {
        VizColor.CUSTOM -> VizColors(Color(prefs.primaryColor), Color(prefs.primaryColor))
        VizColor.GRADIENT -> VizColors(Color(prefs.primaryColor), Color(prefs.secondaryColor))
        else -> VizColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(160.dp)
            .clip(RoundedCornerShape(18.dp)).background(Color.Black),
    ) {
        VisualizerCanvas(controller, prefs, colors, Modifier.fillMaxWidth().height(160.dp).padding(8.dp))
        if (controller.frame.level <= 0.001f) {
            Text(
                "Play something to see it react",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun Swatches(label: String, selected: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(SWATCHES) { c ->
                val isSel = c == selected
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { onPick(c) },
                )
            }
        }
    }
}
