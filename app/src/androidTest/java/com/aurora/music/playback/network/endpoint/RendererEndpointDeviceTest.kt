package com.aurora.music.playback.network.endpoint

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class RendererEndpointDeviceTest {
    @Test fun pinnedPairingAuthenticatedTransportRevocationAndRendererRestart() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "renderer-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = directory
        }
        var state = RendererStatus()
        var executed = 0
        var sourcePeer: String? = null
        val stalledRequest = CountDownLatch(1)
        val releaseStalledRequest = CountDownLatch(1)
        val stalledRequestFinished = CountDownLatch(1)
        val playback = object : RendererPlayback {
            override fun status(): RendererStatus = state
            override fun execute(command: RendererCommand): RendererStatus {
                executed++
                if (command is RendererCommand.Route && command.id == "stalled") {
                    stalledRequest.countDown()
                    try { releaseStalledRequest.await(10, TimeUnit.SECONDS) }
                    finally { stalledRequestFinished.countDown() }
                    return state
                }
                state = when (command) {
                    is RendererCommand.Queue -> {
                        sourcePeer = command.sourceHost
                        state.copy(playing = command.playWhenReady, playWhenReady = command.playWhenReady,
                            queue = command.tracks.map { EndpointQueueItem(it.id, it.title, it.artist, it.durationMs) },
                            queueIndex = command.startIndex, positionMs = command.positionMs)
                    }
                    is RendererCommand.Playing -> state.copy(playing = command.value, playWhenReady = command.value)
                    is RendererCommand.Seek -> state.copy(positionMs = command.positionMs)
                    is RendererCommand.Volume -> state.copy(volume = command.value)
                    is RendererCommand.Route -> state.copy(route = EndpointRoute(command.id, "Test DAC"))
                    RendererCommand.Stop -> state.copy(playing = false, playWhenReady = false)
                    else -> state
                }
                return state
            }
        }
        var endpoint = RendererEndpoint(context, playback, "Test renderer")
        try {
            val address = endpoint.start(advertisedHost = "127.0.0.1")
            RendererClient(address).use { unauthenticated ->
                assertEquals("unauthorized", assertThrows(RendererException::class.java) { unauthenticated.status() }.code)
            }
            val window = endpoint.beginPairing()
            val badFingerprint = if (address.fingerprint == "0".repeat(64)) "1".repeat(64) else "0".repeat(64)
            RendererClient(address.copy(fingerprint = badFingerprint)).use { wrongPin ->
                assertThrows(RendererException::class.java) { wrongPin.pair("Phone", window.code) }
            }
            assertTrue(endpoint.clients.value.isEmpty())
            val paired = RendererClient(address).use { it.pair("Phone", window.code) }
            RendererTrustStore(context).saveRenderer(paired)
            assertEquals(paired, RendererTrustStore(context).renderers().single())
            assertNotEquals(window.code, paired.token)
            RendererClient(paired).use { client ->
                val queue = RendererCommand.Queue(listOf(EndpointTrack("track", "Test audio", "Artist",
                    durationMs = 10_000, sourceUrl = "http://127.0.0.1/media/${"a".repeat(43)}")))
                assertTrue(client.command(queue, "queue-request-1").playing)
                client.command(queue, "queue-request-1")
                assertEquals(1, executed)
                assertEquals("127.0.0.1", sourcePeer)
                assertEquals(2_000L, client.command(RendererCommand.Seek(2_000)).positionMs)
                assertFalse(client.command(RendererCommand.Playing(false)).playing)
                assertEquals(0.4f, client.command(RendererCommand.Volume(0.4f)).volume, 0.001f)
                assertEquals("usb", client.command(RendererCommand.Route("usb")).route.id)
                client.command(RendererCommand.Playing(true))
                runBlocking {
                    val pending = launch(Dispatchers.IO) { client.commandCancellable(RendererCommand.Route("stalled")) }
                    try {
                        assertTrue(stalledRequest.await(5, TimeUnit.SECONDS))
                        withTimeout(2_000) { pending.cancelAndJoin() }
                    } finally { releaseStalledRequest.countDown() }
                    assertTrue(stalledRequestFinished.await(5, TimeUnit.SECONDS))
                    assertTrue(client.statusCancellable().playing)
                    assertFalse(client.commandCancellable(RendererCommand.Playing(false)).playing)
                    client.commandCancellable(RendererCommand.Playing(true))
                }
            }
            assertTrue(state.playing)
            val oldFingerprint = address.fingerprint
            endpoint.stop()
            endpoint = RendererEndpoint(context, playback, "Test renderer")
            val restarted = endpoint.start(advertisedHost = "127.0.0.1")
            assertEquals(oldFingerprint, restarted.fingerprint)
            val reconnected = paired.copy(address = restarted.address)
            RendererClient(reconnected).use { client ->
                assertTrue(client.status().playing)
                endpoint.revokeClient(paired.clientId)
                assertEquals("unauthorized", assertThrows(RendererException::class.java) { client.status() }.code)
            }
            assertTrue(endpoint.clients.value.isEmpty())
        } finally {
            releaseStalledRequest.countDown()
            endpoint.stop()
            directory.deleteRecursively()
        }
    }
}
