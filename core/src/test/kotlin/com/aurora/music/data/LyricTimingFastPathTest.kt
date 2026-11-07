package com.aurora.music.data

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimingFastPathTest {
    @Test
    fun asciiNeedsNoNormalization() {
        for (code in 0 until 128) {
            val c = code.toChar()
            assertEquals(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar(), c.lowercaseChar())
        }
        assertEquals(2, syllables("café", false))
        assertEquals(2, syllables("naïve", false))
    }

    @Test
    fun repeatedStartsStillEndAtTheNextLaterLine() {
        val text = List(16) { "la" }.joinToString(" ")
        val lines = Lyrics(listOf(0L, 0L, 1000L).map { LyricsRepository.timedLine(it, text) }, true, "test").withWordTiming().lines
        assertTrue(lines[0].words.last().endMs in 1L..1000L)
        assertTrue(lines[1].words.last().endMs in 1L..1000L)
    }
}
