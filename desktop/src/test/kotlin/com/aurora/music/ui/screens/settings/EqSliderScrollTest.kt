package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.region
import com.aurora.music.ui.testing.saveTo
import com.aurora.music.ui.theme.AuroraTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin

// issue #35: with more sliders than fit, a mouse user has to be able to reach the ones off the edge
class EqSliderScrollTest {
    private val shots = System.getenv("AURORA_SHOTS")?.let { File(it, "eq-sliders") }
    private val width = 900

    private fun scene(gains: List<Float>, height: Int = 700): Pair<EdtScene, Int> {
        var accent = 0
        val scene = EdtScene(width, height) {
            AuroraTheme {
                accent = MaterialTheme.colorScheme.primary.toArgb()
                Surface {
                    // the settings page scrolls vertically around the editor, like the real one
                    Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        EqEditor(DspCoeffBuilder.CUSTOM_LAYOUT, gains, emptyList(), DspCoeffBuilder.MAX_PARAMETRIC, 48_000, { _, _ -> }, {})
                        Spacer(Modifier.height(600.dp))
                    }
                }
            }
        }
        return scene to accent
    }

    private fun EdtScene.settle(): Image { repeat(30) { frame(16) }; return frame(16) }
    private fun EdtScene.wheel(y: Int, notches: Int) = repeat(notches) {
        input { sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, y.toFloat()), scrollDelta = Offset(0f, 1f)) }
    }

    /** With every gain at 0 dB the thumbs share one row: returns that row and how many thumbs show. */
    private fun thumbRow(image: Image, accent: Int): Pair<Int, Int> {
        val bitmap = Bitmap.makeFromImage(image)
        val y = (image.height / 2 until image.height).maxBy { y -> (0 until image.width).count { x -> bitmap.getColor(x, y) == accent } }
        var runs = 0; var inRun = false
        for (x in 0 until image.width) {
            val hit = bitmap.getColor(x, y) == accent
            if (hit && !inRun) runs++
            inRun = hit
        }
        return y to runs
    }

    private fun Image.band(y0: Int, y1: Int) = region(0, y0.coerceAtLeast(0), width, y1.coerceAtMost(height))

    @Test fun theWheelMovesAnOverflowingSliderRowThenHandsBackToThePage() {
        val (scene, accent) = scene(List(DspCoeffBuilder.MAX_GRAPHIC) { 0f })
        scene.use {
            it.input { sendPointerEvent(PointerEventType.Move, Offset(width / 2f, 10f)) }
            val before = it.settle()
            val (y, visible) = thumbRow(before, accent)
            assertTrue("31 sliders shouldn't all fit in ${width}px: $visible", visible in 5 until DspCoeffBuilder.MAX_GRAPHIC)

            it.wheel(y, 3)
            val scrolled = it.settle()
            assertFalse("the wheel moved the slider row", scrolled.band(y - 100, y + 100).contentEquals(before.band(y - 100, y + 100)))
            assertTrue("the page stayed put while the row could still move", scrolled.band(0, 300).contentEquals(before.band(0, 300)))

            // far more notches than the row has room for: once it runs out the rest reach the page
            it.wheel(y, 40)
            val handedBack = it.settle()
            assertFalse("at the row's end the wheel scrolls the page again", handedBack.band(0, 300).contentEquals(scrolled.band(0, 300)))
        }
    }

    @Test fun theRightArrowPagesThroughTheRow() {
        val (scene, accent) = scene(List(DspCoeffBuilder.MAX_GRAPHIC) { 0f })
        scene.use {
            val before = it.settle()
            val (y, _) = thumbRow(before, accent)
            // the arrow is a 40dp button just inside the 12dp padding at the row's right edge
            it.click(width - 12f - 20f, y.toFloat())
            val paged = it.settle()
            assertFalse("the arrow moved the row", paged.band(y - 100, y + 100).contentEquals(before.band(y - 100, y + 100)))
        }
    }

    @Test fun screenshots() {
        if (shots == null) return
        val (scene, _) = scene(List(DspCoeffBuilder.MAX_GRAPHIC) { i -> (sin(i / 3.0) * 6).toFloat() }, height = 760)
        scene.use {
            val y = 545
            it.input { sendPointerEvent(PointerEventType.Move, Offset(width / 2f, y.toFloat())) }
            it.settle().saveTo(shots, "1-start")
            it.wheel(y, 5)
            it.settle().saveTo(shots, "2-wheel")
            it.wheel(y, 2)
            it.settle().saveTo(shots, "3-end")
        }
    }
}
