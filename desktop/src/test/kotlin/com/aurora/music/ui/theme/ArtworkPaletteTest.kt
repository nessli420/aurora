package com.aurora.music.ui.theme

import coil3.PlatformContext
import com.aurora.music.util.ArtworkPalette
import com.aurora.music.util.Swatch
import com.aurora.music.util.artworkPalette
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    private fun Swatch?.hue() = requireNotNull(this).hsl[0]

    @Test fun prefersVibrantOverDominant() {
        val palette = ArtworkPalette.from(IntArray(1000) { if (it < 800) 0xFF6B6B6B.toInt() else 0xFFE0304A.toInt() })
        println("DEBUG " + palette.swatches.joinToString { Integer.toHexString(it.rgb) + " x" + it.population + " " + it.hsl.toList() })
        val px = IntArray(1000) { if (it < 800) 0xFF6B6B6B.toInt() else 0xFFE0304A.toInt() }
        val q = com.materialkolor.quantize.QuantizerCelebi
        println("DEBUG two50 " + q.quantize(IntArray(1000) { if (it < 500) 0xFF6B6B6B.toInt() else 0xFFE0304A.toInt() }, 16))
        println("DEBUG three " + q.quantize(IntArray(900) { listOf(0xFF6B6B6B.toInt(), 0xFFE0304A.toInt(), 0xFF2050E0.toInt())[it % 3] }, 16))
        println("DEBUG rg " + q.quantize(IntArray(1000) { if (it < 800) 0xFF00FF00.toInt() else 0xFFFF0000.toInt() }, 16))
        val rnd = java.util.Random(1)
        fun jitter(c: Int) = (0xFF shl 24) or (((c shr 16 and 255) + rnd.nextInt(13) - 6).coerceIn(0, 255) shl 16) or (((c shr 8 and 255) + rnd.nextInt(13) - 6).coerceIn(0, 255) shl 8) or ((c and 255) + rnd.nextInt(13) - 6).coerceIn(0, 255)
        println("DEBUG noisy rg " + q.quantize(IntArray(1000) { jitter(if (it < 800) 0xFF00FF00.toInt() else 0xFFFF0000.toInt()) }, 16).entries.sortedByDescending { it.value }.joinToString { Integer.toHexString(it.key) + "=" + it.value })
        println("DEBUG noisy three " + q.quantize(IntArray(900) { jitter(listOf(0xFF6B6B6B.toInt(), 0xFFE0304A.toInt(), 0xFF2050E0.toInt())[it % 3]) }, 16).entries.sortedByDescending { it.value }.joinToString { Integer.toHexString(it.key) + "=" + it.value })
        val wu = Class.forName("com.materialkolor.quantize.QuantizerWu").getDeclaredConstructor().newInstance()
        val m = wu.javaClass.methods.first { it.name.startsWith("quantize") }
        println("DEBUG wu rg " + m.invoke(wu, IntArray(1000) { if (it < 800) 0xFF00FF00.toInt() else 0xFFFF0000.toInt() }, 16))
        println("DEBUG wu three " + m.invoke(wu, IntArray(900) { listOf(0xFF6B6B6B.toInt(), 0xFFE0304A.toInt(), 0xFF2050E0.toInt())[it % 3] }, 16))
        println("DEBUG grad " + q.quantize(IntArray(1000) { (0xFF shl 24) or ((it % 256) shl 16) or (((it / 4) % 256) shl 8) or 40 }, 16).size)
        println("DEBUG celebi rand " + com.materialkolor.quantize.QuantizerCelebi.quantize(IntArray(4096) { java.util.Random(it.toLong()).nextInt() or (0xFF shl 24) }, 16).size)
        assertTrue(requireNotNull(palette.dominantSwatch).hsl[1] < 0.05f)
        assertEquals(palette.dominantSwatch, palette.mutedSwatch)
        assertTrue(palette.vibrantSwatch.hue() > 340f)
    }

    @Test fun lightVibrantClaimsSharedSwatchFirst() {
        val palette = ArtworkPalette.from(IntArray(1000) { if (it < 700) 0xFFFF3D7A.toInt() else 0xFF1E6FE6.toInt() })
        assertTrue(palette.lightVibrantSwatch.hue() > 330f)
        assertTrue(palette.vibrantSwatch.hue() in 205f..225f)
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
