package com.aurora.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.resources.DmSans
import com.aurora.music.desktop.resources.Manrope
import com.aurora.music.desktop.resources.PlusJakartaSans
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.LottieEqualizer
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.Waveform
import java.io.File
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeShowcaseTest {
    private val shots = System.getenv("AURORA_SHOTS")?.let(::File)
    private val resolver = createFontFamilyResolver()
    private val weights = listOf(400, 500, 600, 700, 900)

    private fun width(family: FontFamily, weight: Int, style: FontStyle = FontStyle.Normal) = Paragraph(
        text = "The quick brown fox jumps over the lazy dog",
        style = TextStyle(fontFamily = family, fontWeight = FontWeight(weight), fontStyle = style, fontSize = 20.sp),
        constraints = Constraints(maxWidth = 10_000),
        density = Density(1f),
        fontFamilyResolver = resolver,
    ).getLineWidth(0)

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit): Image {
        val scene = ImageComposeScene(width, height, Density(1f), content = content)
        listOf(0L, 16, 32, 432).forEach {
            scene.render(it * 1_000_000)
            Snapshot.sendApplyNotifications()
        }
        val image = scene.render(432_000_000)
        scene.close()
        shots?.let { File(it.apply { mkdirs() }, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        return image
    }

    private fun Image.luminance(): Float {
        val bitmap = Bitmap.makeFromImage(this)
        var total = 0f
        for (y in 0 until height step 8) for (x in 0 until width step 8) {
            val c = bitmap.getColor(x, y)
            total += (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
        }
        return total / ((height + 7) / 8 * ((width + 7) / 8))
    }

    @Test fun variableFontsRenderEveryWeight() {
        listOf(DmSans, PlusJakartaSans, Manrope).forEach { family ->
            val widths = weights.map { width(family, it) }
            assertEquals(widths.sorted(), widths)
            assertEquals(weights.size, widths.distinct().size)
        }
        assertNotEquals(width(DmSans, 400), width(DmSans, 400, FontStyle.Italic))
        assertNotEquals(width(PlusJakartaSans, 700), width(PlusJakartaSans, 700, FontStyle.Italic))
    }

    @Test fun rendersFontSpecimen() {
        render("fonts", 960, 760) {
            AuroraTheme(UiPrefs(themeMode = ThemeMode.LIGHT)) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
                    listOf("DM Sans" to DmSans, "Plus Jakarta Sans" to PlusJakartaSans, "Manrope" to Manrope).forEach { (name, family) ->
                        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        weights.forEach { weight ->
                            Row {
                                Text("$weight Aurora plays music", fontFamily = family, fontWeight = FontWeight(weight), fontSize = 20.sp, modifier = Modifier.width(440.dp))
                                Text("$weight Aurora plays music", fontFamily = family, fontWeight = FontWeight(weight), fontStyle = FontStyle.Italic, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun rendersEveryStyle() {
        val names = mapOf(ThemeStyle.AURORA to "aurora", ThemeStyle.RETRO to "retro", ThemeStyle.AERO to "aero", ThemeStyle.GLASS to "glass")
        val images = names.flatMap { (style, name) ->
            listOf(true, false).map { dark ->
                val prefs = UiPrefs(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, themeStyle = style)
                val image = render("$name-${if (dark) "dark" else "light"}", 760, 980) { AuroraTheme(prefs) { Showcase() } }
                if (dark) assertTrue(image.luminance() < 0.3f) else assertTrue(image.luminance() > 0.6f)
                Bitmap.makeFromImage(image).readPixels()!!.contentHashCode()
            }
        }
        assertEquals(images.size, images.distinct().size)
    }

    @Composable
    private fun Showcase() {
        val colors = MaterialTheme.colorScheme
        val type = MaterialTheme.typography
        Box(Modifier.fillMaxSize()) {
            AmbientBackground()
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(ThemeIdentities[LocalUiPrefs.current.themeStyle].name, action = "See all", onAction = {})
                Eyebrow("NOW PLAYING", colors.primary)
                weights.forEach { Text("$it Aurora plays music", style = type.titleLarge.copy(fontWeight = FontWeight(it))) }
                Text("Display small", style = type.displaySmall)
                Text("Headline medium", style = type.headlineMedium)
                Text("Title medium", style = type.titleMedium)
                Text("Body medium text for longer descriptions", style = type.bodyMedium, color = colors.onSurfaceVariant)
                Text("LABEL SMALL", style = type.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(300.dp, 110.dp).auroraPanel().padding(16.dp)) { Text("Panel", style = type.titleMedium) }
                    Box(Modifier.size(300.dp, 110.dp).auroraPanel(emphasized = true).padding(16.dp)) { Text("Emphasized", style = type.titleMedium) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork("", AuroraRose, Modifier.size(96.dp))
                    Artwork("", colors.tertiary, Modifier.size(96.dp), corner = 24.dp)
                    LottieLoader(Modifier.size(72.dp))
                    LottieEqualizer(Modifier.size(56.dp))
                }
                Waveform(progress = 0.4f, accent = colors.primary, onSeek = {}, seed = 7, animated = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(colors.primary, colors.primaryContainer, colors.secondary, colors.tertiary, colors.surface, colors.surfaceContainerHigh, colors.outline)
                        .forEach { Box(Modifier.size(40.dp).background(it, MaterialTheme.shapes.small)) }
                }
            }
        }
    }
}
