package com.aurora.music.data.artwork

import java.util.Base64
import kotlin.math.abs

// fingerprint of navidrome's album placeholder never classify by colour alone
object NavidromePlaceholder {
    private const val MAX_SAMPLED_WIDTH = 128
    internal val signature: ByteArray = Base64.getDecoder().decode("AQcRBjd8AVTJAVXOAFXQAFbRAVjTAFnTA17UHXXaFmrKEWC7BTqAAFnQAFfPAFXNAFbSAVfUAFnUAFrUF27ZJnzeC2zbCWDGAVrOAFnRAFrTAFbQAVbTAFTRAFbSCGDVJ3rdCmrbAGLaAWLYAFjPAFjQAFrUAFfRCkKbGTBUJ0FmJ2e5CWXYAGHZAGPaAGTbAFfPAVjRAFrTB0SjR0dGU1JQZWRhVlhXB1C0AGPaAGXcAWTbAFXOAFbSAFfSETJpIiAcHx4bJSQgIyEdEDt3AWTaA2vdAGbbAFLNAFTQAVXSDzd6KyomPTs2IyEdIB4bDD+IAGLZAGbbAGPaAE/LAFHOAFTRCFDCQlNqOTgzJSMfFy1OAVfNAGLaAWPaAGHYAE7KAE7MDVzQKnLXCljOCEWrB0esAFXRAF/ZAGDZAGDYAF/WA0m4DlnNLHLWHWjUAFTQAFXSAFfUAVjVAFvWAFzWAF3WBFzQAxxCHl/FKGzTBlLNAFDOAFLPAFTQAFXSAFfUAFjUAlnQEFq+AAEFBBIrDUioAEzKAE3MAE/NAFLOAFPQAFXSBlfLEVi8Eli5")

    fun sizeEligible(width: Int, height: Int): Boolean = width >= 16 && height >= 16 && abs(width - height) <= 2

    fun sampleSize(width: Int): Int {
        var sample = 1
        while (width / sample > MAX_SAMPLED_WIDTH) sample *= 2
        return sample
    }

    fun matches(width: Int, height: Int, rgbAt: (x: Int, y: Int) -> Int): Boolean {
        var error = 0L
        var close = 0
        var index = 0
        // skip the outer border which servers may composite onto different backgrounds
        for (y in 2 until 14) for (x in 2 until 14) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (py in y * height / 16 until (y + 1) * height / 16) {
                for (px in x * width / 16 until (x + 1) * width / 16) {
                    val rgb = rgbAt(px, py)
                    red += rgb shr 16 and 255; green += rgb shr 8 and 255; blue += rgb and 255; count++
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
    }
}
