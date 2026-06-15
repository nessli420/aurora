package com.aurora.music.ui.screens.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.aurora.music.data.VisualizerPrefs
import com.aurora.music.data.VisualizerStyle
import com.aurora.music.playback.VisualizerController

@Composable
fun VisualizerCanvas(
    controller: VisualizerController,
    prefs: VisualizerPrefs,
    colors: VizColors,
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableStateOf(0L) }
    var startNanos by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { t -> if (startNanos == 0L) startNanos = t; tick = t }
    }

    val particles = remember(prefs.particleCount) { ParticleField(prefs.particleCount.coerceIn(20, 600)) }
    val river = remember { SpectralRiver() }
    val terrain = remember { SpectralTerrain() }
    val curlFlow = remember(prefs.particleCount) { CurlFlowField(prefs.particleCount.coerceIn(200, 1800)) }
    val attractor = remember { StrangeAttractor(4000) }
    val cymatic = remember { Cymatic(2200) }

    Canvas(modifier) {
        tick // read so canvas redraws each frame
        val timeSec = if (startNanos == 0L) 0f else (tick - startNanos) / 1_000_000_000f
        val rot = if (prefs.rotate) timeSec * 0.4f else 0f
        val f = controller.frame
        try {
        when (prefs.style) {
            VisualizerStyle.BARS -> drawBars(f, colors, prefs.mirror, prefs.peakHold)
            VisualizerStyle.MIRROR_BARS -> drawMirrorBars(f, colors, prefs.peakHold)
            VisualizerStyle.WAVEFORM -> drawWaveform(f, colors)
            VisualizerStyle.FILLED_WAVE -> drawFilledWave(f, colors)
            VisualizerStyle.RADIAL_BARS -> drawRadialBars(f, colors, rot)
            VisualizerStyle.RADIAL_WAVE -> drawRadialWave(f, colors, rot)
            VisualizerStyle.PARTICLES -> { particles.update(f.bass, f.level, size.width, size.height); particles.draw(this, colors) }
            VisualizerStyle.FLUID -> drawFluid(f, colors, timeSec)
            VisualizerStyle.COMBO -> {
                drawRadialBars(f, colors, rot)
                particles.update(f.bass, f.level, size.width, size.height); particles.draw(this, colors)
            }
            VisualizerStyle.SMOOTH_CURVE -> drawSmoothCurve(f, colors)
            VisualizerStyle.DOT_GRID -> drawDotGrid(f, colors)
            VisualizerStyle.RINGS -> drawRings(f, colors, timeSec)
            VisualizerStyle.ORB -> drawOrb(f, colors, timeSec)
            VisualizerStyle.LADDER -> drawLadder(f, colors)
            VisualizerStyle.HORIZON -> drawHorizon(f, colors, timeSec)
            VisualizerStyle.CONSTELLATION -> drawConstellation(f, colors, timeSec)
            VisualizerStyle.PEAK_DOTS -> drawPeakDots(f, colors)
            VisualizerStyle.SPECTRUM_LINE -> drawSpectrumLine(f, colors)
            VisualizerStyle.AURORA -> drawAurora(f, colors, timeSec)
            VisualizerStyle.SPECTRAL_RIVER -> { river.update(f, timeSec, size.width, size.height); river.draw(this, colors) }
            VisualizerStyle.SPECTRAL_TERRAIN -> { terrain.update(f, timeSec, size.width, size.height); terrain.draw(this, colors) }
            VisualizerStyle.CURL_FLOW -> { curlFlow.update(f, timeSec, size.width, size.height); curlFlow.draw(this, colors) }
            VisualizerStyle.STRANGE_ATTRACTOR -> { attractor.update(f, timeSec, size.width, size.height); attractor.draw(this, colors) }
            VisualizerStyle.CYMATIC -> { cymatic.update(f, timeSec, size.width, size.height); cymatic.draw(this, colors) }
            VisualizerStyle.SUPERFORMULA_BLOOM -> drawSuperformulaBloom(f, colors, timeSec)
            VisualizerStyle.WORMHOLE -> drawWormhole(f, colors, timeSec)
            else -> drawBars(f, colors, prefs.mirror, prefs.peakHold)
        }
        } catch (_: Throwable) {
            // renderer must never crash ui thread skip frame
        }
    }
}
