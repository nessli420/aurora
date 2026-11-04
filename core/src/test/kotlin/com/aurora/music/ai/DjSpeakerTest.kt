package com.aurora.music.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class DjSpeakerTest {
    @Test fun languagesItCannotReadFallBack() {
        assertEquals("id", speakerLanguage("in"))
        assertEquals("en", speakerLanguage("en"))
        assertNull(speakerLanguage("zh"))
        assertNull(speakerLanguage("ur"))
    }

    @Test fun textIsTaggedAndCleaned() {
        assertEquals("<en>You got it: chill 90s hip hop.</en>", speakerText("You got it: chill  90s hip hop", "en"))
        assertEquals("<en>It's a song, by AC DC!</en>", speakerText("It’s a song , by AC/DC!😀", "en"))
    }

    @Test fun longLinesSplitOnSentences() {
        assertEquals(listOf("One two.", "Three four. Five."), speakerChunks("One two. Three four. Five.", 18))
        assertEquals(listOf("Short."), speakerChunks("Short.", 300))
        assertTrue(speakerChunks("x".repeat(500), 300).all { it.length <= 300 })
    }

    @Test fun waveIsNormalizedPcm() {
        val wave = ByteBuffer.wrap(waveBytes(floatArrayOf(0f, 0.5f, -0.25f), 44_100)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(56, wave.capacity())
        assertEquals(2, wave.getShort(22).toInt())
        assertEquals(44_100, wave.getInt(24))
        assertEquals(176_400, wave.getInt(28))
        assertEquals((0.89f * 32767).toInt().toShort(), wave.getShort(48))
        assertEquals(wave.getShort(48), wave.getShort(50))
    }

    private fun part(file: String, body: String, url: String) = DjModelPart(file, body.length.toLong(),
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }, url)

    @Test fun downloadsEveryPartAndChecksEach() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("first file"))
        server.enqueue(MockResponse().setBody("second"))
        server.start()
        val dir = Files.createTempDirectory("dj-model").toFile()
        try {
            val spec = DjModelSpec("test", listOf(part("a.bin", "first file", server.url("/a").toString()), part("b.bin", "second", server.url("/b").toString())))
            val store = DjModelStore(dir, OkHttpClient(), spec)
            assertEquals(DjModelState.Missing, store.state.value)
            store.download()
            withTimeout(5_000) { while (store.state.value != DjModelState.Ready) delay(10) }
            assertEquals("a.bin", store.model()?.name)
            assertEquals("second", dir.resolve("b.bin").readText())
            assertEquals(DjModelState.Ready, DjModelStore(dir, OkHttpClient(), spec).state.value)
            dir.resolve("b.bin").delete()
            assertNull(store.model())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test fun aBadPartFailsTheChecksum() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("wrong"))
        server.start()
        val dir = Files.createTempDirectory("dj-model").toFile()
        try {
            val store = DjModelStore(dir, OkHttpClient(), DjModelSpec("test", listOf(part("a.bin", "right", server.url("/a").toString()))))
            store.download()
            withTimeout(5_000) { while (store.state.value !is DjModelState.Failed) delay(10) }
            assertEquals(DjModelState.Failed(DjModelStore.FAIL_CHECKSUM), store.state.value)
            assertNotNull(dir.listFiles())
            assertNull(store.model())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }
}
