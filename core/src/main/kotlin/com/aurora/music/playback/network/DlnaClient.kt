package com.aurora.music.playback.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

data class DlnaService(val type: String, val controlUrl: String)
data class DlnaRenderer(
    val id: String,
    val name: String,
    val location: String,
    val avTransport: DlnaService,
    val renderingControl: DlnaService?,
    val connectionManager: DlnaService?,
)

data class DlnaProtocol(val protocol: String, val network: String, val mimeType: String, val extra: String) {
    fun accepts(mime: String): Boolean {
        if (!protocol.equals("http-get", true) && protocol != "*") return false
        val wanted = mime.substringBefore(';').trim()
        val supplied = mimeType.substringBefore(';').trim()
        if (supplied != "*" && !supplied.equals(wanted, true)) return false
        val constraints = mimeType.split(';').drop(1).map { it.trim().lowercase(Locale.ROOT) }
        val parameters = mime.split(';').drop(1).map { it.trim().lowercase(Locale.ROOT) }
        return constraints.all { it in parameters }
    }
}

data class DlnaStatus(
    val transportState: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val volume: Int?,
)

class DlnaException(message: String) : IOException(message)

class DlnaClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = client.dns.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !isLocalAddress(it) }) {
                    throw java.net.UnknownHostException("Receiver is not on the local network.")
                }
            }
        }).build()

    fun discover(timeoutMillis: Int = 3_000): List<DlnaRenderer> {
        require(timeoutMillis in 250..10_000)
        val locations = linkedSetOf<String>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        val search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
            .toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { socket ->
            val destination = InetAddress.getByName("239.255.255.250")
            repeat(2) { socket.send(DatagramPacket(search, search.size, destination, 1900)) }
            while (System.nanoTime() < deadline && locations.size < 16) {
                socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, 500).toInt()
                val response = DatagramPacket(ByteArray(8_192), 8_192)
                try { socket.receive(response) } catch (_: SocketTimeoutException) { continue }
                val location = parseDiscoveryResponse(
                    String(response.data, response.offset, response.length, Charsets.US_ASCII), response.address,
                ) ?: continue
                locations.add(location)
            }
        }
        val resolveDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        return locations.mapNotNull { location ->
            if (System.nanoTime() >= resolveDeadline) null else runCatching {
                describe(location, TimeUnit.NANOSECONDS.toMillis(resolveDeadline - System.nanoTime()).coerceAtLeast(1))
            }.getOrNull()
        }.distinctBy { it.id }
    }

    fun describe(location: String): DlnaRenderer = describe(location, 8_000)

    private fun describe(location: String, timeoutMillis: Long): DlnaRenderer {
        val uri = localUri(location)
        val call = client.newCall(Request.Builder().url(uri.toASCIIString()).get().build())
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        val xml = try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw DlnaException("Receiver description unavailable.")
                readXml(response.body?.byteStream() ?: throw DlnaException("Receiver description is empty."))
            }
        } catch (error: DlnaException) { throw error }
        catch (_: Exception) { throw DlnaException("Receiver description unavailable.") }
        return parseDescription(uri.toString(), xml)
    }

    fun protocols(renderer: DlnaRenderer): List<DlnaProtocol> {
        val manager = renderer.connectionManager ?: return emptyList()
        return parseProtocols(soap(manager, "GetProtocolInfo", emptyMap())["Sink"].orEmpty())
    }

    fun chooseFormat(renderer: DlnaRenderer, offered: List<String>): String? {
        val supported = protocols(renderer)
        return offered.firstOrNull { mime -> supported.any { it.accepts(mime) } }
    }

    fun setUri(renderer: DlnaRenderer, uri: String, title: String, mimeType: String, durationMillis: Long = 0) {
        val media = try { URI(uri) } catch (_: Exception) { throw DlnaException("Invalid media address.") }
        if (media.scheme !in setOf("http", "https") || media.host == null || media.userInfo != null ||
            media.rawQuery != null || media.rawFragment != null) throw DlnaException("Use a shared media address.")
        require(mimeType.matches(Regex("audio/[A-Za-z0-9.+-]+(?:;[A-Za-z0-9= -]+)*"))) { "Unsupported media type." }
        val metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"aurora\" parentID=\"0\" restricted=\"1\"><dc:title>${xmlEscape(title.take(512))}</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${xmlEscape(mimeType)}:*\" duration=\"${formatTime(durationMillis)}\">" +
            "${xmlEscape(uri)}</res></item></DIDL-Lite>"
        soap(renderer.avTransport, "SetAVTransportURI", linkedMapOf(
            "InstanceID" to "0", "CurrentURI" to uri, "CurrentURIMetaData" to metadata,
        ))
    }

    fun play(renderer: DlnaRenderer) { transport(renderer, "Play", "Speed" to "1") }
    fun pause(renderer: DlnaRenderer) { transport(renderer, "Pause") }
    fun stop(renderer: DlnaRenderer) { transport(renderer, "Stop") }
    fun seek(renderer: DlnaRenderer, positionMillis: Long) {
        transport(renderer, "Seek", "Unit" to "REL_TIME", "Target" to formatTime(positionMillis))
    }
    fun setVolume(renderer: DlnaRenderer, volume: Int) {
        val service = renderer.renderingControl ?: throw DlnaException("Receiver volume is unavailable.")
        soap(service, "SetVolume", linkedMapOf(
            "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString(),
        ))
    }

    fun status(renderer: DlnaRenderer): DlnaStatus {
        val state = transport(renderer, "GetTransportInfo")["CurrentTransportState"].orEmpty()
        val position = transport(renderer, "GetPositionInfo")
        val volume = renderer.renderingControl?.let { service ->
            runCatching { soap(service, "GetVolume", linkedMapOf("InstanceID" to "0", "Channel" to "Master")) }
                .getOrNull()?.get("CurrentVolume")?.toIntOrNull()?.coerceIn(0, 100)
        }
        return DlnaStatus(state, parseTime(position["RelTime"].orEmpty()), parseTime(position["TrackDuration"].orEmpty()), volume)
    }

    private fun transport(renderer: DlnaRenderer, action: String, vararg args: Pair<String, String>): Map<String, String> =
        soap(renderer.avTransport, action, linkedMapOf("InstanceID" to "0", *args))

    private fun soap(service: DlnaService, action: String, args: Map<String, String>): Map<String, String> {
        val address = localUri(service.controlUrl)
        if (!service.type.matches(Regex("urn:schemas-upnp-org:service:[A-Za-z]+:[0-9]+"))) {
            throw DlnaException("Unsupported receiver service.")
        }
        val envelope = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"${service.type}\">")
            args.forEach { (key, value) -> append("<$key>${xmlEscape(value)}</$key>") }
            append("</u:$action></s:Body></s:Envelope>")
        }
        val request = Request.Builder().url(address.toASCIIString())
            .header("SOAPAction", "\"${service.type}#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.byteStream()?.let(::readXml).orEmpty()
                val values = if (text.isBlank()) emptyMap() else leafValues(parseXml(text))
                if (!response.isSuccessful || "errorCode" in values) {
                    val code = values["errorCode"]?.takeIf { it.matches(Regex("[0-9]{3}")) }
                    throw DlnaException(if (code == null) "Receiver rejected the command." else "Receiver rejected the command ($code).")
                }
                values
            }
        } catch (error: DlnaException) { throw error }
        catch (_: Exception) { throw DlnaException("Receiver did not respond.") }
    }

    companion object {
        private const val MAX_XML_BYTES = 262_144

        fun parseProtocols(value: String): List<DlnaProtocol> = value.split(',').take(256).mapNotNull { item ->
            val parts = item.trim().split(':', limit = 4)
            if (parts.size != 4) null else DlnaProtocol(parts[0], parts[1], parts[2], parts[3])
        }

        fun parseDescription(location: String, xml: String): DlnaRenderer {
            val document = parseXml(xml)
            val original = localUri(location)
            val baseText = document.getElementsByTagNameNS("*", "URLBase").item(0)?.textContent?.trim().orEmpty()
            val base = if (baseText.isBlank()) original else original.resolve(baseText).also { sameHost(original, it) }
            val devices = document.getElementsByTagNameNS("*", "device")
            val device = (0 until devices.length).mapNotNull { devices.item(it) as? Element }.firstOrNull {
                childText(it, "deviceType").startsWith("urn:schemas-upnp-org:device:MediaRenderer:")
            } ?: throw DlnaException("This device is not a media receiver.")
            val list = childElement(device, "serviceList")
            val services = list?.childNodes?.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
                .orEmpty().filter { it.localName == "service" }.take(32).mapNotNull { element ->
                    val type = childText(element, "serviceType")
                    if (!type.matches(Regex("urn:schemas-upnp-org:service:(AVTransport|RenderingControl|ConnectionManager):[0-9]+"))) return@mapNotNull null
                    val control = childText(element, "controlURL")
                    if (control.isBlank()) return@mapNotNull null
                    val address = base.resolve(control)
                    sameHost(original, address)
                    DlnaService(type, address.toASCIIString())
                }
            fun service(type: String) = services.firstOrNull { it.type.startsWith("urn:schemas-upnp-org:service:$type:") }
            val transport = service("AVTransport") ?: throw DlnaException("Receiver playback control is unavailable.")
            return DlnaRenderer(
                childText(device, "UDN").take(256).ifBlank { original.toASCIIString() },
                childText(device, "friendlyName").take(128).ifBlank { "DLNA receiver" }, original.toASCIIString(),
                transport, service("RenderingControl"), service("ConnectionManager"),
            )
        }

        internal fun parseDiscoveryResponse(message: String, sender: InetAddress): String? = runCatching {
            if (message.length > 8_192 || !message.startsWith("HTTP/1.1 200")) return null
            val headers = message.split("\r\n").drop(1).filter { ':' in it }.associate {
                it.substringBefore(':').lowercase(Locale.ROOT) to it.substringAfter(':').trim()
            }
            val location = headers["location"] ?: return null
            val uri = localUri(location)
            if (InetAddress.getAllByName(uri.host).none { it == sender }) return null
            uri.toASCIIString()
        }.getOrNull()

        private fun sameHost(original: URI, candidate: URI) {
            localUri(candidate.toString())
            if (!candidate.host.equals(original.host, true)) throw DlnaException("Receiver control address is invalid.")
        }

        private fun localUri(value: String): URI {
            val uri = try { URI(value) } catch (_: Exception) { throw DlnaException("Invalid receiver address.") }
            if (uri.scheme !in setOf("http", "https") || uri.host == null || uri.userInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65_535 || uri.port == 0) {
                throw DlnaException("Invalid receiver address.")
            }
            val addresses = try { InetAddress.getAllByName(uri.host) } catch (_: Exception) { throw DlnaException("Receiver address unavailable.") }
            if (addresses.isEmpty() || addresses.any { !isLocalAddress(it) }) {
                throw DlnaException("Choose a receiver on the local network.")
            }
            return uri
        }

        private fun isLocalAddress(address: InetAddress): Boolean = !address.isAnyLocalAddress &&
            (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc))

        private fun parseXml(xml: String): org.w3c.dom.Document {
            if (xml.length > MAX_XML_BYTES || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
                throw DlnaException("Receiver description is invalid.")
            }
            return try {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                }
                factory.newDocumentBuilder().apply {
                    setEntityResolver { _, _ -> throw SAXException("External entities are disabled.") }
                    setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                        override fun error(error: org.xml.sax.SAXParseException) { throw error }
                        override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
                    })
                }.parse(InputSource(StringReader(xml)))
            } catch (_: Exception) { throw DlnaException("Receiver description is invalid.") }
        }

        private fun childElement(element: Element, name: String): Element? = element.childNodes.let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.firstOrNull { it.localName == name }
        }
        private fun childText(element: Element, name: String): String = childElement(element, name)?.textContent?.trim().orEmpty()

        private fun leafValues(document: org.w3c.dom.Document): Map<String, String> {
            val all = document.getElementsByTagName("*")
            return (0 until all.length).mapNotNull { index ->
                val element = all.item(index) as? Element ?: return@mapNotNull null
                if ((0 until element.childNodes.length).any { element.childNodes.item(it) is Element }) null
                else (element.localName ?: element.nodeName) to element.textContent.trim().take(16_384)
            }.toMap()
        }

        private fun readXml(input: java.io.InputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8_192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (bytes.size() + read > MAX_XML_BYTES) throw DlnaException("Receiver response is too large.")
                bytes.write(chunk, 0, read)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }

        internal fun formatTime(millis: Long): String {
            val seconds = millis.coerceAtLeast(0) / 1_000
            return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
        }
        internal fun parseTime(value: String): Long {
            val parts = value.split(':')
            if (parts.size != 3) return 0
            val hours = parts[0].toLongOrNull()?.takeIf { it in 0..999_999 } ?: return 0
            val minutes = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return 0
            val seconds = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it < 60 } ?: return 0
            return hours * 3_600_000 + minutes * 60_000 + (seconds * 1_000).toLong()
        }
        private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }
}
