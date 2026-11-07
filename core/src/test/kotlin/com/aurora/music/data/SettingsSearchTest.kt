package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val settings = listOf(
        SettingsSearch.Candidate("crossfade", "Crossfade", "Blend the end of one song into the next", "Playback & Quality"),
        SettingsSearch.Candidate("gapless", "Gapless Playback", "No silence between album tracks", "Playback & Quality"),
        SettingsSearch.Candidate("eq", "Equalizer & Effects", "EQ, device presets, convolution and channels", ""),
        SettingsSearch.Candidate("theme", "Theme", "Light, dark or follow the system", "Appearance"),
        SettingsSearch.Candidate("skip", "Skip Buttons", "Show 10 second skip buttons for podcasts and long tracks", "Playback & Quality"),
        SettingsSearch.Candidate("russian", "Язык приложения", "Выберите язык", "Настройки"),
        SettingsSearch.Candidate("japanese", "ライブラリ", "最初のタブで開きます", ""),
        SettingsSearch.Candidate("cafe", "Café Mode", "Soft background levels", "Sound"),
    )

    private fun find(q: String) = SettingsSearch.rank(q, settings)

    @Test fun blankQueryFindsNothing() {
        assertTrue(find("").isEmpty())
        assertTrue(find("   ").isEmpty())
    }

    @Test fun titlesMatchFromTheStartOfAWord() {
        assertEquals("crossfade", find("cross").first())
        assertEquals("gapless", find("gap").first())
        assertEquals("eq", find("equal").first())
    }

    @Test fun descriptionsAndPagesAlsoMatch() {
        assertEquals(listOf("theme"), find("dark"))
        assertTrue(find("podcast").contains("skip"))
        assertEquals(setOf("crossfade", "gapless", "skip"), find("playback quality").toSet())
    }

    @Test fun everyWordMustMatch() {
        assertEquals(listOf("gapless"), find("gapless album"))
        assertTrue(find("gapless zebra").isEmpty())
    }

    @Test fun titleMatchesRankAboveDescriptionMatches() {
        val hits = find("playback")
        assertEquals("gapless", hits.first())
    }

    @Test fun caseAccentsAndPunctuationAreIgnored() {
        assertEquals(listOf("cafe"), find("CAFE"))
        assertEquals("eq", find("equalizer effects").first())
        assertEquals("eq", find("Equalizer & Effects").first())
    }

    @Test fun otherScriptsMatch() {
        assertEquals(listOf("russian"), find("язык"))
        assertEquals(listOf("japanese"), find("タブ"))
    }

    @Test fun shortFragmentsInsideWordsDoNotMatch() {
        assertTrue(find("ss").isEmpty())
    }
}
