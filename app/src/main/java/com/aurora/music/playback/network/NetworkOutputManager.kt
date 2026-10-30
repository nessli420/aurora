package com.aurora.music.playback.network

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.playback.network.endpoint.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class NetworkOutputState(
    val processedCast: Boolean = false,
    val receiverName: String? = null,
    val receiverKind: String? = null,
    val preparing: Boolean = false,
    val detail: String = "",
    val error: String? = null,
    val scanning: Boolean = false,
    val dlna: List<DlnaRenderer> = emptyList(),
    val discovered: List<DiscoveredRenderer> = emptyList(),
    val paired: List<PairedRenderer> = emptyList(),
    val receiverEnabled: Boolean = false,
    val receiverAddress: EndpointAddress? = null,
    val receiverAddresses: List<String> = emptyList(),
    val pairing: PairingWindow? = null,
    val controllers: List<PairedController> = emptyList(),
    val controlling: PairedRenderer? = null,
    val probed: ProbedDevice? = null,
)

data class ProbedDevice(val address: String, val fingerprint: String, val known: Boolean)

// hosts answer in generic english, so the problems a user can fix get local text that names the device
fun ConnectSession.problem(): String? {
    val message = error.value ?: return null
    return when (errorCode) {
        "not_available" -> appString(R.string.connect_error_unavailable, name)
        "connect_disabled" -> appString(R.string.connect_error_disabled, name)
        "unauthorized", "certificate_mismatch" -> appString(R.string.connect_error_unpaired, name)
        "renderer_unreachable" -> appString(R.string.connect_error_unreachable, name)
        else -> message
    }
}

sealed interface NetworkTarget {
    data class Dlna(val renderer: DlnaRenderer) : NetworkTarget
    data class Aurora(val renderer: PairedRenderer) : NetworkTarget
    data object Cast : NetworkTarget
}

interface NetworkOutputHost {
    fun connect(target: NetworkTarget)
    fun disconnect()
    fun enableReceiver(enabled: Boolean)
    fun pairReceiver()
    fun revokeController(id: String)
    fun control(peer: PairedRenderer?)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkOutputManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("network-output", Context.MODE_PRIVATE)
    private val trust = RendererTrustStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(NetworkOutputState(
        processedCast = preferences.getBoolean("processed-cast", false), paired = trust.renderers()))
    val state = mutable.asStateFlow()
    private var host: NetworkOutputHost? = null
    private var pending: ((NetworkOutputHost) -> Unit)? = null
    private val mutableSession = MutableStateFlow<ConnectSession?>(null)
    val session = mutableSession.asStateFlow()
    private var scanJob: Job? = null
    private var scanGeneration = 0L

    fun update(transform: (NetworkOutputState) -> NetworkOutputState) { mutable.value = transform(mutable.value) }
    fun attach(value: NetworkOutputHost) { host = value; pending?.invoke(value); pending = null }
    fun detach(value: NetworkOutputHost) {
        if (host !== value) return
        host = null
        update { it.copy(receiverName = null, receiverKind = null, preparing = false, receiverEnabled = false, receiverAddress = null, pairing = null, controllers = emptyList()) }
    }
    private fun dispatch(foreground: Boolean = false, action: (NetworkOutputHost) -> Unit) {
        val active = host
        if (active != null) { action(active); return }
        pending = action
        val intent = Intent(context, com.aurora.music.playback.PlaybackService::class.java)
        if (foreground) intent.action = "com.aurora.music.action.RECEIVER_START"
        try {
            if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        } catch (_: Exception) { pending = null; update { it.copy(error = "Open Aurora before starting network playback.") } }
    }
    fun setProcessedCast(value: Boolean) {
        if (mutable.value.processedCast == value) return
        preferences.edit().putBoolean("processed-cast", value).apply()
        update { it.copy(processedCast = value) }
        if (mutable.value.receiverKind == "Cast") connect(NetworkTarget.Cast)
    }
    fun connect(target: NetworkTarget) = dispatch { it.connect(target) }
    fun disconnect() = dispatch { it.disconnect() }
    fun enableReceiver(enabled: Boolean) {
        preferences.edit().putBoolean("receiver", enabled).apply()
        dispatch(enabled) { it.enableReceiver(enabled) }
    }

    // receiver mode survives restarts, but only starts while aurora is in front, so the foreground service is allowed
    fun restoreReceiver() {
        if (preferences.getBoolean("receiver", false) && !mutable.value.receiverEnabled) dispatch(true) { it.enableReceiver(true) }
    }
    fun beginPairing() = dispatch { it.pairReceiver() }
    fun revokeController(id: String) = dispatch { it.revokeController(id) }
    fun dismissError() = update { it.copy(error = null) }

