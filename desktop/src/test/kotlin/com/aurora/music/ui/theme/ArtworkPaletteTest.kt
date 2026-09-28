package com.aurora.music.ui.theme

import coil3.PlatformContext
import com.aurora.music.util.ArtworkPalette
import com.aurora.music.util.Swatch
import com.aurora.music.util.artworkPalette
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    private fun Swatch?.hue() = requireNotNull(this).hsl[0]

    @Test fun prefersVibrantOverDominant() {
        val palette = ArtworkPalette.from(IntArray(1000) { if (it < 800) 0xFF6B6B6B.toInt() else 0xFFE0304A.toInt() })
        assertTrue(requireNotNull(palette.dominantSwatch).hsl[1] < 0.05f)
        assertEquals(palette.dominantSwatch, palette.mutedSwatch)
        assertTrue(palette.vibrantSwatch.hue() > 340f)
    }

    @Test fun lightVibrantClaimsSharedSwatchFirst() {
        val palette = ArtworkPalette.from(IntArray(1000) { if (it < 700) 0xFFFF3D7A.toInt() else 0xFF1E6FE6.toInt() })
        assertTrue(palette.lightVibrantSwatch.hue() > 330f)
        assertTrue(palette.vibrantSwatch.hue() in 205f..225f)
    }

    @Test fun medianCutKeepsMinorityHues() {
        val random = Random(1)
        val palette = ArtworkPalette.from(IntArray(4000) { i ->
            val color = if (i < 3200) 0x20C040 else 0xE0304A
            (0..2).fold(0xFF shl 24) { argb, d -> argb or (((color shr 8 * d and 0xFF) + random.nextInt(-8, 9)).coerceIn(0, 255) shl 8 * d) }
        })
        assertEquals(16, palette.swatches.size)
        assertTrue(palette.swatches.all { it.hsl[0] in 125f..145f || it.hsl[0] > 340f })
        assertTrue(palette.vibrantSwatch.hue() in 125f..145f)
    }

    @Test fun readsArtworkPixelsInArgbOrder() {
        val file = File.createTempFile("aurora-art", ".png").apply { deleteOnExit() }
        val art = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) art.setRGB(x, y, if (x < 24) 0x2050E0 else 0x707070)
        ImageIO.write(art, "png", file)
        val palette = runBlocking { artworkPalette(PlatformContext.INSTANCE, file.absolutePath) }
        assertTrue(palette?.vibrantSwatch.hue() in 215f..235f)
    }
}
