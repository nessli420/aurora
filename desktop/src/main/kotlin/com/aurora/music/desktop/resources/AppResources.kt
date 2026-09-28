package com.aurora.music.desktop.resources

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.unit.Density
import java.io.InputStream
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.xml.sax.InputSource

private fun resource(path: String): InputStream =
    requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) { "Missing resource $path" }

// compose 1.7 desktop fonts ignore variation axes, so pin one instance and let compose synthesize bold
private fun variableFontFamily(file: String, vararg axes: FontVariation): FontFamily {
    val typeface = requireNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(resource("font/$file").use { it.readBytes() })))
    return FontFamily(Typeface(typeface.makeClone(arrayOf(*axes), 0), "aurora-${file.substringBefore('.')}"))
}

val DmSans: FontFamily by lazy { variableFontFamily("dm_sans.ttf", FontVariation("wght", 400f), FontVariation("opsz", 14f)) }
val PlusJakartaSans: FontFamily by lazy { variableFontFamily("plus_jakarta_sans.ttf", FontVariation("wght", 400f)) }
val Manrope: FontFamily by lazy { variableFontFamily("manrope.ttf", FontVariation("wght", 400f)) }

@Suppress("DEPRECATION")
val AuroraLogo: ImageVector by lazy { resource("drawable/ic_aurora_logo.xml").use { loadXmlImageVector(InputSource(it), Density(1f)) } }
