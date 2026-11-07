package com.aurora.music.data

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineSongsMemoTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aChangedDownloadIsSeenByTheNextRead() = runBlocking {
        val dir = folder.newFolder("downloads")
        val server = File(dir, "server").apply { mkdirs() }
        fun entry(id: String) = DownloadedSong(id, "Song $id", "Artist", "", "", "artist-$id", 120,
            File(server, "$id.audio").apply { writeText("audio") }.absolutePath, File(server, "$id.jpg").apply { writeText("cover") }.absolutePath,
            serverId = "http://server", playbackSource = PlaybackSourceIdentity(providerId = "provider:nd", providerLabel = "Navidrome · server"))
        File(dir, "index.json").writeText(Gson().toJson(listOf(entry("b"), entry("a"))))
        val downloads = DownloadManager(dir, { "file://$it" }, { null })
        val repository = MusicRepository({ null }, downloads, offlineProvider = { true })

        assertEquals(listOf("Song a", "Song b"), repository.allSongs().map { it.title })
        assertEquals(0, repository.starredCount())
        downloads.markLiked(listOf("a"), true)
        assertEquals(1, repository.starredCount())
    }
}
