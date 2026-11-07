package com.aurora.music.data

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadedLibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun entry(id: String, albumId: String, dir: File) = DownloadedSong(
        id, "Song $id", "Artist", if (albumId.isBlank()) "" else "Album $albumId", albumId, "artist", 120,
        File(dir, "$id.audio").apply { writeText("audio $id") }.absolutePath, "", serverId = "http://server")

    @Test fun singleTracksAreListedAndTheirAlbumRowOpensOnlyDownloadedTracks() = runBlocking {
        val dir = folder.newFolder("downloads")
        File(dir, "index.json").writeText(Gson().toJson(listOf(
            entry("a", "album-1", dir), entry("b", "album-1", dir), entry("c", "", dir))))
        val repository = MusicRepository({ null }, DownloadManager(dir, { "file://$it" }, { null }), offlineProvider = { true })

        val rows = repository.downloadedLibrary()
        assertEquals(listOf("downloaded" to "songs", DOWNLOADED_ALBUM to "album-1"), rows.map { it.kind to it.id })
        assertEquals("3 tracks", rows.first().subtitle)
        assertEquals(listOf("Song a", "Song b", "Song c"), repository.detail("downloaded", "songs")!!.tracks.map { it.title }.sorted())

        val album = repository.detail(DOWNLOADED_ALBUM, "album-1")!!
        assertEquals("Album album-1", album.info.title)
        assertEquals(listOf("Song a", "Song b"), album.tracks.map { it.title }.sorted())
    }
}
