package com.aurora.music.data

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DownloadIsolationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun queuedDownloadKeepsItsSourceAndUntrustedIdsStayInsideDownloads() = runBlocking {
        val id = "../edge-${UUID.randomUUID()}"
        val server = AtomicReference("server-a")
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val bytes = byteArrayOf(1, 2, 3, 4)
        val manager = DownloadManager(context, currentServerIdProvider = { server.get() },
            copyCached = { _, file, progress ->
                started.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                file.writeBytes(bytes)
                progress(1f)
            })
        try {
            manager.downloadSong(Song(id, "Edge", "Artist", "Album", "", 10, streamUrl = "aurora-cache://edge"))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            server.set("server-b")
            proceed.countDown()
            withTimeout(5_000) { manager.states.first { it[id] == DownloadState.Done } }
            val entry = requireNotNull(manager.get(id))
            assertEquals("server-a", entry.serverId)
            assertTrue(File(entry.audioPath).canonicalPath.startsWith(File(context.filesDir, "downloads").canonicalPath + File.separator))
            assertArrayEquals(bytes, File(entry.audioPath).readBytes())
        } finally {
            proceed.countDown()
            withTimeoutOrNull(5_000) { manager.states.first { it[id] == DownloadState.Done || it[id] == DownloadState.Failed } }
            manager.removeDownload(id)
        }
    }

    @Test fun collectionCoverUsesAContainedFileName() = runBlocking {
        val id = "../collection-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "cover-${UUID.randomUUID()}.jpg")
        source.writeBytes(byteArrayOf(5, 6, 7))
        val manager = DownloadManager(context, currentServerIdProvider = { "server-a" })
        try {
            manager.downloadCollection(id, "album", "Edge", "Artist", Uri.fromFile(source).toString(), emptyList())
            val entry = withTimeout(5_000) { manager.collections.first { list -> list.any { it.id == id } }.first { it.id == id } }
            assertTrue(File(entry.coverPath).canonicalPath.startsWith(File(context.filesDir, "downloads").canonicalPath + File.separator))
            assertArrayEquals(source.readBytes(), File(entry.coverPath).readBytes())
        } finally {
            manager.removeCollection(id)
            source.delete()
        }
    }
}
