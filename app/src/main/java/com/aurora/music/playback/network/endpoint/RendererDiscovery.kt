package com.aurora.music.playback.network.endpoint

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val SERVICE_TYPE = "_aurora-music._tcp."

data class DiscoveredRenderer(val id: String, val name: String, val address: String)

internal class RendererAdvertisement(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start(name: String, rendererId: String, port: Int) {
        stop()
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(service: NsdServiceInfo, error: Int) = Unit
            override fun onServiceUnregistered(service: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(service: NsdServiceInfo, error: Int) = Unit
        }
        val info = NsdServiceInfo().apply {
            serviceName = "${name.take(40)} ${rendererId.take(6)}"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("v", RENDERER_PROTOCOL_VERSION.toString())
            setAttribute("id", rendererId)
            setAttribute("name", name.take(64))
            setAttribute("role", "renderer")
        }
        if (runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }.isSuccess) listener = registration
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }
}

class RendererDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val mutableRenderers = MutableStateFlow<List<DiscoveredRenderer>>(emptyList())
    val renderers: StateFlow<List<DiscoveredRenderer>> = mutableRenderers.asStateFlow()
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolved = linkedMapOf<String, DiscoveredRenderer>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var generation = 0

    @Synchronized
    fun start() {
        if (discovery != null) return
        val current = ++generation
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { stop() }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(this@RendererDiscovery) {
                    if (current != generation || pending.size >= 32) return
                    if (service.serviceType.trim('.') != SERVICE_TYPE.trim('.')) return
                    pending.addLast(service)
                    resolveNext(current)
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(this@RendererDiscovery) {
                    if (current != generation) return
                    resolved.remove(service.serviceName)
                    mutableRenderers.value = resolved.values.toList()
                }
            }
        }
        discovery = listener
        if (runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isFailure) stop()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(current: Int) {
        if (resolving || pending.isEmpty() || current != generation) return
        resolving = true
        val service = pending.removeFirst()
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) = finished(null)
            override fun onServiceResolved(info: NsdServiceInfo) = finished(info)
            private fun finished(info: NsdServiceInfo?) {
                synchronized(this@RendererDiscovery) {
                    if (current != generation) return
                    resolving = false
                    val host = info?.host?.hostAddress?.takeUnless { it.contains('%') }
                    val version = info?.attributes?.get("v")?.toString(Charsets.UTF_8)
                    val id = info?.attributes?.get("id")?.toString(Charsets.UTF_8).orEmpty()
                    val name = info?.attributes?.get("name")?.toString(Charsets.UTF_8).orEmpty().take(64)
                    if (host != null && info.port in 1..65535 && version == "1" && id.matches(Regex("[a-f0-9]{32}"))) {
                        val urlHost = if (':' in host) "[$host]" else host
                        if (resolved.size < 32 || resolved.containsKey(info.serviceName)) {
                            resolved[info.serviceName] = DiscoveredRenderer(id, name, "https://$urlHost:${info.port}")
                            mutableRenderers.value = resolved.values.toList()
                        }
                    }
                    resolveNext(current)
                }
            }
        }
        if (runCatching { nsd.resolveService(service, resolver) }.isFailure) resolver.onResolveFailed(service, -1)
    }

    @Synchronized
    fun stop() {
        ++generation
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        pending.clear()
        resolved.clear()
        resolving = false
        mutableRenderers.value = emptyList()
    }
}
