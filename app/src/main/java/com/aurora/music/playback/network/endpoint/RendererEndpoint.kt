package com.aurora.music.playback.network.endpoint

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aurora.music.playback.network.BoundedHttpServer
import com.aurora.music.playback.network.HttpRequest
import com.aurora.music.playback.network.HttpResponse
import com.aurora.music.playback.network.endpoint.RendererJson.text
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class RendererEndpoint(
    context: Context,
    private val playback: RendererPlayback,
    private val name: String,
) {
    private val trustStore = RendererTrustStore(context)
    private val pairingAuth = RendererPairing(trustStore.controllers(), persist = trustStore::saveControllers)
    private val mutableClients = MutableStateFlow(pairingAuth.clients())
    private val mutablePairing = MutableStateFlow<PairingWindow?>(null)
    val clients: StateFlow<List<PairedController>> = mutableClients.asStateFlow()
    val pairing: StateFlow<PairingWindow?> = mutablePairing.asStateFlow()
    private val advertisement = RendererAdvertisement(context)
    private val appContext = context.applicationContext
    private var server: BoundedHttpServer? = null
    private var rendererId = ""
    @Volatile var endpointAddress: EndpointAddress? = null
        private set
    private val commandLock = ReentrantLock()
    private val responses = ConcurrentHashMap<String, HttpResponse>()
    private var expiryTimer: ScheduledExecutorService? = null
    @Volatile private var generation = 0L

    @Synchronized
    fun start(port: Int = 0, advertisedHost: String? = null): EndpointAddress {
        endpointAddress?.let { return it }
        val (tls, fingerprint) = RendererTls.server()
        rendererId = fingerprint.take(32)
        val host = advertisedHost ?: localAddress(appContext)
        val listener = BoundedHttpServer(::handle, maxBodyBytes = 1_048_576, maxConcurrentRequests = 4,
            requestTimeoutMillis = 120_000, serverSocketFactory = tls.serverSocketFactory)
        val actualPort = listener.start(port = port)
        val address = EndpointAddress("https://$host:$actualPort", fingerprint)
        server = listener
        generation++
        endpointAddress = address
        advertisement.start(name, rendererId, actualPort)
        return address
    }

    @Synchronized
    fun stop() {
        server?.close()
        generation++
        server = null
        endpointAddress = null
        pairingAuth.cancel()
        mutablePairing.value = null
        expiryTimer?.shutdownNow()
        expiryTimer = null
        advertisement.stop()
        responses.clear()
    }

    @Synchronized fun beginPairing(): PairingWindow {
        if (endpointAddress == null) throw RendererException("renderer_offline", "Enable the renderer first.", 409)
        expiryTimer?.shutdownNow()
        expiryTimer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "aurora-pairing-expiry").apply { isDaemon = true } }
        return pairingAuth.begin().also { window ->
            mutablePairing.value = window
            expiryTimer?.schedule({
                if (mutablePairing.value == window) mutablePairing.value = pairingAuth.current()
            }, 120_100, TimeUnit.MILLISECONDS)
        }
    }

    fun revokeClient(id: String) {
        pairingAuth.revoke(id)
        responses.keys.removeAll { it.startsWith("$id:") }
        mutableClients.value = pairingAuth.clients()
    }

    private fun handle(request: HttpRequest): HttpResponse = try {
        when {
            request.method == "GET" && request.path == "/v1/info" -> json(JsonObject().apply {
                addProperty("version", RENDERER_PROTOCOL_VERSION)
                addProperty("role", "renderer")
                addProperty("id", rendererId)
                addProperty("name", name.take(64))
                addProperty("maxQueueTracks", 100)
                addProperty("sourceMode", "scoped-cache")
                add("commands", RendererJson.gson.toJsonTree(listOf("queue", "playing", "seek", "volume", "route", "repeat", "reorder", "next", "previous", "stop")))
            })
            request.method == "POST" && request.path == "/v1/pair" -> {
                val body = RendererJson.parse(request.body)
                if (body.get("version")?.asInt != RENDERER_PROTOCOL_VERSION) {
                    throw RendererException("unsupported_version", "Update both Aurora devices.", 409)
                }
                val (controller, token) = try {
                    pairingAuth.pair(body.text("name", 64), body.text("code", 6))
                } finally { mutablePairing.value = pairingAuth.current() }
                mutableClients.value = pairingAuth.clients()
                json(JsonObject().apply {
                    addProperty("version", RENDERER_PROTOCOL_VERSION)
                    addProperty("id", rendererId)
                    addProperty("name", name.take(64))
                    addProperty("clientId", controller.id)
                    addProperty("token", token)
                })
            }
            request.method == "GET" && request.path == "/v1/status" -> {
                authenticate(request)
                json(status())
            }
            request.method == "POST" && request.path == "/v1/command" -> {
                val authorized = authenticate(request)
                val requestGeneration = generation
                val body = RendererJson.parse(request.body)
                val requestId = body.text("requestId", 64)
                if (!requestId.matches(Regex("[A-Za-z0-9_-]{8,64}"))) {
                    throw RendererException("invalid_request", "Invalid request identifier.")
                }
                val parsed = RendererJson.command(body)
                val stillAuthorized = { generation == requestGeneration && endpointAddress != null &&
                    runCatching { authenticate(request).id == authorized.id }.getOrDefault(false) }
                val command = if (parsed is RendererCommand.Queue) parsed.copy(
                    sourceHost = request.remoteAddress.hostAddress,
                    authorizationValid = stillAuthorized,
                ) else parsed
                if (!commandLock.tryLock(200, TimeUnit.MILLISECONDS)) {
                    throw RendererException("renderer_busy", "The renderer is loading audio. Try again.", 503)
                }
                try {
                    val controller = authenticate(request)
                    val key = "${controller.id}:$requestId"
                    responses[key] ?: json(playback.executeAuthorized(command, stillAuthorized).withIdentity()).also {
                        if (responses.size >= 128) responses.clear()
                        responses[key] = it
                    }
                } finally { commandLock.unlock() }
            }
            else -> throw RendererException("not_found", "Unsupported renderer request.", 404)
        }
    } catch (error: RendererException) {
        json(JsonObject().apply {
            addProperty("version", RENDERER_PROTOCOL_VERSION)
            addProperty("error", error.code)
            addProperty("message", error.message)
        }, error.httpStatus)
    } catch (_: IllegalArgumentException) {
        json(JsonObject().apply {
            addProperty("version", RENDERER_PROTOCOL_VERSION)
            addProperty("error", "invalid_request")
            addProperty("message", "Invalid renderer request.")
        }, 400)
    } catch (_: IllegalStateException) {
        json(JsonObject().apply {
            addProperty("version", RENDERER_PROTOCOL_VERSION)
            addProperty("error", "invalid_request")
            addProperty("message", "Invalid renderer request.")
        }, 400)
    } catch (_: Exception) {
        json(JsonObject().apply {
            addProperty("version", RENDERER_PROTOCOL_VERSION)
            addProperty("error", "renderer_failed")
            addProperty("message", "The renderer could not complete this request.")
        }, 500)
    }

    private fun authenticate(request: HttpRequest): PairedController {
        val header = request.headers["authorization"].orEmpty()
        return pairingAuth.authenticate(header.removePrefix("Bearer ").takeIf { header.startsWith("Bearer ") }.orEmpty())
            ?: throw RendererException("unauthorized", "Pair with this renderer again.", 401)
    }

    private fun status(): RendererStatus = playback.status().withIdentity()
    private fun RendererStatus.withIdentity() = copy(rendererId = this@RendererEndpoint.rendererId, name = this@RendererEndpoint.name.take(64))
    private fun json(value: Any, status: Int = 200): HttpResponse = HttpResponse(status,
        mapOf("Content-Type" to "application/json; charset=utf-8", "Cache-Control" to "no-store"),
        RendererJson.gson.toJson(value).toByteArray(Charsets.UTF_8))

    companion object {
        @Suppress("DEPRECATION")
        fun localAddress(context: Context): String {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val active = connectivity.activeNetwork
            val networks = connectivity.allNetworks.sortedBy { if (it == active) 0 else 1 }
            for (network in networks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue
                val address = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull {
                        it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.isLoopbackAddress
                    }
                if (address != null) return requireNotNull(address.hostAddress)
            }
            throw RendererException("local_network_required", "Connect to Wi-Fi or Ethernet first.", 409)
        }
    }
}
