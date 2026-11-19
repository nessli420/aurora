package com.aurora.music.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchScoreTest {
    @Test
    fun ranksExactOverPrefixOverWordStart() {
        assertEquals(3, matchScore(" Lunar ", "lunar"))
        assertEquals(2, matchScore("lun", "Lunar Tide"))
        assertEquals(1, matchScore("tide", "Lunar Tide"))
        assertEquals(0, matchScore("una", "Lunar Tide"))
        assertEquals(0, matchScore("  ", "Lunar Tide"))
    }
}
