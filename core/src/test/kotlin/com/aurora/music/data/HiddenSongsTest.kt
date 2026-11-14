package com.aurora.music.data

import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenSongsTest {
    private fun song(id: String, title: String, artist: String, duration: Int) = Song(id, title, artist, "", "", duration)

    private val navidrome = song("nd1", "Loving Machine", "TV Girl", 227)
    private val spotify = song("s" + "0".repeat(64) + MERGE_NAMESPACE_SEP + "spotify:track:1", "Loving Machine (feat. Someone)", "TV Girl, Someone", 225)
    private val soundcloud = song("sc:42", "TV Girl - Loving Machine", "TV Girl Archive", 229)
    private val accented = song("yt:1", "Lóving Machine!", "The TV Girl", 228)
    private val live = song("nd2", "Loving Machine", "TV Girl", 262)
    private val otherArtist = song("nd3", "Loving Machine", "Someone Else", 227)
    private val remix = song("nd4", "Loving Machine (Remix)", "TV Girl", 227)

    @Test fun hidingOneSourceHidesTheSameRecordingEverywhere() {
        val hidden = HiddenSongs(listOf(HiddenSong.of(navidrome)))
        listOf(navidrome, spotify, soundcloud, accented).forEach { assertTrue(it.title, hidden.hides(it)) }
        listOf(live, otherArtist, remix).forEach { assertFalse(it.title, hidden.hides(it)) }
    }

    @Test fun anUploadHiddenOnSoundCloudHidesTheLibraryCopy() {
        val hidden = HiddenSongs(listOf(HiddenSong.of(soundcloud)))
        assertTrue(hidden.hides(navidrome))
        assertTrue(hidden.hides(spotify))
        assertFalse(hidden.hides(live))
    }

    @Test fun theExactItemStaysHiddenWhenMatchingCannot() {
        val untagged = song("local:9", "Track 01", "", 0)
        val hidden = HiddenSongs(listOf(HiddenSong.of(untagged)))
        assertTrue(hidden.hides(untagged))
        assertFalse(hidden.hides(song("local:10", "Track 01", "", 0)))
    }

    @Test fun playbackListsDropEveryCopyOfAHiddenSong() {
        val list = listOf(navidrome, live, spotify, otherArtist)
        assertEquals(listOf(live, otherArtist), HiddenSongs(listOf(HiddenSong.of(navidrome))).visible(list))
        assertSame(list, HiddenSongs.NONE.visible(list))
    }

    @Test fun unhidingAnotherSourcesCopyFindsTheEntryThatHidesIt() {
        val entry = HiddenSong.of(navidrome)
        val hidden = HiddenSongs(listOf(entry, HiddenSong.of(otherArtist)))
        assertEquals(listOf(entry), hidden.entriesFor(soundcloud))
        assertEquals(listOf(entry), hidden.entriesFor(navidrome))
        assertEquals(emptyList<HiddenSong>(), hidden.entriesFor(live))
    }
}
