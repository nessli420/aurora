package com.aurora.music.playback.network

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.Collections

class DlnaClientTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun descriptorKeepsServicesWithTheRendererNotAnEmbeddedDevice() {
        val parsed = DlnaClient.parseDescription("http://127.0.0.1:1234/device.xml", descriptor())
        assertEquals("uuid:aurora-test", parsed.id)
        assertEquals("Living room & speaker", parsed.name)
        assertEquals("http://127.0.0.1:1234/transport", parsed.avTransport.controlUrl)
        assertNotNull(parsed.connectionManager)
        assertNotNull(parsed.renderingControl)
    }

    @Test fun externalEntitiesAndOffDeviceControlUrlsAreRejected() {
        val document = descriptor()
        listOf(
            "<!DOCTYPE root [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]>$document",
            document.replace("/transport", "http://example.com/control"),
            document.replace("/transport", "http://user:password@127.0.0.1/control"),
            document.replace("/transport", "/control?api_key=secret"),
            document.replace("/transport", "http://localhost/control"),
        ).forEach { xml ->
            assertThrows(DlnaException::class.java) { DlnaClient.parseDescription("http://127.0.0.1/device.xml", xml) }
        }
    }

    @Test fun protocolNegotiationHonorsMimeAndPcmRateConstraints() {
        val supported = DlnaClient.parseProtocols("http-get:*:audio/wav:*,http-get:*:audio/L16;rate=44100;channels=2:*,rtsp-rtp-udp:*:audio/mpeg:*")
        assertTrue(supported[0].accepts("audio/wav"))
        assertFalse(supported[0].accepts("audio/aac"))
        assertTrue(supported[1].accepts("audio/L16;rate=44100;channels=2"))
        assertFalse(supported[1].accepts("audio/L16;rate=48000;channels=2"))
        assertFalse(supported[1].accepts("audio/L16"))
        assertFalse(supported[2].accepts("audio/mpeg"))
        assertTrue(DlnaClient.parseProtocols("http-get:*:*:*").single().accepts("audio/wav"))
        assertTrue(DlnaClient.parseProtocols("garbage").isEmpty())
    }

    @Test fun soapNegotiatesControlsAndReadsPositionAgainstLoopbackReceiver() {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
        BoundedHttpServer(handler = { request ->
            requests.add(request)
            if (request.path == "/device.xml") HttpResponse(200, body = descriptor().toByteArray())
            else {
                val action = request.headers["soapaction"].orEmpty().substringAfter('#').trimEnd('"')
                val payload = when (action) {
                    "GetProtocolInfo" -> "<Sink>http-get:*:audio/wav:*</Sink>"
                    "GetTransportInfo" -> "<CurrentTransportState>PLAYING</CurrentTransportState>"
                    "GetPositionInfo" -> "<RelTime>00:01:12.500</RelTime><TrackDuration>00:04:03</TrackDuration>"
                    "GetVolume" -> "<CurrentVolume>37</CurrentVolume>"
                    else -> ""
                }
                HttpResponse(200, body = soapResponse(action, payload).toByteArray())
            }
        }).use { server ->
            server.start(loopback)
            val client = DlnaClient()
            val renderer = client.describe("http://127.0.0.1:${server.port}/device.xml")
            assertEquals("audio/wav", client.chooseFormat(renderer, listOf("audio/aac", "audio/wav")))
            client.setUri(renderer, "http://127.0.0.1:4567/media/abc", "A & B <live>", "audio/wav", 243_000)
            client.play(renderer)
            client.pause(renderer)
            client.seek(renderer, 72_999)
            client.setVolume(renderer, 140)
            val status = client.status(renderer)
            client.stop(renderer)
            assertEquals("PLAYING", status.transportState)
            assertEquals(72_500L, status.positionMillis)
            assertEquals(243_000L, status.durationMillis)
            assertEquals(37, status.volume)
            val uri = requests.single { it.headers["soapaction"].orEmpty().contains("#SetAVTransportURI") }.body.toString(Charsets.UTF_8)
            assertTrue(uri.contains("A &amp;amp; B &amp;lt;live&amp;gt;"))
            assertTrue(uri.contains("<CurrentURI>http://127.0.0.1:4567/media/abc</CurrentURI>"))
            val seek = requests.single { it.headers["soapaction"].orEmpty().contains("#Seek") }.body.toString(Charsets.UTF_8)
            assertTrue(seek.contains("<Unit>REL_TIME</Unit><Target>00:01:12</Target>"))
            val volume = requests.single { it.headers["soapaction"].orEmpty().contains("#SetVolume") }.body.toString(Charsets.UTF_8)
            assertTrue(volume.contains("<DesiredVolume>100</DesiredVolume>"))
        }
    }

    @Test fun soapFaultsHideReceiverTextAndCredentialsNeverEnterUriCommands() {
        BoundedHttpServer(handler = {
            HttpResponse(500, body = "<error><errorCode>701</errorCode><errorDescription>secret source address</errorDescription></error>".toByteArray())
        }).use { server ->
            server.start(loopback)
            val renderer = DlnaClient.parseDescription("http://127.0.0.1:${server.port}/device.xml", descriptor())
            val client = DlnaClient()
            val fault = assertThrows(DlnaException::class.java) { client.play(renderer) }
            assertEquals("Receiver rejected the command (701).", fault.message)
            listOf("http://user:secret@127.0.0.1/audio", "http://127.0.0.1/audio?api_key=secret", "file:///data/audio.wav").forEach {
                val error = assertThrows(DlnaException::class.java) { client.setUri(renderer, it, "Title", "audio/wav") }
                assertFalse(error.message.orEmpty().contains("secret"))
            }
        }
    }

    @Test fun discoveryRejectsAddressesUnrelatedToResponseSender() {
        val packet = "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:1234/device.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://127.0.0.1:1234/device.xml", DlnaClient.parseDiscoveryResponse(packet, loopback))
        assertNull(DlnaClient.parseDiscoveryResponse(packet, InetAddress.getByName("192.168.1.10")))
        assertNull(DlnaClient.parseDiscoveryResponse(packet.replace("127.0.0.1", "8.8.8.8"), loopback))
    }

    @Test fun timeParsingHandlesUnsupportedAndFractionalValues() {
        assertEquals(0L, DlnaClient.parseTime("NOT_IMPLEMENTED"))
        assertEquals(0L, DlnaClient.parseTime("00:00:NaN"))
        assertEquals(3_661_125L, DlnaClient.parseTime("01:01:01.125"))
        assertEquals("01:01:01", DlnaClient.formatTime(3_661_125))
    }

    private fun descriptor(): String = """
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
          <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
          <friendlyName>Living room &amp; speaker</friendlyName><UDN>uuid:aurora-test</UDN>
          <serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/transport</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rendering</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/connection</controlURL></service>
          </serviceList>
          <deviceList><device><deviceType>urn:example:Other:1</deviceType><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/wrong</controlURL></service>
          </serviceList></device></deviceList>
        </device></root>
    """.trimIndent()

    private fun soapResponse(action: String, payload: String) = """
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$payload</u:${action}Response>
        </s:Body></s:Envelope>
    """.trimIndent()
}
