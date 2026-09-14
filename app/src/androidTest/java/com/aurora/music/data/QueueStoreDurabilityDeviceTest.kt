package com.aurora.music.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class QueueStoreDurabilityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun saved(id: String) = SavedQueue(listOf(SavedTrack(id = id, streamUrl = "file:///fixture.wav")), positionSec = 17)

    @Test fun earlierOrdinaryFlushCannotFinishAfterAndOverwriteAnAwaitedRestore() = fixture { isolated ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockNext = AtomicBoolean(false)
        val store = QueueStore(isolated) { file, bytes ->
            if (blockNext.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            persistBackupFileAtomically(file, bytes)
        }
        runBlocking { store.restoreAccount("other", saved("untouched")) }
        blockNext.set(true)
        store.save("account", saved("temporary"))
        store.requestFlush()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val restore = worker.submit { runBlocking { store.restoreAccount("account", saved("original")) } }
            assertThrows(TimeoutException::class.java) { restore.get(150, TimeUnit.MILLISECONDS) }
            release.countDown()
            restore.get(5, TimeUnit.SECONDS)
            val reloaded = QueueStore(isolated)
            assertEquals(saved("original"), reloaded.get("account"))
            assertEquals(saved("untouched"), reloaded.get("other"))
            assertEquals(saved("original"), store.get("account"))
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun removingAnOriginallyAbsentAccountIsDurableAndKeepsOtherAccounts() = fixture { isolated ->
        val store = QueueStore(isolated)
        runBlocking {
            store.restoreAccount("other", saved("keep"))
            store.restoreAccount("temporary", saved("fixture"))
            store.restoreAccount("temporary", null)
        }
        assertNull(store.get("temporary"))
        val reloaded = QueueStore(isolated)
        assertNull(reloaded.get("temporary")); assertEquals(saved("keep"), reloaded.get("other"))
    }

    @Test fun failedDurableRestoreDoesNotPublishOrEraseTheOriginalQueue() = fixture { isolated ->
        val fail = AtomicBoolean(false)
        val store = QueueStore(isolated) { file, bytes ->
            if (fail.get()) throw IOException("Fixture write failure")
            persistBackupFileAtomically(file, bytes)
        }
        runBlocking { store.restoreAccount("account", saved("original")) }
        fail.set(true)
        assertThrows(IOException::class.java) { runBlocking { store.restoreAccount("account", saved("replacement")) } }
        assertEquals(saved("original"), store.get("account"))
        assertEquals(saved("original"), QueueStore(isolated).get("account"))
    }

    private fun fixture(block: (ContextWrapper) -> Unit) {
        val root = File(context.cacheDir, "queue-durability-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = root }
        try { block(isolated) } finally { root.deleteRecursively() }
    }
}
