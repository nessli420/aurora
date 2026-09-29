package com.aurora.music.desktop.player

import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.desktop.audio.EnginePhase
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.desktop.audio.QueueEntry
import com.aurora.music.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ListeningHistoryTest {
    private val root: File = Files.createTempDirectory("aurora-history").toFile()
    private val store = PlayHistoryStore(root)
    private val history = ListeningHistory(store).apply { allowed = true }
    private var now = 0L

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun play(song: Song, fromMs: Long, seconds: Int, playing: Boolean = true) {
        val entry = QueueEntry(1, song)
        repeat(seconds + 1) { second ->
            history.track(EngineState(listOf(entry), 0, fromMs + second * 1_000L, 200_000, playing, EnginePhase.READY), now)
            now += 1_000
        }
    }

    @Test fun recordsListeningTimeInFiveSecondChunks() {
        play(song("a"), 0, 7)
        val event = store.history.value.single()
        assertEquals("a", event.songId)
        assertEquals(5_000L, event.listenedMs)
        history.finish()
        assertEquals(7_000L, store.history.value.single().listenedMs)
    }

    @Test fun restartingTheTrackStartsANewListen() {
        play(song("a"), 0, 6)
        play(song("a"), 200, 6)
        assertEquals(listOf(5_000L, 6_000L), store.history.value.map { it.listenedMs })
    }

    @Test fun pausedPrivateAndRadioPlaybackIsNotRecorded() {
        play(song("a"), 0, 8, playing = false)
        play(song("radio:station"), 0, 8)
        history.allowed = false
        play(song("b"), 0, 8)
        history.finish()
        assertTrue(store.history.value.isEmpty())
    }
}
