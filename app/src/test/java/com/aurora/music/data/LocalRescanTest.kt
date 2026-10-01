package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRescanTest {
    @Test fun countsAddedAndRemovedSongsByKey() {
        val result = LocalRescan.result(setOf("/m/a.flac", "/m/b.flac", "/m/c.flac"), setOf("/m/b.flac", "/m/c.flac", "/m/d.flac", "/m/e.flac"))
        assertEquals(LocalRescanResult(added = 2, removed = 1, total = 4), result)
        assertTrue(result.changed)
    }

    @Test fun anUnchangedLibraryReportsNoChange() {
        val songs = setOf("/m/a.flac", "/m/b.flac")
        val result = LocalRescan.result(songs, songs)
        assertEquals(LocalRescanResult(0, 0, 2), result)
        assertFalse(result.changed)
    }

    @Test fun scanRootsDropNestedDuplicateAndBlankFolders() {
        val roots = LocalRescan.roots(listOf("/storage/emulated/0/Music/Album", "/storage/emulated/0/Music/", "",
            "/storage/emulated/0/Music", "/storage/emulated/0/Download", "/storage/emulated/0/Music/Album/CD1"))
        assertEquals(listOf("/storage/emulated/0/Music", "/storage/emulated/0/Download"), roots)
    }

    @Test fun scanRootsKeepSiblingsThatOnlyShareAPrefix() {
        assertEquals(listOf("/sd/Music", "/sd/Music2"), LocalRescan.roots(listOf("/sd/Music2", "/sd/Music")))
    }
}
