package com.aurora.music.playback.network.endpoint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectEndpointTest {
    private val held = HeldCertificate.Builder().ecdsa256().commonName("Aurora test").build()
    private val identity = HandshakeCertificates.Builder().heldCertificate(held).build().sslContext() to EndpointTls.fingerprint(held.certificate)
    private val noAds = object : EndpointAdvertiser {
        override fun start(name: String, id: String, port: Int, connect: Boolean) = Unit
        override fun stop() = Unit
    }

    private class FakeHost : ConnectHost {
        @Volatile var state = ConnectStatus(queue = listOf(ConnectTrack("a", "One"), ConnectTrack("b", "Two")), durationMs = 200_000,
            outputs = listOf(ConnectOption("spk", "Speakers")), presets = listOf(ConnectOption("p1", "Warm")))
        val executed = mutableListOf<ConnectCommand>()
        @Volatile var playable = true
        override fun status() = state
        override fun execute(command: ConnectCommand) {
            if (command is ConnectCommand.Play && !playable) throw RendererException("not_available", "Those songs aren't available on that device.", 409)
            executed += command
            state = when (command) {
                is ConnectCommand.Playing -> state.copy(playing = command.value)
                is ConnectCommand.Jump -> state.copy(index = command.index)
                is ConnectCommand.Volume -> state.copy(volume = command.value)
                is ConnectCommand.Play -> state.copy(queue = command.tracks, index = command.index, playing = command.playing)
                else -> state
            }
        }
        override fun artwork(id: String): ByteArray? = if (id == "a") byteArrayOf(1, 2, 3) else null
    }

    @Test fun pairedControllerDrivesTheHostSessionAndLongPollWakesOnChange() = runBlocking {
        val host = FakeHost()
        val server = EndpointServer("Desk", { identity }, emptyList(), {}, noAds, { "127.0.0.1" }, "windows", connect = host)
        try {
            val address = server.start(port = 0)
            RendererClient(address).use { anonymous ->
                assertEquals("unauthorized", assertThrows(RendererException::class.java) { runBlocking { anonymous.connectStatus() } }.code)
            }
            val port = address.address.substringAfterLast(':').toInt()
            assertEquals(address.fingerprint, EndpointTls.probe("127.0.0.1", port))
            val paired = RendererClient(address).use { it.pair("Phone", server.beginPairing().code) }
            RendererClient(paired).use { client ->
                val (status, tag) = client.connectStatus()
                assertEquals("Desk", status.name)
                assertEquals("windows", status.platform)
                assertEquals(listOf("One", "Two"), status.queue.map { it.title })
                assertEquals("Warm", status.presets.single().name)
                assertTrue(client.connectCommand(ConnectCommand.Playing(true)).first.playing)
                assertEquals(0.3f, client.connectCommand(ConnectCommand.Volume(0.3f)).first.volume, 0.001f)
                val waiting = async(Dispatchers.IO) { client.connectStatus(client.connectStatus().second, 0) }
                delay(400)
                assertFalse(waiting.isCompleted)
                host.execute(ConnectCommand.Jump(1))
                assertEquals(1, withTimeout(3_000) { waiting.await() }.first.index)
                assertArrayEquals(byteArrayOf(1, 2, 3), client.artwork("a"))
                assertEquals(null, client.artwork("missing"))
                assertEquals("receiver_disabled", assertThrows(RendererException::class.java) {
                    client.command(RendererCommand.Playing(false))
                }.code)
                server.connect = null
                assertEquals("connect_disabled", assertThrows(RendererException::class.java) { runBlocking { client.connectStatus() } }.code)
                assertTrue(tag.isNotEmpty())
            }
        } finally { server.stop() }
    }

    @Test fun commandsRoundTripAndRejectOutOfRangeValues() {
        val tracks = listOf(ConnectTrack("x", "X", "Artist", "Album", 120, "al", "ar"))
        listOf(ConnectCommand.Playing(true), ConnectCommand.Seek(5_000), ConnectCommand.Jump(3), ConnectCommand.Shuffle(true),
            ConnectCommand.Repeat(2), ConnectCommand.Volume(0.5f), ConnectCommand.Speed(1.25f), ConnectCommand.Like(true),
            ConnectCommand.Sleep(30, false), ConnectCommand.Output("spk"), ConnectCommand.Preset("p1"),
            ConnectCommand.Play(tracks, 0, 1_000, true), ConnectCommand.Add(tracks, -1), ConnectCommand.Remove(2),
            ConnectCommand.Move(1, 4), ConnectCommand.Next, ConnectCommand.Previous, ConnectCommand.Clear, ConnectCommand.Stop,
        ).forEach { assertEquals(it, ConnectJson.command(ConnectJson.encode(it))) }
        listOf(ConnectCommand.Volume(1.5f), ConnectCommand.Repeat(3), ConnectCommand.Play(tracks, 1, 0, true)).forEach {
            assertThrows(RendererException::class.java) { ConnectJson.command(ConnectJson.encode(it)) }
        }
        val status = ConnectStatus("id", "Desk", "linux", queue = tracks, volume = 0.4f, outputs = listOf(ConnectOption("o", "Out")))
        assertEquals(status, ConnectJson.status(ConnectJson.encode(status)).copy(total = 0))
    }

    @Test fun sessionFollowsTheHostAndSerializesCommands() = runBlocking {
        val host = FakeHost()
        val server = EndpointServer("Desk", { identity }, emptyList(), {}, noAds, { "127.0.0.1" }, "linux", connect = host)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val address = server.start(port = 0)
            val paired = RendererClient(address).use { it.pair("Phone", server.beginPairing().code) }
            val unreachable = paired.copy(address = "https://127.0.0.1:1", addresses = listOf(paired.address))
            ConnectSession(unreachable, scope).use { session ->
                assertEquals("Two", withTimeout(15_000) { session.status.first { it != null } }!!.queue[1].title)
                assertTrue(session.send(ConnectCommand.Playing(true)))
                assertTrue(session.send(ConnectCommand.Jump(1)))
                assertEquals(listOf(ConnectCommand.Playing(true), ConnectCommand.Jump(1)), host.executed)
                host.execute(ConnectCommand.Playing(false))
                assertFalse(withTimeout(5_000) { session.status.first { it?.playing == false } }!!.playing)
                assertTrue(session.connected.value)
                server.connect = null
                assertTrue(withTimeout(10_000) { session.lost.first { it } })
                assertEquals("Remote control is off on that device.", session.error.value)
            }
            server.connect = host
            ConnectSession(paired, scope, lostAfterMs = 1_500).use { session ->
                withTimeout(10_000) { session.connected.first { it } }
                server.stop()
                assertTrue(withTimeout(15_000) { session.lost.first { it } })
                assertFalse(session.connected.value)
                val frozen = session.positionMs()
                delay(300)
                assertEquals(frozen, session.positionMs())
            }
        } finally { scope.cancel(); server.stop() }
    }

    @Test fun handOffSendsTheQueueOnlyWhereItIsNeededAndSessionsRepeat() = runBlocking {
        val host = FakeHost()
        val server = EndpointServer("Phone", { identity }, emptyList(), {}, noAds, { "127.0.0.1" }, "android", connect = host)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val play = ConnectCommand.Play(listOf(ConnectTrack("yt1", "Song")), 0, 5_000, true)
        try {
            val paired = RendererClient(server.start(port = 0)).use { it.pair("Tablet", server.beginPairing().code) }
            ConnectSession(paired, scope).use { session ->
                assertTrue(session.handOff(ConnectSwitch.PAUSE, play))
                assertTrue(session.handOff(ConnectSwitch.KEEP, play))
                assertTrue(session.handOff(ConnectSwitch.MOVE, null))
                assertTrue(host.executed.isEmpty())
                assertEquals("Phone", session.name)
            }
            host.state = host.state.copy(queue = emptyList())
            host.playable = false
            ConnectSession(paired, scope).use { session ->
                assertFalse(session.handOff(ConnectSwitch.PAUSE, play))
                assertEquals("not_available", session.errorCode)
                assertTrue(session.status.value!!.queue.isEmpty())
            }
            host.playable = true
            repeat(2) {
                host.state = host.state.copy(queue = emptyList(), index = 0)
                ConnectSession(paired, scope).use { session ->
                    assertTrue(session.handOff(ConnectSwitch.PAUSE, play))
                    assertEquals("yt1", session.status.value!!.current!!.id)
                    assertEquals(null, session.errorCode)
                }
            }
            ConnectSession(paired, scope).use { session -> assertTrue(session.handOff(ConnectSwitch.MOVE, play)) }
            assertEquals(3, host.executed.count { it == play })
            ConnectSession(paired.copy(address = "https://127.0.0.1:1", addresses = emptyList()), scope).use { session ->
                assertFalse(session.send(ConnectCommand.Playing(true)))
                assertEquals("renderer_unreachable", session.errorCode)
            }
        } finally { scope.cancel(); server.stop() }
    }

    @Test fun mergedIdsSurviveAndDisplayTextIsCleaned() {
        val merged = ConnectTrack("nav\u0001song-1", "Line\nbreak", "A\u0000B", "", 60, "nav\u0001album", "nav\u0001artist")
        val parsed = (ConnectJson.command(ConnectJson.encode(ConnectCommand.Play(listOf(merged), 0, 0, true))) as ConnectCommand.Play).tracks.single()
        assertEquals(merged.copy(title = "Linebreak", artist = "AB"), parsed)
        assertEquals(merged.id, ConnectJson.status(ConnectJson.encode(ConnectStatus(queue = listOf(merged)))).queue.single().id)
    }

    @Test fun typedAddressesDefaultToTheConnectPort() {
        assertEquals("100.101.102.103" to CONNECT_PORT, ConnectPairing.hostAndPort(" 100.101.102.103 "))
        assertEquals("desk.tail.ts.net" to 9000, ConnectPairing.hostAndPort("https://desk.tail.ts.net:9000/"))
        assertEquals("fd7a::1" to 47801, ConnectPairing.hostAndPort("[fd7a::1]:47801"))
        listOf("", "bad host", "host:0", "user@host").forEach {
            assertThrows(RendererException::class.java) { ConnectPairing.hostAndPort(it) }
        }
    }

    @Test fun longQueuesAreWindowedAroundTheCurrentTrack() {
        val full = (0 until 1_000).map { ConnectTrack("$it", "T$it") }
        val status = ConnectStatus(index = 700).windowed(full)
        assertEquals(CONNECT_QUEUE_WINDOW, status.queue.size)
        assertEquals("700", status.current?.id)
        assertEquals(1_000, status.total)
    }
}
