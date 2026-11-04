package com.aurora.music.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class QueueStoreTest {
    private val root: File = Files.createTempDirectory("aurora-queue").toFile()
    private val file = File(root, "queue_state.json")

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun queue(vararg ids: String, position: Int = 0) =
        SavedQueue(ids.map { SavedTrack(id = it, streamUrl = "https://music.example/$it.flac") }, positionSec = position)

    @Test fun flushNowWritesPendingStateBeforeReturningAndOnlyOnce() {
        val writes = AtomicInteger()
        val store = QueueStore(root) { target, bytes -> writes.incrementAndGet(); persistBackupFileAtomically(target, bytes) }
        store.flushNow()
        assertFalse(file.exists())
        val saved = queue("a", "b", position = 42).copy(shuffle = true, shuffleOrder = listOf("b", "a"))
        store.save("alice", saved)
        store.flushNow()
        assertEquals(1, writes.get())
        assertEquals(saved, QueueStore(root).get("alice"))
        store.flushNow()
        store.requestFlush()
        Thread.sleep(100)
        assertEquals(1, writes.get())
    }

    @Test fun djSpeechIsNeverSaved() {
        val line = "radio:aurora-dj:1"
        val store = QueueStore(root)
        store.save("alice", queue("a", line, "b", position = 42).copy(currentIndex = 2, shuffleOrder = listOf(line, "b", "a")))
        val saved = store.get("alice")!!
        assertEquals(listOf("a", "b"), saved.tracks.orEmpty().map { it.id })
        assertEquals(1, saved.currentIndex)
        assertEquals(42, saved.positionSec)
        assertEquals(listOf("b", "a"), saved.shuffleOrder)
        store.save("alice", queue("a", line, "b", position = 42).copy(currentIndex = 1))
        val onLine = store.get("alice")!!
        assertEquals(1, onLine.currentIndex)
        assertEquals(0, onLine.positionSec)
    }

    @Test fun failedFlushStaysPendingForTheNextOne() {
        val fail = AtomicBoolean(true)
        val store = QueueStore(root) { target, bytes ->
            if (fail.get()) throw IOException("disk full")
            persistBackupFileAtomically(target, bytes)
        }
        store.save("alice", queue("a"))
        store.flushNow()
        assertFalse(file.exists())
        fail.set(false)
        store.flushNow()
        assertEquals(queue("a"), QueueStore(root).get("alice"))
    }

    @Test fun saveDoesNotWaitForASlowWrite() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = QueueStore(root) { target, bytes ->
            writing.countDown()
            release.await()
            persistBackupFileAtomically(target, bytes)
        }
        store.save("alice", queue("a"))
        val flush = Thread { store.flushNow() }.apply { start() }
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        val saved = CountDownLatch(1)
        Thread { store.save("alice", queue("b")); saved.countDown() }.start()
        val returned = saved.await(2, TimeUnit.SECONDS)
        release.countDown()
        flush.join()
        assertTrue("save must not wait for the write in progress", returned)
        store.flushNow()
        assertEquals(queue("b"), QueueStore(root).get("alice"))
    }

    @Test fun queuesSavedBeforeShuffleOrderExistedLoadWithoutOne() {
        file.writeText("""{"alice":{"tracks":[{"id":"a","streamUrl":"u"}],"currentIndex":0,"positionSec":5,"shuffle":true,"repeat":1}}""")
        val saved = QueueStore(root).get("alice")!!
        assertNull(saved.shuffleOrder)
        assertTrue(saved.shuffle)
        assertEquals(5, saved.positionSec)
        assertEquals(listOf("a"), saved.tracks?.map { it.id })
    }

    @Test fun concurrentFlushesNeverOverlapAndLeaveTheLatestStateOnDisk() {
        val active = AtomicInteger()
        val overlapped = AtomicBoolean()
        val store = QueueStore(root) { target, bytes ->
            if (active.incrementAndGet() > 1) overlapped.set(true)
            try { persistBackupFileAtomically(target, bytes) } finally { active.decrementAndGet() }
        }
        val pool = Executors.newFixedThreadPool(4)
        val start = CyclicBarrier(4)
        repeat(4) { worker ->
            pool.execute {
                start.await()
                repeat(25) { i ->
                    store.save("account$worker", queue("t$i", position = i))
                    if (i % 2 == 0) store.flushNow() else store.requestFlush()
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        store.flushNow()
        assertFalse(overlapped.get())
        assertEquals(listOf(file.name), root.list()!!.toList())
        val reloaded = QueueStore(root)
        repeat(4) { assertEquals(queue("t24", position = 24), reloaded.get("account$it")) }
    }
}
