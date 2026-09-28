package com.aurora.music.desktop.platform

import com.aurora.music.data.artwork.ArtworkImages
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.util.Base64
import kotlin.math.abs

object SkiaArtworkImages : ArtworkImages {
    override fun size(bytes: ByteArray): Pair<Int, Int>? = decodeImage(bytes)?.use { it.width to it.height }

    override fun isPlaceholder(bytes: ByteArray): Boolean = NavidromePlaceholder.matches(bytes)
}

internal fun decodeImage(bytes: ByteArray): Image? = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()

internal object NavidromePlaceholder {
    private val signature = Base64.getDecoder().decode("AQcRBjd8AVTJAVXOAFXQAFbRAVjTAFnTA17UHXXaFmrKEWC7BTqAAFnQAFfPAFXNAFbSAVfUAFnUAFrUF27ZJnzeC2zbCWDGAVrOAFnRAFrTAFbQAVbTAFTRAFbSCGDVJ3rdCmrbAGLaAWLYAFjPAFjQAFrUAFfRCkKbGTBUJ0FmJ2e5CWXYAGHZAGPaAGTbAFfPAVjRAFrTB0SjR0dGU1JQZWRhVlhXB1C0AGPaAGXcAWTbAFXOAFbSAFfSETJpIiAcHx4bJSQgIyEdEDt3AWTaA2vdAGbbAFLNAFTQAVXSDzd6KyomPTs2IyEdIB4bDD+IAGLZAGbbAGPaAE/LAFHOAFTRCFDCQlNqOTgzJSMfFy1OAVfNAGLaAWPaAGHYAE7KAE7MDVzQKnLXCljOCEWrB0esAFXRAF/ZAGDZAGDYAF/WA0m4DlnNLHLWHWjUAFTQAFXSAFfUAVjVAFvWAFzWAF3WBFzQAxxCHl/FKGzTBlLNAFDOAFLPAFTQAFXSAFfUAFjUAlnQEFq+AAEFBBIrDUioAEzKAE3MAE/NAFLOAFPQAFXSBlfLEVi8Eli5")

    fun matches(bytes: ByteArray): Boolean = decodeImage(bytes)?.use { image ->
        if (image.width < 16 || image.height < 16 || abs(image.width - image.height) > 2) return false
        var sample = 1
        while (image.width / sample > 128) sample *= 2
        val width = image.width / sample
        val height = image.height / sample
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val pixels = Bitmap().use { bitmap ->
            if (!bitmap.allocPixels(info)) return false
            Canvas(bitmap).use {
                it.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
            }
            bitmap.readPixels(info, width * 4, 0, 0)
        } ?: return false
        var error = 0L
        var close = 0
        var index = 0
        // skip the outer border which servers may composite onto different backgrounds
        for (y in 2 until 14) for (x in 2 until 14) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (py in y * height / 16 until (y + 1) * height / 16) {
                for (px in x * width / 16 until (x + 1) * width / 16) {
                    val offset = (py * width + px) * 4
                    red += pixels[offset].toInt() and 255
                    green += pixels[offset + 1].toInt() and 255
                    blue += pixels[offset + 2].toInt() and 255
                    count++
                }
            }
            if (count == 0) return false
            val difference = abs(red / count - (signature[index++].toInt() and 255)) +
                abs(green / count - (signature[index++].toInt() and 255)) +
                abs(blue / count - (signature[index++].toInt() and 255))
            error += difference
            if (difference < 75) close++
        }
        error < 144 * 3 * 12 && close >= 130
    } ?: false
}
