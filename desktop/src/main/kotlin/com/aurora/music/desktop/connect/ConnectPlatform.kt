package com.aurora.music.desktop.connect

import com.aurora.music.desktop.platform.WebCredentials
import com.aurora.music.playback.network.endpoint.ControllerTrust
import com.aurora.music.playback.network.endpoint.DiscoveredRenderer
import com.aurora.music.playback.network.endpoint.EndpointAdvertiser
import com.aurora.music.playback.network.endpoint.EndpointAddress
import com.aurora.music.playback.network.endpoint.EndpointTls
import com.aurora.music.playback.network.endpoint.PairedController
import com.aurora.music.playback.network.endpoint.PairedRenderer
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.net.ssl.SSLContext

private const val SERVICE_TYPE = "_aurora-music._tcp.local."
private const val IDENTITY = "a0c0ffee-0000-4000-8000-000000000001"
private const val TRUST = "a0c0ffee-0000-4000-8000-000000000002"

// the key stays in the local (non-roaming) profile so two machines never share one identity
internal fun connectIdentity(vault: WebCredentials): Pair<SSLContext, String> {
    val stored = runCatching { HeldCertificate.decode(vault.read(IDENTITY)) }.getOrNull()
        ?.takeIf { it.certificate.notAfter.time > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30) }
    val held = stored ?: HeldCertificate.Builder().ecdsa256().commonName("Aurora Renderer")
        .validityInterval(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1), System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650))
        .build().also { vault.update(IDENTITY, it.certificatePem() + it.privateKeyPkcs8Pem()) }
    return HandshakeCertificates.Builder().heldCertificate(held).build().sslContext() to EndpointTls.fingerprint(held.certificate)
}

internal class ConnectTrustStore(private val vault: WebCredentials) {
    @Synchronized fun controllers(): List<ControllerTrust> = read().array("controllers").mapNotNull { item ->
        runCatching {
            val value = item.asJsonObject
            val hash = value.get("hash").asString
            require(hash.matches(Regex("[a-f0-9]{64}")))
            ControllerTrust(PairedController(value.get("id").asString.take(64), value.get("name").asString.take(64), value.get("pairedAtMs").asLong), hash)
        }.getOrNull()
    }.take(8)

    @Synchronized fun saveControllers(values: List<ControllerTrust>) = write(read().apply {
        add("controllers", JsonArray().apply { values.forEach { trust -> add(JsonObject().apply {
            addProperty("id", trust.controller.id); addProperty("name", trust.controller.name)
            addProperty("pairedAtMs", trust.controller.pairedAtMs); addProperty("hash", trust.tokenHash)
        }) } })
    })

    @Synchronized fun renderers(): List<PairedRenderer> = read().array("renderers").mapNotNull { item ->
        runCatching {
            val value = item.asJsonObject
            val address = EndpointAddress.parse(value.get("address").asString + "#" + value.get("fingerprint").asString)
            val token = value.get("token").asString
            require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
            val addresses = value.get("addresses")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                runCatching { EndpointAddress.parse(it.asString + "#" + address.fingerprint).address }.getOrNull()
            }.orEmpty().take(8)
            PairedRenderer(value.get("id").asString.take(64), value.get("name").asString.take(64), address.address,
                address.fingerprint, value.get("clientId").asString.take(64), token, addresses)
        }.getOrNull()
    }.take(16)

    @Synchronized fun saveRenderer(renderer: PairedRenderer) = saveRenderers((renderers().filterNot { it.id == renderer.id } + renderer).takeLast(16))

    @Synchronized fun forgetRenderer(id: String) = saveRenderers(renderers().filterNot { it.id == id })

    private fun saveRenderers(values: List<PairedRenderer>) = write(read().apply {
        add("renderers", JsonArray().apply { values.forEach { peer -> add(JsonObject().apply {
            addProperty("id", peer.id); addProperty("name", peer.name); addProperty("address", peer.address)
            addProperty("fingerprint", peer.fingerprint); addProperty("clientId", peer.clientId); addProperty("token", peer.token)
            add("addresses", JsonArray().apply { peer.addresses.forEach(::add) })
        }) } })
    })

    private fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun read(): JsonObject = runCatching { JsonParser.parseString(vault.read(TRUST)).asJsonObject }.getOrElse { JsonObject() }
    private fun write(value: JsonObject) = vault.update(TRUST, value.toString())
}

private fun lanAddresses(): List<InetAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress && !EndpointTls.isTailscale(it) }
}.getOrDefault(emptyList())

internal class MdnsAdvertiser : EndpointAdvertiser {
    private var instances = emptyList<JmDNS>()

    @Synchronized override fun start(name: String, id: String, port: Int, connect: Boolean) {
        stop()
        val properties = mapOf("v" to "1", "id" to id, "name" to name.take(64), "role" to "renderer", "connect" to if (connect) "1" else "0")
        instances = lanAddresses().mapNotNull { address ->
            runCatching {
                JmDNS.create(address).also { it.registerService(ServiceInfo.create(SERVICE_TYPE, "${name.take(40)} ${id.take(6)}", port, 0, 0, properties)) }
            }.getOrNull()
        }
    }

    @Synchronized override fun stop() {
        instances.forEach { runCatching { it.unregisterAllServices(); it.close() } }
        instances = emptyList()
    }
}

internal suspend fun discoverDevices(): List<DiscoveredRenderer> = coroutineScope {
    lanAddresses().map { address ->
        async(Dispatchers.IO) {
            runCatching { JmDNS.create(address).use { dns -> dns.list(SERVICE_TYPE, 3_000).mapNotNull { discovered(it, address) } } }.getOrDefault(emptyList())
        }
    }.awaitAll().flatten().distinctBy { it.id }
}

// a phone also announces its mobile-data and vowifi addresses, so take the one on the subnet we heard it from
private fun discovered(info: ServiceInfo, local: InetAddress): DiscoveredRenderer? {
    val host = info.inet4Addresses.sortedByDescending { remote -> remote.address.zip(local.address).takeWhile { (a, b) -> a == b }.size }
        .firstOrNull()?.hostAddress ?: return null
    val id = info.getPropertyString("id").orEmpty()
    if (info.getPropertyString("v") != "1" || !id.matches(Regex("[a-f0-9]{32}")) || info.port !in 1..65535) return null
    return DiscoveredRenderer(id, info.getPropertyString("name").orEmpty().take(64), "https://$host:${info.port}",
        info.getPropertyString("connect") == "1")
}

internal fun deviceName(): String {
    // linux gui sessions rarely export HOSTNAME, and getLocalHost can wait on dns before the window shows
    val host = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
        ?: runCatching { java.io.File("/proc/sys/kernel/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
    return "Aurora · ${host?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: com.aurora.music.desktop.platform.HostPlatform.name}".take(64)
}
