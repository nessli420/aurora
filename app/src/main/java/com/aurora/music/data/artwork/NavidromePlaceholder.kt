package com.aurora.music.data.artwork

import android.graphics.BitmapFactory
import android.graphics.Color
import java.util.Base64
import kotlin.math.abs

/** Fingerprint of Navidrome's resources/album-placeholder.webp; never classify by color alone. */
internal object NavidromePlaceholder {
    private val signature = Base64.getDecoder().decode("AQcRBjd8AVTJAVXOAFXQAFbRAVjTAFnTA17UHXXaFmrKEWC7BTqAAFnQAFfPAFXNAFbSAVfUAFnUAFrUF27ZJnzeC2zbCWDGAVrOAFnRAFrTAFbQAVbTAFTRAFbSCGDVJ3rdCmrbAGLaAWLYAFjPAFjQAFrUAFfRCkKbGTBUJ0FmJ2e5CWXYAGHZAGPaAGTbAFfPAVjRAFrTB0SjR0dGU1JQZWRhVlhXB1C0AGPaAGXcAWTbAFXOAFbSAFfSETJpIiAcHx4bJSQgIyEdEDt3AWTaA2vdAGbbAFLNAFTQAVXSDzd6KyomPTs2IyEdIB4bDD+IAGLZAGbbAGPaAE/LAFHOAFTRCFDCQlNqOTgzJSMfFy1OAVfNAGLaAWPaAGHYAE7KAE7MDVzQKnLXCljOCEWrB0esAFXRAF/ZAGDZAGDYAF/WA0m4DlnNLHLWHWjUAFTQAFXSAFfUAVjVAFvWAFzWAF3WBFzQAxxCHl/FKGzTBlLNAFDOAFLPAFTQAFXSAFfUAFjUAlnQEFq+AAEFBBIrDUioAEzKAE3MAE/NAFLOAFPQAFXSBlfLEVi8Eli5")

    fun matches(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 16 || bounds.outHeight < 16 || abs(bounds.outWidth - bounds.outHeight) > 2) return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 128) inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        try {
            var error = 0L
            var close = 0
            var index = 0
            // Ignore the outer border, which servers may composite onto different backgrounds.
            for (y in 2 until 14) for (x in 2 until 14) {
                var red = 0; var green = 0; var blue = 0; var count = 0
                for (py in y * bitmap.height / 16 until (y + 1) * bitmap.height / 16) {
                    for (px in x * bitmap.width / 16 until (x + 1) * bitmap.width / 16) {
                        val color = bitmap.getPixel(px, py)
                        red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++
                    }
                }
                if (count == 0) return false
                val difference = abs(red / count - (signature[index++].toInt() and 255)) +
                    abs(green / count - (signature[index++].toInt() and 255)) +
                    abs(blue / count - (signature[index++].toInt() and 255))
                error += difference
                if (difference < 75) close++
            }
            return error < 144 * 3 * 12 && close >= 130
        } finally { bitmap.recycle() }
    }
}
