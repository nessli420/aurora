package com.aurora.music.desktop.platform

import com.aurora.music.data.artwork.ArtworkImages
import com.aurora.music.data.artwork.NavidromePlaceholder
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

object SkiaArtworkImages : ArtworkImages {
    override fun size(bytes: ByteArray): Pair<Int, Int>? = decodeImage(bytes)?.use { it.width to it.height }

    override fun isPlaceholder(bytes: ByteArray): Boolean = decodeImage(bytes)?.use { image ->
        if (!NavidromePlaceholder.sizeEligible(image.width, image.height)) return false
        val sample = NavidromePlaceholder.sampleSize(image.width)
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
        NavidromePlaceholder.matches(width, height) { x, y ->
            val offset = (y * width + x) * 4
            (pixels[offset].toInt() and 255 shl 16) or (pixels[offset + 1].toInt() and 255 shl 8) or (pixels[offset + 2].toInt() and 255)
        }
    } ?: false
}

internal fun decodeImage(bytes: ByteArray): Image? = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
