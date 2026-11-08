package com.aurora.music.desktop.resources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp

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

// the website's own in-between weights (560, 640, 720) get exact instances of the variable fonts
private fun exactWeights(file: String, weights: List<Int>, heaviest: Int = 1000): FontFamily = FontFamily(weights.map { weight ->
    Font("font/$file", FontWeight(weight), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(weight.coerceAtMost(heaviest))))
})
// the site never sets type heavier than 720, so screens that ask for extra bold or black get 720 too
val Archivo: FontFamily = exactWeights("archivo.ttf", (100..900 step 100).toList() + listOf(560, 640, 720), heaviest = 720)
// and its mono labels are always regular
val ChivoMono: FontFamily = exactWeights("chivo_mono.ttf", (100..900 step 100).toList(), heaviest = 400)

// mirrors android's ic_aurora_logo drawable; built in code so startup skips the xml parser
val AuroraLogo: ImageVector by lazy {
    ImageVector.Builder("logo", 64.dp, 64.dp, 64f, 64f).addPath(
        addPathNodes("M9,53 L28.4,11.3 Q32,3.8 35.6,11.3 L55,53 M16,39.5 C27,30 37,49 48,39.5"),
        stroke = SolidColor(Color(0xFFFF2E7E)), strokeLineWidth = 9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}
