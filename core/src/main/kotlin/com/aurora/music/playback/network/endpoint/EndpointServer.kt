package com.aurora.music.playback.network.endpoint

import com.aurora.music.playback.network.BoundedHttpServer
import com.aurora.music.playback.network.HttpRequest
import com.aurora.music.playback.network.HttpResponse
import com.aurora.music.playback.network.endpoint.RendererJson.text
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.BindException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext

// same window as ConnectStatus.windowed, but only the visible tracks get built
fun ConnectStatus.windowedFrom(total: Int, track: (Int) -> ConnectTrack): ConnectStatus {
    val start = (index - CONNECT_QUEUE_WINDOW / 3).coerceIn(0, (total - CONNECT_QUEUE_WINDOW).coerceAtLeast(0))
    return copy(offset = start, total = total, queue = List(minOf(total - start, CONNECT_QUEUE_WINDOW)) { track(start + it) })
}

interface EndpointAdvertiser {
    fun start(name: String, id: String, port: Int, connect: Boolean)
    fun stop()
}

open class EndpointServer(
    private val name: String,
    private val identity: () -> Pair<SSLContext, String>,
    trust: List<ControllerTrust>,
    persist: (List<ControllerTrust>) -> Unit,
    private val advertiser: EndpointAdvertiser,
    private val defaultHost: () -> String,
    private val platform: String,
    @Volatile var playback: RendererPlayback? = null,
    connect: ConnectHost? = null,
    private val reachable: () -> List<String> = EndpointTls::localAddresses,
) {
    private val pairingAuth = RendererPairing(trust, persist = persist)
    private val mutableClients = MutableStateFlow(pairingAuth.clients())
    private val mutablePairing = MutableStateFlow<PairingWindow?>(null)
    val clients: StateFlow<List<PairedController>> = mutableClients.asStateFlow()
    val pairing: StateFlow<PairingWindow?> = mutablePairing.asStateFlow()
    private var server: BoundedHttpServer? = null
    private var rendererId = ""
    @Volatile var endpointAddress: EndpointAddress? = null
        private set
    private val commandLock = ReentrantLock()
    private val responses = ConcurrentHashMap<String, HttpResponse>()
    private var expiryTimer: ScheduledExecutorService? = null
    @Volatile private var generation = 0L

    @Volatile var connect: ConnectHost? = connect
        set(value) {
            field = value
            endpointAddress?.let { advertiser.start(name, rendererId, server?.port ?: return, value != null) }
        }

    val deviceId: String get() = rendererId

    @Synchronized
    fun start(port: Int = CONNECT_PORT, advertisedHost: String? = null): EndpointAddress {
        endpointAddress?.let { return it }
        val (tls, fingerprint) = identity()
        rendererId = fingerprint.take(32)
        val host = advertisedHost ?: defaultHost()
        fun listener() = BoundedHttpServer(::handle, maxBodyBytes = 1_048_576, maxConcurrentRequests = 12,
            requestTimeoutMillis = 120_000, serverSocketFactory = tls.serverSocketFactory)
        var listener = listener()
        val actualPort = try { listener.start(port = port) } catch (_: BindException) {
            listener.close()
            listener = listener()
            listener.start(port = 0)
        }
        val address = EndpointAddress("https://${if (':' in host) "[$host]" else host}:$actualPort", fingerprint)
        server = listener
        generation++
        endpointAddress = address
        advertiser.start(name, rendererId, actualPort, connect != null)
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
        advertiser.stop()
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

    // every address this device answers on, so a controller paired at home also finds it over tailscale
    fun addresses(): List<String> {
        val port = server?.port ?: return emptyList()
        return (listOfNotNull(endpointAddress?.address) + reachable().map { "https://$it:$port" }).distinct()
    }

    private fun handle(request: HttpRequest): HttpResponse = try {
        val path = request.path.substringBefore('?')
        when {
            request.method == "GET" && path == "/v1/info" -> json(JsonObject().apply {
                addProperty("version", RENDERER_PROTOCOL_VERSION)
                addProperty("role", "renderer")
                addProperty("id", rendererId)
                addProperty("name", name.take(64))
                addProperty("platform", platform)
                addProperty("renderer", playback != null)
                addProperty("connect", connect != null)
                addProperty("maxQueueTracks", 100)
                addProperty("sourceMode", "scoped-cache")
                add("commands", RendererJson.gson.toJsonTree(listOf("queue", "playing", "seek", "volume", "route", "repeat", "reorder", "next", "previous", "stop")))
            })
            request.method == "POST" && path == "/v1/pair" -> {
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
                    add("addresses", RendererJson.gson.toJsonTree(addresses().take(8)))
                })
            }
            request.method == "GET" && path == "/v1/status" -> {
                authenticate(request)
                json(status())
            }
            request.method == "POST" && path == "/v1/command" -> {
                val authorized = authenticate(request)
                val renderer = playback ?: throw RendererException("receiver_disabled", "That device only accepts remote control.", 409)
                val requestGeneration = generation
                val body = RendererJson.parse(request.body)
                val requestId = requestId(body)
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
                    responses[key] ?: json(renderer.executeAuthorized(command, stillAuthorized).withIdentity()).also { remember(key, it) }
                } finally { commandLock.unlock() }
            }
            request.method == "GET" && path == "/v1/connect" -> {
                authenticate(request)
                val host = connectHost()
                val query = query(request.path)
                val tag = query["wait"]
                var status = host.status()
                if (tag != null) {
                    val position = query["pos"]?.toLongOrNull() ?: status.positionMs
                    val started = System.nanoTime()
                    val waitGeneration = generation
                    while (status.tag() == tag && waitGeneration == generation) {
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        val expected = position + if (status.playing) (elapsed * status.speed).toLong() else 0L
                        if (kotlin.math.abs(status.positionMs - expected) > 2_000 || elapsed >= 20_000) break
                        Thread.sleep(250)
                        status = host.status()
                    }
                }
                connectJson(status)
            }
            request.method == "POST" && path == "/v1/connect" -> {
                val controller = authenticate(request)
                val host = connectHost()
                val body = RendererJson.parse(request.body)
                val key = "${controller.id}:${requestId(body)}"
                responses[key] ?: run {
                    host.execute(ConnectJson.command(body))
                    connectJson(host.status()).also { remember(key, it) }
                }
            }
            request.method == "GET" && path == "/v1/connect/art" -> {
                authenticate(request)
                val id = query(request.path)["id"].orEmpty()
                if (id.isEmpty() || id.length > ConnectJson.MAX_ID) throw RendererException("invalid_request", "Invalid artwork request.")
                val bytes = connectHost().artwork(id)?.takeIf { it.size in 1..8_388_608 }
                    ?: throw RendererException("not_found", "No artwork.", 404)
                HttpResponse(200, mapOf("Content-Type" to imageType(bytes)), bytes)
            }
            else -> throw RendererException("not_found", "Unsupported renderer request.", 404)
        }
    } catch (error: RendererException) {
        failure(error.code, error.message.orEmpty(), error.httpStatus)
    } catch (_: IllegalArgumentException) {
        failure("invalid_request", "Invalid renderer request.", 400)
    } catch (_: IllegalStateException) {
        failure("invalid_request", "Invalid renderer request.", 400)
    } catch (_: InterruptedException) {
        failure("renderer_offline", "Remote control stopped on that device.", 503)
    } catch (_: Exception) {
        failure("renderer_failed", "The renderer could not complete this request.", 500)
    }

    private fun connectHost(): ConnectHost = connect ?: throw RendererException("connect_disabled", "Remote control is off on that device.", 409)

    private fun connectJson(status: ConnectStatus): HttpResponse {
        val identified = status.copy(deviceId = rendererId, name = name.take(64), platform = platform)
        return json(ConnectJson.encode(identified).apply { addProperty("tag", status.tag()) })
    }

    private fun ConnectStatus.tag(): String = Integer.toHexString(copy(positionMs = 0).hashCode())

    private fun requestId(body: JsonObject): String = body.text("requestId", 64).also {
        if (!it.matches(Regex("[A-Za-z0-9_-]{8,64}"))) throw RendererException("invalid_request", "Invalid request identifier.")
    }

    private fun remember(key: String, response: HttpResponse) {
        if (responses.size >= 128) responses.clear()
        responses[key] = response
    }

    private fun query(path: String): Map<String, String> = path.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun imageType(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 11 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun failure(code: String, message: String, status: Int) = json(JsonObject().apply {
        addProperty("version", RENDERER_PROTOCOL_VERSION)
        addProperty("error", code)
        addProperty("message", message)
    }, status)

    private fun authenticate(request: HttpRequest): PairedController {
        val header = request.headers["authorization"].orEmpty()
        return pairingAuth.authenticate(header.removePrefix("Bearer ").takeIf { header.startsWith("Bearer ") }.orEmpty())
            ?: throw RendererException("unauthorized", "Pair with this renderer again.", 401)
    }

    private fun status(): RendererStatus = (playback?.status() ?: RendererStatus()).withIdentity()
    private fun RendererStatus.withIdentity() = copy(rendererId = this@EndpointServer.rendererId, name = this@EndpointServer.name.take(64))
    private fun json(value: Any, status: Int = 200): HttpResponse = HttpResponse(status,
        mapOf("Content-Type" to "application/json; charset=utf-8", "Cache-Control" to "no-store"),
        RendererJson.gson.toJson(value).toByteArray(Charsets.UTF_8))
}
