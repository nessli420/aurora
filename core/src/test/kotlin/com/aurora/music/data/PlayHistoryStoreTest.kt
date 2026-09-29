package com.aurora.music.data

import com.aurora.music.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlayHistoryStoreTest {
    private val root: File = Files.createTempDirectory("aurora-history").toFile()
    private val file = File(root, "play_history.json")

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun song(id: String) = Song(id, "Title $id", "Artist", "Album", "", 200)

    @Test fun flushNowPersistsListeningTimeTheSaveThrottleHeldBack() {
        val store = PlayHistoryStore(root)
        val start = System.currentTimeMillis()
        store.recordListening(song("a"), 5_000, start)
        store.recordListening(song("a"), 4_000, start + 5_000)
        store.flushNow()
        assertEquals(9_000L, PlayHistoryStore(root).snapshot().single().listenedMs)
    }

    @Test fun flushNowIsIdempotentAndQueuedSavesDoNotRewriteFlushedState() {
        val store = PlayHistoryStore(root)
        store.record(song("a"), 1_000)
        store.flushNow()
        assertTrue(file.delete())
        store.flushNow()
        Thread.sleep(200)
        assertFalse(file.exists())
        store.record(song("b"), 60_000)
        store.flushNow()
        val reloaded = PlayHistoryStore(root)
        assertEquals(listOf("b", "a"), reloaded.snapshot().map { it.songId })
        assertTrue(file.delete())
        reloaded.flushNow()
        assertFalse(file.exists())
    }

    @Test fun concurrentRecordsAndFlushesLeaveTheLatestHistoryOnDisk() {
        val store = PlayHistoryStore(root)
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { worker ->
            pool.execute {
                repeat(25) { i ->
                    store.record(song("w$worker-$i"), worker * 1_000_000L + i * 20_000L)
                    if (i % 5 == 0) store.flushNow()
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        store.flushNow()
        assertEquals(100, store.snapshot().size)
        assertEquals(store.snapshot(), PlayHistoryStore(root).snapshot())
    }
}
