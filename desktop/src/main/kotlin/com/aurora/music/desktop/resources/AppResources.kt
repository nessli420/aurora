package com.aurora.music.desktop.resources

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import java.io.InputStream
import org.xml.sax.InputSource

private fun resource(path: String): InputStream =
    requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) { "Missing resource $path" }

@OptIn(ExperimentalTextApi::class)
private fun variableFontFamily(
    regular: String,
    italic: String? = null,
    weightRange: IntRange,
    opticalSize: Float? = null,
): FontFamily = FontFamily(
    buildList {
        for (weight in 100..900 step 100) {
            val settings = buildList {
                add(FontVariation.weight(weight.coerceIn(weightRange)))
                opticalSize?.let { add(FontVariation.Setting("opsz", it)) }
            }
            add(Font("font/$regular", FontWeight(weight), FontStyle.Normal, FontVariation.Settings(*settings.toTypedArray())))
            if (italic != null) {
                add(Font("font/$italic", FontWeight(weight), FontStyle.Italic, FontVariation.Settings(*settings.toTypedArray())))
            }
        }
    },
)

val DmSans: FontFamily = variableFontFamily("dm_sans.ttf", "dm_sans_italic.ttf", 100..1000, opticalSize = 14f)
val PlusJakartaSans: FontFamily = variableFontFamily("plus_jakarta_sans.ttf", "plus_jakarta_sans_italic.ttf", 200..800)
val Manrope: FontFamily = variableFontFamily("manrope.ttf", weightRange = 200..800)

@Suppress("DEPRECATION")
val AuroraLogo: ImageVector by lazy { resource("drawable/ic_aurora_logo.xml").use { loadXmlImageVector(InputSource(it), Density(1f)) } }
