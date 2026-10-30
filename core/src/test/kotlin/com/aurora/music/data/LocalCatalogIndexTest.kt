package com.aurora.music.data

import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCatalogIndexTest {
    private fun song(id: String, title: String, album: String = "Twenty Twenty", file: String = "$title.flac") =
        Song(id, title, "Djo", album, "", 0, path = "/music/album/$file")

    @Test fun folderTracksFollowTagOrderNotTitles() {
        val songs = listOf(song("a", "BNBG"), song("b", "Showtime"), song("c", "Personal Lies"), song("d", "Tentpole Shangrila"))
        val dirs = songs.associate { it.id to "/music/album" }
        val positions = mapOf("b" to 1, "c" to 2, "d" to 3, "a" to 10_001)
        val (_, here) = LocalCatalogIndex.browse("/music/album", "/music", songs, dirs, positions)
        assertEquals(listOf("Showtime", "Personal Lies", "Tentpole Shangrila", "BNBG"), here.map { it.title })
    }

    @Test fun untaggedTracksUseFilenamesAndAlbumsStayTogether() {
        val songs = listOf(
            song("a", "Zulu", album = "B", file = "01 zulu.mp3"),
            song("b", "Alpha", album = "B", file = "02 alpha.mp3"),
            song("c", "Second", album = "A"),
            song("d", "First", album = "A"),
        )
        val dirs = songs.associate { it.id to "/music/album" }
        val (_, here) = LocalCatalogIndex.browse("/music/album", "/music", songs, dirs, mapOf("c" to 2, "d" to 1))
        assertEquals(listOf("First", "Second", "Zulu", "Alpha"), here.map { it.title })
    }
}
