package com.aurora.music.data

import com.aurora.music.model.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyViewFallbackTest {
    private val merged = listOf("library", "spotify", "soundcloud")

    @Test
    fun emptyLibraryOpensTheFirstStreamingView() {
        assertEquals("spotify", EmptyViewFallback.pick(merged, "library", chosen = false, empty = true))
    }

    @Test
    fun staysWhenTheLibraryHasMusic() {
        assertNull(EmptyViewFallback.pick(merged, "library", chosen = false, empty = false))
    }

    @Test
    fun aViewTheUserPickedIsKept() {
        assertNull(EmptyViewFallback.pick(merged, "library", chosen = true, empty = true))
    }

    @Test
    fun onlyLeavesTheLibraryViewAndNeverLoops() {
        // an empty streaming view stays put rather than bouncing on to the next one
        assertNull(EmptyViewFallback.pick(merged, "spotify", chosen = false, empty = true))
        assertNull(EmptyViewFallback.pick(listOf("library"), "library", chosen = false, empty = true))
        assertNull(EmptyViewFallback.pick(emptyList(), "library", chosen = false, empty = true))
        // offline views use their own ids and are left alone
        assertNull(EmptyViewFallback.pick(listOf("downloads", "cache"), "downloads", chosen = false, empty = true))
    }

    @Test
    fun homeDataBlankness() {
        assertTrue(HomeData().isBlank)
        assertTrue(HomeData(sections = listOf(HomeFeedSection("a", "A", items = emptyList()))).isBlank)
        assertFalse(HomeData(newReleases = listOf(Album(id = "1", title = "x", artist = "y", artworkUrl = "", year = 0, songCount = 1))).isBlank)
    }
}
