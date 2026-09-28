package com.aurora.music.playback.network.endpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RendererProtocolTest {
    private val token = "a".repeat(43)

    @Test fun controllerCommandsRoundTripWithoutChangingQueueSemantics() {
        val commands = listOf(RendererCommand.Queue(listOf(EndpointTrack("one", "Track", sourceUrl = "http://192.168.1.2:8080/media/$token")),
            playWhenReady = false, positionMs = 1_234, repeatMode = 2), RendererCommand.Playing(true), RendererCommand.Seek(9_876, 0),
            RendererCommand.Volume(0.25f), RendererCommand.Route("usb"), RendererCommand.Repeat(1),
            RendererCommand.Reorder(listOf("two", "one")), RendererCommand.Reorder(emptyList()),
            RendererCommand.Next, RendererCommand.Previous, RendererCommand.Stop)
        commands.forEach { assertEquals(it, RendererJson.command(RendererJson.encode(it))) }
    }

    @Test fun rawBackendCredentialsAndArbitraryPathsNeverReachRenderer() {
        listOf("http://user:password@192.168.1.2/media/$token", "http://192.168.1.2/media/$token?api_key=secret",
            "http://192.168.1.2/rest/stream.view?id=1&u=user&t=secret", "file:///private/source.flac",
            "http://192.168.1.2/media/$token#secret", "http://192.168.1.2/media/%2e%2e/private").forEach {
            assertThrows(RendererException::class.java) { RendererJson.validateSource(it) }
        }
        RendererJson.validateSource("https://192.168.1.2:8090/media/$token")
    }

    @Test fun invalidVolumesQueueIndicesAndProtocolVersionsAreRejected() {
        listOf(-0.1f, 1.1f, Float.NaN).forEach { volume ->
            val value = com.google.gson.JsonObject().apply {
                addProperty("version", 1); addProperty("type", "volume"); addProperty("value", volume)
            }
            assertThrows(RendererException::class.java) { RendererJson.command(value) }
        }
        val queue = RendererJson.encode(RendererCommand.Queue(listOf(EndpointTrack("one", "Track", sourceUrl = "http://localhost/media/$token")), startIndex = 1))
        assertThrows(RendererException::class.java) { RendererJson.command(queue) }
        val playing = RendererJson.encode(RendererCommand.Playing(true)).apply { addProperty("version", 2) }
        assertEquals("unsupported_version", assertThrows(RendererException::class.java) { RendererJson.command(playing) }.code)
    }

    @Test fun pairingAddressRequiresTrustedFullFingerprintAndHttps() {
        val fingerprint = "af".repeat(32)
        assertEquals(EndpointAddress("https://192.168.1.2:8090", fingerprint), EndpointAddress.parse("https://192.168.1.2:8090#$fingerprint"))
        listOf("http://192.168.1.2#$fingerprint", "https://192.168.1.2", "https://user@192.168.1.2#$fingerprint",
            "https://192.168.1.2/path#$fingerprint", "https://192.168.1.2#short").forEach {
            assertThrows(RendererException::class.java) { EndpointAddress.parse(it) }
        }
    }

    @Test fun replayGainMetadataIsOptionalBoundedAndPreserved() {
        val track = EndpointTrack("one", "Track", sourceUrl = "http://localhost/media/$token", rgTrack = -6f, rgAlbum = -3f)
        val encoded = RendererJson.encode(RendererCommand.Queue(listOf(track)))
        assertEquals(track, (RendererJson.command(encoded) as RendererCommand.Queue).tracks.single())
        val value = encoded.getAsJsonArray("tracks").first().asJsonObject
        value.remove("rgTrack"); value.remove("rgAlbum")
        assertEquals(0f, (RendererJson.command(encoded) as RendererCommand.Queue).tracks.single().rgTrack, 0f)
        listOf(Float.NaN, -61f, 31f).forEach { gain ->
            value.addProperty("rgTrack", gain)
            assertThrows(RendererException::class.java) { RendererJson.command(encoded) }
        }
    }

    @Test fun queueOccurrencesMustBeUniqueAndRepeatRemainsCompatibleWithOlderControllers() {
        val track = EndpointTrack("one", "Track", sourceUrl = "http://localhost/media/$token")
        val encoded = RendererJson.encode(RendererCommand.Queue(listOf(track), repeatMode = 2))
        encoded.remove("repeatMode")
        assertEquals(0, (RendererJson.command(encoded) as RendererCommand.Queue).repeatMode)
        listOf(listOf(track, track), listOf(track.copy(id = "")), List(101) { track.copy(id = "item-$it") }).forEach { tracks ->
            assertThrows(RendererException::class.java) { RendererJson.command(RendererJson.encode(RendererCommand.Queue(tracks))) }
        }
        assertThrows(RendererException::class.java) {
            RendererJson.command(RendererJson.encode(RendererCommand.Reorder(listOf("one", "one"))))
        }
        listOf(-1, 3).forEach { mode ->
            assertThrows(RendererException::class.java) { RendererJson.command(RendererJson.encode(RendererCommand.Repeat(mode))) }
        }
    }

    @Test fun fractionalOverflowAndStringNumbersCannotChangeQueuePositions() {
        listOf("1.5", "9223372036854775808", "\"2\"", "null", "true").forEach { value ->
            val json = RendererJson.parse("""{"version":1,"type":"seek","positionMs":$value}""".toByteArray())
            assertThrows(RendererException::class.java) { RendererJson.command(json) }
        }
        listOf("1.5", "4294967296", "\"1\"").forEach { value ->
            val json = RendererJson.parse("""{"version":1,"type":"repeat","repeatMode":$value}""".toByteArray())
            assertThrows(RendererException::class.java) { RendererJson.command(json) }
        }
    }
}
