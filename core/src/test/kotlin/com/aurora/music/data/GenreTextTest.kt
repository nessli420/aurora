package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreTextTest {
    @Test fun plainGenresPassThrough() {
        assertEquals(listOf("Alternative & Indie"), GenreText.split("  Alternative & Indie "))
        assertEquals(listOf("Musique brésilienne"), GenreText.split("Musique brésilienne"))
        assertEquals(listOf("K-Pop"), GenreText.split("K-Pop"))
    }

    @Test fun nulSeparatedGenresSplit() {
        assertEquals(listOf("Pop", "Rock"), GenreText.split("Pop\u0000Rock"))
    }

    @Test fun leakedFrameBytesAreDropped() {
        val leaked = "atopia\u0000\u0000\u0001ÿþetyy\u0003DcO\$\nbeabadoobee\u0000*Dirty Hit2\u0002"
        assertTrue(GenreText.split(leaked).isEmpty())
        assertTrue(GenreText.split("Hip Hop\u0001\u0007").isEmpty())
        assertTrue(GenreText.split("��").isEmpty())
    }

    @Test fun matchingUsesEachSplitGenre() {
        assertTrue(GenreText.matches("Pop\u0000Rock", "rock"))
        assertFalse(GenreText.matches("Pop\u0000Rock", "Jazz"))
    }
}
