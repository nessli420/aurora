package com.aurora.music.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.saveTo
import com.aurora.music.ui.theme.AuroraTheme
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// the mac bar only paints the logo and name: macos draws the traffic lights over its first 78pt
class MacTitleBarTest {
    private val shots = System.getenv("AURORA_SHOTS")?.let { File(it, "mac-title-bar") }

    private fun scene(prefs: UiPrefs, lights: Boolean = false) = EdtScene(720, 140) {
        AuroraTheme(prefs) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box {
                    MacTitleBarContent()
                    // stand-ins for the native traffic lights, only for the screenshots
                    if (lights) Row(Modifier.height(MacBarHeight).padding(start = 9.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        listOf(0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840).forEach { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(it))) }
                    }
                }
                Text("Good evening", Modifier.padding(start = 24.dp, top = 16.dp), style = MaterialTheme.typography.headlineMedium)
            }
        }
    }

    @Test fun theLogoStartsClearOfTheTrafficLightsInsideTheNativeBarHeight() {
        val prefs = UiPrefs(themeMode = ThemeMode.LIGHT)
        scene(prefs).use { scene ->
            scene.frame(16)
            val image = scene.frame(16)
            val bitmap = Bitmap.makeFromImage(image)
            val background = bitmap.getColor(image.width - 1, 1)
            // the scene renders at 1dp = 1px; the page's own text starts below row 40
            val bar = (0 until image.width).flatMap { x -> (0 until 40).map { y -> x to y } }
                .filter { (x, y) -> bitmap.getColor(x, y) != background }
            assertTrue("the bar draws something", bar.isNotEmpty())
            val left = bar.minOf { it.first }
            val top = bar.minOf { it.second }
            val bottom = bar.maxOf { it.second }
            assertTrue("nothing under the traffic lights: logo starts at $left", left >= MacLightsWidth.value.toInt())
            assertTrue("logo and name sit inside the 28pt native bar: $top..$bottom", top > 0 && bottom < MacBarHeight.value.toInt())
        }
    }

    @Test fun screenshots() {
        val dir = shots ?: return
        listOf(
            "aurora-dark" to UiPrefs(themeMode = ThemeMode.DARK),
            "aurora-light" to UiPrefs(themeMode = ThemeMode.LIGHT),
            "paper" to UiPrefs(themeStyle = ThemeStyle.PAPER),
        ).forEach { (name, prefs) ->
            scene(prefs, lights = true).use { scene -> scene.frame(16); scene.frame(16).saveTo(dir, name) }
        }
    }
}
