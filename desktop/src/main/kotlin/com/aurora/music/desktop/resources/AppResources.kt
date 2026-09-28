package com.aurora.music.desktop.resources

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import org.xml.sax.InputSource

private fun variableFontFamily(regular: String, italic: String? = null): FontFamily = FontFamily(
    buildList {
        for (weight in 100..900 step 100) {
            add(Font("font/$regular", FontWeight(weight)))
            if (italic != null) add(Font("font/$italic", FontWeight(weight), FontStyle.Italic))
        }
    },
)

val DmSans: FontFamily = variableFontFamily("dm_sans.ttf", "dm_sans_italic.ttf")
val PlusJakartaSans: FontFamily = variableFontFamily("plus_jakarta_sans.ttf", "plus_jakarta_sans_italic.ttf")
val Manrope: FontFamily = variableFontFamily("manrope.ttf")

val AuroraLogo: ImageVector by lazy {
    requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("drawable/ic_aurora_logo.xml"))
        .use { loadXmlImageVector(InputSource(it), Density(1f)) }
}
