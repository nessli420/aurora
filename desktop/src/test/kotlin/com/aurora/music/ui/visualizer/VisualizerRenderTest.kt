package com.aurora.music.ui.visualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.UiPrefs
import com.aurora.music.data.VisualizerPrefs
import com.aurora.music.data.VisualizerStyle
import com.aurora.music.data.VizBackground
import com.aurora.music.data.VizColor
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.model.Song
import com.aurora.music.playback.VisualizerController
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.screens.settings.VisualizerSettingsScreen
import com.aurora.music.ui.screens.visualizer.VisualizerCanvas
import com.aurora.music.ui.screens.visualizer.VisualizerScreen
import com.aurora.music.ui.screens.visualizer.VizColors
import com.aurora.music.ui.screens.visualizer.label
import com.aurora.music.ui.theme.AuroraTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class VisualizerRenderTest {
    @get:Rule val temp = TemporaryFolder()

    private val shots = System.getenv("AURORA_SHOTS")?.let(::File)
    private val colors = VizColors(Color(0xFF7C4DFF), Color(0xFF00E5FF))
    private val song = Song("1", "Midnight City", "M83", "Hurry Up, We're Dreaming", "", 243, accentArgb = 0xFF7C4DFF)

    private val lifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private fun VisualizerController.Frame.fill(t: Float) {
        val n = 64
        val next = FloatArray(n) { b ->
            (0.2f + 0.62f * abs(sin(b * 0.31f + t * 2.1f)) * (1f - b / (n * 1.6f)) + 0.12f * sin(t * 3f + b * 0.05f)).coerceIn(0f, 1f)
        }
        val held = peaks
        peaks = FloatArray(n) { b -> max(next[b], (held.getOrNull(b) ?: 0f) * 0.94f) }
        bands = next
        wave = FloatArray(256) { k -> (0.55f * sin(2f * PI.toFloat() * k / 64f + t * 4f) + 0.25f * sin(2f * PI.toFloat() * k / 9f - t * 7f)).coerceIn(-1f, 1f) }
        rms = 0.4f + 0.1f * sin(t)
        bass = next.take(8).average().toFloat()
        level = next.max()
    }

    private fun synthetic(controller: VisualizerController) {
        controller.monoSource = object : VisualizerController.MonoSource {
            private var position = 0L
            override fun read(out: FloatArray): Int {
                for (k in out.indices) {
                    val s = (position + k) / 48_000.0
                    out[k] = (0.45 * sin(2 * PI * 110 * s) + 0.3 * sin(2 * PI * 440 * s) + 0.15 * sin(2 * PI * 1_800 * s) + 0.08 * sin(2 * PI * 6_000 * s)).toFloat()
                }
                position += out.size
                return out.size
            }
            override fun sampleRate() = 48_000
            override fun active() = true
        }
    }

    private fun save(name: String, image: Image) {
        shots?.let { File(it.apply { mkdirs() }, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
    }

    private fun Bitmap.coverage(x0: Int, y0: Int, w: Int, h: Int): Float {
        var lit = 0
        var total = 0
        for (y in y0 until y0 + h step 2) for (x in x0 until x0 + w step 2) {
            val c = getColor(x, y)
            if (maxOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF) > 48) lit++
            total++
        }
        return lit.toFloat() / total
    }

    private fun Bitmap.signature(x0: Int, y0: Int, w: Int, h: Int): List<Int> = (0 until 12).flatMap { gy ->
        (0 until 16).map { gx ->
            var sum = 0
            for (y in 0 until h / 12 step 3) for (x in 0 until w / 16 step 3) {
                val c = getColor(x0 + gx * w / 16 + x, y0 + gy * h / 12 + y)
                sum += (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
            }
            sum / 64
        }
    }

    @Composable
    private fun Providers(container: DesktopContainer? = null, content: @Composable () -> Unit) {
        val locals = listOfNotNull(
            LocalWindowLayout provides WindowLayout(1440, 900),
            LocalLifecycleOwner provides lifecycleOwner,
            container?.let { LocalDesktopContainer provides it },
        )
        CompositionLocalProvider(*locals.toTypedArray()) {
            AuroraTheme(UiPrefs(themeMode = ThemeMode.DARK), content = content)
        }
    }

    private fun withContainer(block: (DesktopContainer, VisualizerController) -> Unit) {
        val container = DesktopContainer(DesktopPaths(File(temp.root, "Roaming"), File(temp.root, "Local")))
        try {
            block(container, container.visualizer.also(::synthetic))
        } finally {
            container.close()
            runBlocking { container.scope.coroutineContext.job.join() }
        }
    }

    private fun ImageComposeScene.settle(frames: Int, realMillis: Long = 0, onFrame: (Int) -> Unit = {}): Image {
        repeat(frames) { i ->
            onFrame(i)
            render(i * 16_000_000L)
            Snapshot.sendApplyNotifications()
            if (realMillis > 0) Thread.sleep(realMillis)
        }
        return render(frames * 16_000_000L)
    }

    @Test fun everyStyleDrawsAndAnimates() {
        val cols = 6
        val cellW = 320
        val labelH = 22
        val canvasH = 198
        val count = VisualizerStyle.count
        val rows = (count + cols - 1) / cols
        val controller = VisualizerController(CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(cols * cellW, rows * (labelH + canvasH), Density(1f)) {
            Providers {
                Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                    (0 until rows).forEach { r ->
                        Row {
                            (0 until cols).forEach { c ->
                                val s = r * cols + c
                                Column(Modifier.size(cellW.dp, (labelH + canvasH).dp)) {
                                    Text(if (s < count) "$s ${VisualizerStyle.label(s)}" else "", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.height(labelH.dp).padding(start = 6.dp, top = 3.dp))
                                    Box(Modifier.size(cellW.dp, canvasH.dp).background(Color.Black)) {
                                        if (s < count) VisualizerCanvas(controller, VisualizerPrefs(style = s, particleCount = 140), colors, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val early = Bitmap.makeFromImage(scene.settle(40) { controller.frame.fill(it / 60f) })
        val image = scene.settle(90) { controller.frame.fill(it / 60f) }
        scene.close()
        save("visualizer-styles", image)
        val bitmap = Bitmap.makeFromImage(image)
        val cells = (0 until count).map { s -> Triple((s % cols) * cellW, (s / cols) * (labelH + canvasH) + labelH, s) }
        val coverage = cells.associate { (x, y, s) -> VisualizerStyle.label(s) to bitmap.coverage(x, y, cellW, canvasH) }
        println(coverage.entries.joinToString("\n") { "%-20s %.4f".format(it.key, it.value) })
        coverage.forEach { (name, value) -> assertTrue("$name drew nothing ($value)", value > 0.004f) }
        assertEquals(count, cells.map { (x, y) -> bitmap.signature(x, y, cellW, canvasH) }.distinct().size)
        val moved = cells.count { (x, y) -> early.signature(x, y, cellW, canvasH) != bitmap.signature(x, y, cellW, canvasH) }
        assertTrue("only $moved of $count styles animated", moved >= count - 1)
    }

    @Test fun screenRunsTheAnalyserWhileVisible() = withContainer { container, controller ->
        runBlocking { container.settingsStore.setVisualizer(VisualizerPrefs(style = VisualizerStyle.RADIAL_BARS, background = VizBackground.GRADIENT, colorSource = VizColor.GRADIENT)) }
        var closed = 0
        val scene = ImageComposeScene(1440, 900, Density(1f)) {
            Providers(container) { VisualizerScreen(PlayerUiState(current = song, isPlaying = true), onClose = { closed++ }) }
        }
        val image = scene.settle(45, realMillis = 12)
        assertTrue(controller.active)
        assertTrue("analyser produced no level", controller.frame.level > 0.05f)
        save("visualizer-screen", image)
        val bitmap = Bitmap.makeFromImage(image)
        assertTrue(bitmap.coverage(420, 150, 600, 600) > 0.02f)
        assertTrue("close button missing", bitmap.coverage(12, 8, 40, 40) > 0.05f)
        assertTrue("mode chips missing", bitmap.coverage(0, 820, 1440, 60) > 0.05f)
        scene.close()
        assertFalse(controller.active)
        assertEquals(0f, controller.frame.level)
        assertEquals(0, closed)
    }

    @Test fun settingsPreviewFollowsPrefs() = withContainer { container, controller ->
        runBlocking { container.settingsStore.setVisualizer(VisualizerPrefs(style = VisualizerStyle.SPECTRUM_LINE, colorSource = VizColor.GRADIENT)) }
        val scene = ImageComposeScene(1080, 900, Density(1f)) {
            Providers(container) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VisualizerSettingsScreen(PaddingValues(0.dp), onBack = {})
                }
            }
        }
        val image = scene.settle(45, realMillis = 12)
        assertTrue(controller.active)
        save("visualizer-settings", image)
        scene.close()
        assertFalse(controller.active)
    }
}
