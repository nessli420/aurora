package com.aurora.music.playback.network.endpoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ConnectSession(
    val peer: PairedRenderer,
    private val scope: CoroutineScope,
    private val lostAfterMs: Long = 15_000,
) : AutoCloseable {
    private val clients = (listOf(peer.address) + peer.addresses).distinct().map { RendererClient(peer.copy(address = it)) }
    @Volatile private var client = clients.first()
    private val mutableStatus = MutableStateFlow<ConnectStatus?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableConnected = MutableStateFlow(false)
    private val mutableLost = MutableStateFlow(false)
    val status: StateFlow<ConnectStatus?> = mutableStatus.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    val lost: StateFlow<Boolean> = mutableLost.asStateFlow()
    @Volatile var errorCode: String? = null
        private set
    val name: String get() = mutableStatus.value?.name?.ifBlank { null } ?: peer.name
    @Volatile private var receivedAt = 0L
    @Volatile private var tag: String? = null
    @Volatile private var commands = 0L
    private val sending = Mutex()
    private val job: Job = scope.launch { poll() }

    // the clock only runs while the other device is answering
    fun positionMs(): Long {
        val current = mutableStatus.value ?: return 0
        val elapsed = if (current.playing && mutableConnected.value) ((System.nanoTime() - receivedAt) / 1_000_000 * current.speed).toLong() else 0L
        val position = current.positionMs + elapsed
        return if (current.durationMs > 0) position.coerceAtMost(current.durationMs) else position
    }

    private suspend fun poll() {
        var failures = 0
        var failingSince = 0L
        while (true) {
            val before = commands
            try {
                val (status, next) = client.connectStatus(tag, positionMs())
                // a command answered while this poll was out carries newer state, so ask again instead
                if (before == commands) apply(status, next) else tag = null
                failures = 0
                failingSince = 0L
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failures++
                tag = null
                mutableConnected.value = false
                if (failingSince == 0L) failingSince = System.currentTimeMillis()
                val code = (e as? RendererException)?.code
                val fatal = code in FATAL
                if (!fatal && clients.size > 1) client = clients[(clients.indexOf(client) + 1) % clients.size]
                if (fatal || failures >= clients.size) fail(e, "Lost the connection.")
                if (fatal || System.currentTimeMillis() - failingSince >= lostAfterMs) {
                    mutableLost.value = true
                    return
                }
                delay((failures * 1_000L).coerceAtMost(5_000))
            }
        }
    }

    suspend fun send(command: ConnectCommand): Boolean = try {
        val (status, next) = sending.withLock { client.connectCommand(command) }
        commands++
        apply(status, next)
        true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { fail(e, "The command didn't reach the device."); false }

    // move hands the queue over, pause only when the other device has nothing of its own queued
    suspend fun handOff(behaviour: Int, play: ConnectCommand.Play?): Boolean {
        if (play == null || behaviour == ConnectSwitch.KEEP) return true
        val remote = withTimeoutOrNull(lostAfterMs) { status.filterNotNull().first() } ?: return true
        if (behaviour == ConnectSwitch.PAUSE && remote.queue.isNotEmpty()) return true
        return send(play)
    }

    fun post(command: ConnectCommand) { scope.launch { send(command) } }

    fun artwork(id: String): ByteArray? = client.artwork(id)

    fun clearError() { mutableError.value = null }

    private fun fail(e: Exception, fallback: String) {
        errorCode = (e as? RendererException)?.code ?: "renderer_unreachable"
        mutableError.value = e.message ?: fallback
    }

    private fun apply(status: ConnectStatus, next: String) {
        receivedAt = System.nanoTime()
        tag = next
        errorCode = null
        mutableError.value = null
        mutableStatus.value = status
        mutableConnected.value = true
    }

    override fun close() {
        job.cancel()
        clients.forEach { it.close() }
    }

    private companion object {
        val FATAL = setOf("unauthorized", "connect_disabled", "certificate_mismatch", "unsupported_version")
    }
}
