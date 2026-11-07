package com.aurora.music.data

import com.aurora.music.data.remote.ArtistInfo
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeferredStoreLoadTest {
    private val root: File = Files.createTempDirectory("aurora-deferred").toFile()

    @After fun tearDown() {
        root.deleteRecursively()
    }

    // only metadata may be polled, an open read handle fails the atomic replace on windows
    private fun waitFor(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(check())
    }

    @Test fun aQueueSaveKeepsTheOtherAccountsInTheUnreadFile() {
        File(root, "queue_state.json").writeText("""{"bob":{"tracks":[{"id":"b","streamUrl":"u"}]}}""")
        val store = QueueStore(root)
        store.save("alice", SavedQueue(listOf(SavedTrack(id = "a", streamUrl = "u"))))
        store.flushNow()
        val reloaded = QueueStore(root)
        assertEquals("a", reloaded.get("alice")?.tracks?.single()?.id)
        assertEquals("b", reloaded.get("bob")?.tracks?.single()?.id)
    }

    @Test fun aReplayGainMergeKeepsUnreadGainsAndClearEmptiesTheFile() {
        File(root, "rg_gains.json").writeText("""{"/old":{"track":1.0,"album":2.0}}""")
        ReplayGainStore(root).putAll(mapOf("/new" to RgEntry(3f, 4f)))
        val reloaded = ReplayGainStore(root)
        assertEquals(RgEntry(1f, 2f), reloaded.get("/old"))
        assertEquals(RgEntry(3f, 4f), reloaded.get("/new"))
        reloaded.clear()
        assertEquals(0, reloaded.size)
        assertEquals(0, ReplayGainStore(root).size)
        assertEquals(listOf("rg_gains.json"), root.list()!!.toList())
    }

    @Test fun downloadFlowsStayOneInstanceAndAWriteKeepsTheUnreadIndex() {
        val dir = File(root, "downloads").apply { mkdirs() }
        val folder = File(dir, "server").apply { mkdirs() }
        fun entry(id: String) = DownloadedSong(id, id, "", "", "", "", 1, File(folder, id).apply { writeText("x") }.absolutePath, "")
        val index = File(dir, "index.json").apply { writeText(Gson().toJson(listOf(entry("a"), entry("b")))) }
        val length = index.length()
        val manager = DownloadManager(dir, { "file://$it" }, { null })
        assertSame(manager.downloads, manager.downloads)
        assertSame(manager.collections, manager.collections)
        manager.removeDownload("a")
        waitFor { index.length() < length }
        assertEquals(listOf("b"), Gson().fromJson(index.readText(), Array<DownloadedSong>::class.java).map { it.id })
    }

    @Test fun artistPutKeepsUnreadEntriesAndDropsExpiredOnes() {
        var now = 0L
        val first = ArtistInfoStore(root) { now }
        first.put("kept", ArtistInfo(name = "kept", found = true))
        first.put("miss", ArtistInfo(name = "miss", found = false))
        now = 4L * 24 * 60 * 60 * 1000
        val reopened = ArtistInfoStore(root) { now }
        reopened.put("new", ArtistInfo(name = "new", found = true))
        assertEquals(2, ArtistInfoStore(root) { now }.size)
        assertEquals("kept", reopened.get("kept")?.name)
        reopened.clear()
        assertEquals(0, reopened.size)
        assertEquals(0, ArtistInfoStore(root) { now }.size)
    }

    @Test fun localEditsReachDiskAndAQueuedWriteCannotUndoABackupRestore() = runBlocking {
        val store = LocalStore(root)
        store.setLiked("a", true)
        waitFor { File(root, "local_store.json").exists() }
        assertEquals(setOf("a"), LocalStore(root).likedIds())
        store.setLiked("b", true)
        store.restoreBackupJson("""{"likedIds":["restored"]}""")
        Thread.sleep(100)
        assertEquals(setOf("restored"), LocalStore(root).likedIds())
    }

    @Test fun historySharesRepeatedStringsAndKeepsEventsWithMissingFields() {
        fun event(timestamp: Int) = """{"songId":"a","title":"Same","artist":"Artist","album":"Album","artistId":"x","artworkUrl":"u","durationSec":1,"timestamp":$timestamp}"""
        File(root, "play_history.json").writeText("[${event(1)},${event(2)}]")
        val events = PlayHistoryStore(root).snapshot()
        assertEquals(listOf(1L, 2L), events.map { it.timestamp })
        assertNull(events[0].albumId)
        assertSame(events[0].title, events[1].title)
    }
}
