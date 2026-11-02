package com.aurora.music.data

import com.aurora.music.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrackChoicesTest {
    private val root: File = Files.createTempDirectory("aurora-choices").toFile()

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun song(id: String) = Song(id, "Title $id", "Artist", "", "", 200)

    @Test fun onlyTracksPlayingAYouTubeSearchCanBeRematched() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", TrackChoices.matchKey("aurora-yt://4uLU6hMCjMI75M1A2tKUQC?q=Never%20Gonna&dur=213"))
        assertEquals("sc12345", TrackChoices.matchKey("aurora-yt://sc12345?q=Song&dur=180"))
        assertNull(TrackChoices.matchKey("aurora-yt://video/dQw4w9WgXcQ"))
        assertNull(TrackChoices.matchKey("file:///music/song.flac"))
        assertNull(TrackChoices.matchKey("aurora-sc://track/1"))
    }

    @Test fun hiddenSongsStayOutOfPlaybackUnlessPicked() {
        val choices = TrackChoices(root) { _, _ -> }
        val songs = listOf(song("a"), song("b"), song("c"))
        choices.setHidden("playlist", "mix", "b", true)
        assertEquals(listOf("a", "c"), choices.visible("playlist", "mix", songs).map { it.id })
        assertEquals(listOf("a", "b", "c"), choices.visible("playlist", "mix", songs, picked = songs[1]).map { it.id })
        assertEquals(listOf("a", "b", "c"), choices.visible("playlist", "other", songs).map { it.id })
        // a merged library prefixes ids, and the choice still applies there
        val merged = "s" + "0".repeat(64) + MERGE_NAMESPACE_SEP
        assertEquals(listOf("${merged}a"), choices.visible("playlist", "${merged}mix", listOf(song("${merged}a"), song("${merged}b"))).map { it.id })
        choices.setHidden("playlist", "mix", "b", false)
        assertEquals(3, choices.visible("playlist", "mix", songs).size)
    }

    @Test fun choicesSurviveARestart() {
        val written = CountDownLatch(2)
        val choices = TrackChoices(root) { file, bytes -> file.writeBytes(bytes); written.countDown() }
        choices.setMatch("4uLU6hMCjMI75M1A2tKUQC", MatchChoice("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213))
        choices.setHidden("playlist", "mix", "b", true)
        assertTrue(written.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        val reopened = TrackChoices(root)
        assertEquals("dQw4w9WgXcQ", reopened.matchFor("4uLU6hMCjMI75M1A2tKUQC")?.videoId)
        assertTrue(reopened.isHidden("playlist", "mix", "b"))
    }
}
