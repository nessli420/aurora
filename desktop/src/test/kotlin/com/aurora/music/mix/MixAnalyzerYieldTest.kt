package com.aurora.music.mix

import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.wavBytes
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MixAnalyzerYieldTest {
    @get:Rule val temp = TemporaryFolder()

    private fun song(id: String): Song {
        val rate = 22050
        val file = temp.newFile("$id.wav").apply {
            writeBytes(wavBytes(rate, 1, 16, rate * 20) { frame, _ -> (kotlin.math.sin(2 * Math.PI * 440 * frame / rate) * 8000).toInt() })
        }
        return Song(id, "Song $id", "Artist", "Album", "", 20, streamUrl = desktopFileUri(file.path))
    }

    @Test(timeout = 60_000) fun scanTrackGivesWayToAUserAnalysisThenRetries() = runBlocking {
        val scanSong = song("scan")
        val userSong = song("user")
        val gate = CountDownLatch(1)
        val scanResolves = AtomicInteger()
        val analyzer = MixAnalyzer(temp.root, { s ->
            if (s.id == "scan" && scanResolves.incrementAndGet() == 1) gate.await()
            ResolvedStream(s.streamUrl)
        })
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val scan = async(Dispatchers.Default) { analyzer.analyze("acct", scanSong, worker) {} }
            while (scanResolves.get() == 0) delay(10)
            val user = async(Dispatchers.Default) { analyzer.analyze("acct", userSong) {} }
            delay(300)
            gate.countDown()
            assertTrue(user.await().seconds > 1f)
            assertTrue(scan.await().seconds > 1f)
            assertEquals(2, scanResolves.get())
        } finally { gate.countDown(); worker.close() }
    }
}