    fun scan() {
        val generation = ++scanGeneration
        scanJob?.cancel()
        scanJob = scope.launch {
            update { it.copy(scanning = true, error = null) }
            val discovery = RendererDiscovery(context)
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock = wifi.createMulticastLock("aurora-discovery").apply { setReferenceCounted(false) }
            var observer: Job? = null
            try {
                lock.acquire()
                discovery.start()
                observer = launch { discovery.renderers.collect { found ->
                    val known = trust.renderers()
                    known.forEach { peer -> found.firstOrNull { it.id == peer.id && it.address != peer.address }?.let { fresh ->
                        trust.saveRenderer(peer.copy(address = fresh.address))
                    } }
                    update { it.copy(discovered = found, paired = trust.renderers()) }
                } }
                val devices = withContext(Dispatchers.IO) { DlnaClient().discover() }
                update { it.copy(dlna = devices) }
                delay(3000)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { update { it.copy(error = "Could not search this network.") } }
            finally { observer?.cancel(); discovery.stop(); if (lock.isHeld) lock.release(); if (generation == scanGeneration) update { it.copy(scanning = false) } }
        }
    }

    fun pair(address: String, code: String) {
        scope.launch {
            update { it.copy(preparing = true, error = null) }
            try {
                val endpoint = EndpointAddress.parse(address)
                val paired = withContext(Dispatchers.IO) { RendererClient(endpoint).use { it.pair(android.os.Build.MODEL, code) } }
                trust.saveRenderer(paired)
                update { it.copy(paired = trust.renderers()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error = if (e is RendererException) e.message else "Pairing failed. Check the address and code.") } }
            finally { update { it.copy(preparing = false) } }
        }
    }

    fun forget(id: String) {
        if (mutable.value.controlling?.id == id) control(null)
        trust.forgetRenderer(id); update { it.copy(paired = trust.renderers()) }
    }

    fun control(peer: PairedRenderer?) {
        if (peer?.id == mutable.value.controlling?.id) return
        dispatch { it.control(peer) }
    }

    fun openSession(peer: PairedRenderer): ConnectSession {
        mutableSession.value?.close()
        val fresh = trust.renderers().firstOrNull { it.id == peer.id } ?: peer
        return ConnectSession(fresh, scope).also { session ->
            mutableSession.value = session
            update { state -> state.copy(controlling = fresh, error = null) }
            scope.launch {
                session.lost.first { it }
                if (mutableSession.value !== session) return@launch
                val message = session.problem() ?: appString(R.string.connect_error_lost, session.name)
                control(null)
                update { it.copy(error = message) }
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun closeSession() {
        mutableSession.value?.close()
        mutableSession.value = null
        update { it.copy(controlling = null) }
    }

    // the device is free to pick again at once, even while the old session still moves playback back
    fun release(session: ConnectSession?) {
        if (session == null || mutableSession.value !== session) return
        mutableSession.value = null
        update { it.copy(controlling = null) }
    }

    fun command(command: ConnectCommand) { mutableSession.value?.post(command) }

    fun probe(input: String) {
        scope.launch {
            update { it.copy(preparing = true, error = null, probed = null) }
            try {
                val address = withContext(Dispatchers.IO) { ConnectPairing.probe(input) }
                val known = trust.renderers().firstOrNull { it.fingerprint == address.fingerprint }
                known?.let { trust.saveRenderer(ConnectPairing.withAddress(it, address.address)) }
                update { it.copy(probed = ProbedDevice(address.address, address.fingerprint, known != null), paired = trust.renderers()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error = (e as? RendererException)?.message ?: "Cannot reach that device.") } }
            finally { update { it.copy(preparing = false) } }
        }
    }

    fun pairDiscovered(found: DiscoveredRenderer, code: String) {
        scope.launch {
            update { it.copy(preparing = true, error = null) }
            try {
                val paired = withContext(Dispatchers.IO) { ConnectPairing.pairDiscovered(found, code, android.os.Build.MODEL) }
                trust.saveRenderer(paired)
                update { it.copy(paired = trust.renderers()) }
                control(paired)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(error = if (e is RendererException) e.message else "Pairing failed. Check the code.") } }
            finally { update { it.copy(preparing = false) } }
        }
    }

    fun pairProbed(code: String) {
        val probed = mutable.value.probed ?: return
        pair(EndpointAddress(probed.address, probed.fingerprint).pairingAddress, code)
        update { it.copy(probed = null) }
    }

    fun dismissProbe() = update { it.copy(probed = null) }
}
