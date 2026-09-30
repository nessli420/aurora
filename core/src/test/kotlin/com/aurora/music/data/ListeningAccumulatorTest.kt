package com.aurora.music.data

import com.aurora.music.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ListeningAccumulatorTest {
    private val root: File = Files.createTempDirectory("aurora-listening").toFile()
    private val store = PlayHistoryStore(root)
    private val accumulator = ListeningAccumulator(store).apply { allowed = true }
    private var now = 0L

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun song(id: String) = Song(id, id, "Artist", "Album", "", 200)

    private fun play(song: Song?, fromMs: Long, seconds: Int, playing: Boolean = true) {
        repeat(seconds + 1) { second ->
            accumulator.track(song, playing, fromMs + second * 1_000L, now)
            now += 1_000
        }
    }

    @Test fun accumulatesInFiveSecondChunksAndFlushesOnFinish() {
        play(song("a"), 0, 7)
        assertEquals(5_000L, store.history.value.single().listenedMs)
        accumulator.finish()
        assertEquals(7_000L, store.history.value.single().listenedMs)
    }

    @Test fun missingPlayerOnlyAdvancesTheClock() {
        play(song("a"), 0, 3)
        play(null, 0, 10)
        play(song("a"), 3_000, 2)
        accumulator.finish()
        assertEquals("a", accumulator.song?.id)
        assertEquals(6_000L, store.history.value.single().listenedMs)
    }

    @Test fun excludedSourcesAndPrivateSessionsAreNotRecorded() {
        play(song("podcast:episode"), 0, 8)
        accumulator.allowed = false
        play(song("b"), 0, 8)
        accumulator.finish()
        assertTrue(store.history.value.isEmpty())
    }
}
