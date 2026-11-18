package com.aurora.music.data

import com.aurora.music.data.remote.GeniusHit
import com.aurora.music.data.remote.GeniusUserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniusMeaningsTest {
    private val lines = listOf(
        "Paper lanterns over the harbour",
        "I counted every boat that never came",
        "Hold on, hold on",
        "We were young and the night was ours (night was ours)",
        "Don't let the morning find us",
        "",
        "Hold on, hold on",
        "Salt in the wind, salt on my tongue",
    )

    @Test fun aPartOfOneLineLandsOnThatLine() {
        assertEquals(listOf(1..1), LyricFragments.locate(lines, "every boat that never came"))
    }

    @Test fun aFragmentOverSeveralLinesCoversThemAll() {
        assertEquals(listOf(0..1), LyricFragments.locate(lines, "Paper lanterns over the harbour\n I counted every boat"))
    }

    @Test fun punctuationCaseAndApostrophesAreIgnored() {
        assertEquals(listOf(4..4), LyricFragments.locate(lines, "DONT let the Morning find us!"))
        assertEquals(listOf(4..4), LyricFragments.locate(lines, "Don’t let the morning, find us"))
    }

    @Test fun backingVocalsInBracketsDoNotBreakAMatch() {
        assertEquals(listOf(3..3), LyricFragments.locate(lines, "We were young and the night was ours"))
    }

    @Test fun aRepeatedChorusIsMarkedEveryTime() {
        assertEquals(listOf(2..2, 6..6), LyricFragments.locate(lines, "Hold on, hold on"))
    }

    @Test fun aShortFragmentMustFillWholeLines() {
        assertTrue(LyricFragments.locate(lines, "the night").isEmpty())
    }

    @Test fun aCloseLongFragmentStillMatchesOnce() {
        assertEquals(listOf(7..7), LyricFragments.locate(lines, "Salt in the wind, salt upon my tongue"))
        assertTrue(LyricFragments.locate(lines, "Salt in the winds, salt upon my tongue").isEmpty())
    }

    @Test fun anUnrelatedFragmentMatchesNothing() {
        assertTrue(LyricFragments.locate(lines, "nothing in this song sounds like this line at all").isEmpty())
        assertTrue(LyricFragments.locate(lines, "  ").isEmpty())
    }

    @Test fun theMarkerSitsOnTheLastLineOfASpan() {
        val ref = GeniusReferent("Paper lanterns over the harbour\n I counted every boat", "", listOf(GeniusNote("note")))
        val meanings = LyricMeanings.place(GeniusSong(found = true, referents = listOf(ref)), lines)
        assertEquals(listOf(ref), meanings.at(0))
        assertEquals(listOf(ref), meanings.at(1))
        assertTrue(meanings.markedAt(1))
        assertTrue(!meanings.markedAt(0))
    }

    private fun hit(title: String, artist: String, instrumental: Boolean = false) =
        GeniusHit(id = 1, title = title, artistNames = artist, primaryArtist = GeniusUserDto(name = artist), instrumental = instrumental)

    @Test fun theHitWithTheSameTitleAndArtistIsPicked() {
        val right = hit("Harbour Lights", "The Lanterns")
        val picked = GeniusMeanings.pickHit("Lanterns feat. Someone", "Harbour Lights (2019 Remaster)",
            listOf(hit("Harbour Lights (Turkish translation)", "Genius Translations"), hit("Harbour Lights", "Other Band"), right))
        assertEquals(right, picked)
    }

    @Test fun aWrongSongIsSkippedRatherThanShown() {
        assertNull(GeniusMeanings.pickHit("The Lanterns", "Harbour Lights", listOf(hit("Harbour Nights", "The Lanterns"))))
        assertNull(GeniusMeanings.pickHit("The Lanterns", "Harbour Lights", listOf(hit("Harbour Lights", "The Lanterns", instrumental = true))))
        assertNull(GeniusMeanings.pickHit("The Lanterns", "Harbour Lights", emptyList()))
    }
}
