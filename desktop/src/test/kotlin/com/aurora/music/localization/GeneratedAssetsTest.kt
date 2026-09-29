package com.aurora.music.localization

import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.desktop.platform.BuildInfo
import com.aurora.music.desktop.resources.AuroraLogo
import com.aurora.music.desktop.resources.DmSans
import com.aurora.music.desktop.resources.Manrope
import com.aurora.music.desktop.resources.PlusJakartaSans
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedAssetsTest {
    private val loader = Thread.currentThread().contextClassLoader

    private fun width(family: FontFamily) = Paragraph(
        text = "The quick brown fox jumps over the lazy dog",
        style = TextStyle(fontFamily = family, fontSize = 20.sp),
        constraints = Constraints(maxWidth = 10_000),
        density = Density(1f),
        fontFamilyResolver = createFontFamilyResolver(),
    ).getLineWidth(0)

    @Test fun syncsOnlyTheOpenFonts() {
        val fonts = File(loader.getResource("font/manrope.ttf")!!.toURI()).parentFile.list()!!.toSet()
        assertEquals(setOf("dm_sans.ttf", "dm_sans_italic.ttf", "plus_jakarta_sans.ttf", "plus_jakarta_sans_italic.ttf", "manrope.ttf"), fonts)
    }

    @Test fun fontFamiliesPinTheRegularInstance() {
        listOf(DmSans, PlusJakartaSans, Manrope).forEach { assertNotEquals(0f, width(it)) }
        assertNotEquals(width(FontFamily(Font("font/manrope.ttf"))), width(Manrope))
    }

    @Test fun bundlesTheLicenceTexts() {
        assertTrue(loader.getResource("licenses/aurora-dst-LGPL-2.1.txt")!!.readText().contains("GNU LESSER GENERAL PUBLIC LICENSE"))
        val fonts = File(loader.getResource("font_licenses/manrope-OFL.txt")!!.toURI()).parentFile.list()!!.toSet()
        assertEquals(setOf("dmsans-OFL.txt", "manrope-OFL.txt", "plusjakartasans-OFL.txt"), fonts)
    }

    @Test fun buildInfoCarriesTheCatalogComposeVersion() {
        val catalog = File("../gradle/libs.versions.toml").readLines().first { it.startsWith("composeMultiplatform") }
        assertEquals(catalog.substringAfter('"').substringBefore('"'), BuildInfo.COMPOSE_VERSION)
    }

    @Test fun loadsTheLogo() {
        assertEquals(64.dp, AuroraLogo.defaultWidth)
        assertEquals(64f, AuroraLogo.viewportWidth)
    }
}
