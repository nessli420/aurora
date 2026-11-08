package com.aurora.music.data

import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSortTest {
    private fun song(id: String, title: String, artist: String, album: String, added: Long, duration: Int) =
        Song(id = id, title = title, artist = artist, album = album, artworkUrl = "", durationSec = duration, dateAddedSec = added)

    private val songs = listOf(
        song("1", "beta", "Zed", "Moon", added = 300, duration = 200),
        song("2", "Alpha", "anna", "Sun", added = 100, duration = 180),
        song("3", "Gamma", "Anna", "Earth", added = 200, duration = 240),
    )

    private fun ids(order: TrackSortOrder) = order.apply(songs).map { it.id }

    @Test fun customKeepsTheCollectionOrder() {
        assertEquals(listOf("1", "2", "3"), ids(TrackSortOrder()))
        assertEquals(listOf("3", "2", "1"), ids(TrackSortOrder(TrackSort.CUSTOM, descending = true)))
    }

    @Test fun textSortsIgnoreCase() {
        assertEquals(listOf("2", "1", "3"), ids(TrackSortOrder(TrackSort.TITLE)))
        // same artist falls back to album
        assertEquals(listOf("3", "2", "1"), ids(TrackSortOrder(TrackSort.ARTIST)))
        assertEquals(listOf("3", "1", "2"), ids(TrackSortOrder(TrackSort.ALBUM)))
    }

    @Test fun recentlyAddedStartsNewestFirstAndDurationShortestFirst() {
        assertEquals(listOf("1", "3", "2"), ids(TrackSortOrder().pick(TrackSort.RECENTLY_ADDED)))
        assertEquals(listOf("2", "1", "3"), ids(TrackSortOrder().pick(TrackSort.DURATION)))
    }

    @Test fun pickingTheActiveSortFlipsIt() {
        val title = TrackSortOrder().pick(TrackSort.TITLE)
        assertFalse(title.descending)
        assertTrue(title.pick(TrackSort.TITLE).descending)
        assertEquals(TrackSortOrder(TrackSort.ARTIST), title.pick(TrackSort.ARTIST))
    }

    @Test fun sortsRoundTripPerCollectionAndDropTheDefault() {
        var raw = TrackSorts.encode(emptyMap(), "playlist:a", TrackSortOrder(TrackSort.TITLE))
        raw = TrackSorts.encode(TrackSorts.decode(raw), "liked:x", TrackSortOrder(TrackSort.RECENTLY_ADDED, true))
        val decoded = TrackSorts.decode(raw)
        assertEquals(TrackSortOrder(TrackSort.TITLE), decoded["playlist:a"])
        assertEquals(TrackSortOrder(TrackSort.RECENTLY_ADDED, true), decoded["liked:x"])
        val reset = TrackSorts.decode(TrackSorts.encode(decoded, "playlist:a", TrackSortOrder()))
        assertFalse("playlist:a" in reset)
        assertTrue(TrackSorts.decode("garbage\nno\ttabs\tHERE").isEmpty())
    }
}
