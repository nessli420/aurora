package com.aurora.music.data

import com.aurora.music.model.LibraryFilter.ALBUMS
import com.aurora.music.model.LibraryFilter.ALL
import com.aurora.music.model.LibraryFilter.ARTISTS
import com.aurora.music.model.LibraryFilter.DOWNLOADED
import com.aurora.music.model.LibraryFilter.PLAYLISTS
import com.aurora.music.model.LibraryFilter.SONGS
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTabsTest {
    @Test fun defaultsToEveryTabInTheUsualOrder() {
        assertEquals(listOf(ALL, PLAYLISTS, ALBUMS, ARTISTS, SONGS, DOWNLOADED), LibraryTabs.decode(null).visible)
    }

    @Test fun songsCanMoveFirstAndOthersCanBeHidden() {
        val tabs = LibraryTabs().move(SONGS, -4).toggle(ALL).toggle(ARTISTS)
        assertEquals(listOf(SONGS, PLAYLISTS, ALBUMS, DOWNLOADED), tabs.visible)
        assertEquals(tabs, LibraryTabs.decode(tabs.encode()))
    }

    @Test fun theLastVisibleTabCannotBeHidden() {
        var tabs = LibraryTabs()
        listOf(ALL, PLAYLISTS, ALBUMS, ARTISTS, SONGS, DOWNLOADED).forEach { tabs = tabs.toggle(it) }
        assertEquals(listOf(DOWNLOADED), tabs.visible)
    }

    @Test fun unknownAndMissingTabsAreRepaired() {
        val tabs = LibraryTabs.decode("SONGS,-ALL,RADIO,SONGS")
        assertEquals(listOf(SONGS, ALL, PLAYLISTS, ALBUMS, ARTISTS, DOWNLOADED), tabs.order)
        assertEquals(setOf(ALL), tabs.hidden)
        assertEquals(LibraryTabs().visible, LibraryTabs.decode("-ALL,-PLAYLISTS,-ALBUMS,-ARTISTS,-SONGS,-DOWNLOADED").visible)
    }

    @Test fun movingPastTheEdgesStops() {
        assertEquals(LibraryTabs(), LibraryTabs().move(ALL, -1))
        assertEquals(listOf(PLAYLISTS, ALBUMS, ARTISTS, SONGS, DOWNLOADED, ALL), LibraryTabs().move(ALL, 99).order)
    }
}
