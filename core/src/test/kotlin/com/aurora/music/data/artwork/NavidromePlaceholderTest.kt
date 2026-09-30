package com.aurora.music.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavidromePlaceholderTest {
    private fun signatureAt(size: Int, x: Int, y: Int): Int {
        val cellX = x * 16 / size
        val cellY = y * 16 / size
        if (cellX !in 2 until 14 || cellY !in 2 until 14) return 0xFFFFFF
        val index = ((cellY - 2) * 12 + cellX - 2) * 3
        val bytes = NavidromePlaceholder.signature
        return (bytes[index].toInt() and 255 shl 16) or (bytes[index + 1].toInt() and 255 shl 8) or (bytes[index + 2].toInt() and 255)
    }

    @Test fun matchesTheSignatureGridWhateverTheBorder() {
        assertTrue(NavidromePlaceholder.matches(128, 128) { x, y -> signatureAt(128, x, y) })
        assertTrue(NavidromePlaceholder.matches(64, 64) { x, y -> signatureAt(64, x, y) })
    }

    @Test fun rejectsFlatOrTinyImages() {
        assertFalse(NavidromePlaceholder.matches(128, 128) { _, _ -> 0x2255CC })
        assertFalse(NavidromePlaceholder.matches(8, 8) { x, y -> signatureAt(8, x, y) })
    }

    @Test fun onlySquareImagesAreSampledDownToTheScoringWidth() {
        assertTrue(NavidromePlaceholder.sizeEligible(300, 302))
        assertFalse(NavidromePlaceholder.sizeEligible(300, 303))
        assertFalse(NavidromePlaceholder.sizeEligible(15, 15))
        assertEquals(1, NavidromePlaceholder.sampleSize(128))
        assertEquals(4, NavidromePlaceholder.sampleSize(300))
    }
}
