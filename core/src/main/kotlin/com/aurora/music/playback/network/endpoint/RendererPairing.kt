package com.aurora.music.playback.network.endpoint

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal data class ControllerTrust(val controller: PairedController, val tokenHash: String)

internal class RendererPairing(
    initial: List<ControllerTrust> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
    private val persist: (List<ControllerTrust>) -> Unit = {},
) {
    private val random = SecureRandom()
    private val trust = initial.take(8).toMutableList()
    private var window: PairingWindow? = null
    private var failures = 0

    @Synchronized
    fun begin(): PairingWindow {
        failures = 0
        return PairingWindow(random.nextInt(1_000_000).toString().padStart(6, '0'), now() + 120_000)
            .also { window = it }
    }

    @Synchronized
    fun cancel() { window = null }

    @Synchronized
    fun current(): PairingWindow? {
        if (window?.expiresAtMs?.let { now() >= it } == true) window = null
        return window
    }

    @Synchronized
    fun pair(name: String, code: String): Pair<PairedController, String> {
        val current = window
        if (current == null || now() >= current.expiresAtMs || failures >= 5) {
            window = null
            throw RendererException("pairing_closed", "Open a new pairing code on the renderer.", 403)
        }
        if (!MessageDigest.isEqual(code.toByteArray(), current.code.toByteArray())) {
            failures++
            if (failures >= 5) window = null
            throw RendererException("invalid_code", "The pairing code is incorrect.", 403)
        }
        if (trust.size >= 8) throw RendererException("client_limit", "Remove a paired controller first.", 409)
        val safeName = name.trim().take(64).filterNot(Char::isISOControl)
        if (safeName.isBlank()) throw RendererException("invalid_name", "Enter a controller name.")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val controller = PairedController(UUID.randomUUID().toString(), safeName, now())
        val updated = trust + ControllerTrust(controller, hash(token))
        persist(updated)
        trust.clear()
        trust.addAll(updated)
        window = null
        return controller to token
    }

    @Synchronized
    fun authenticate(token: String): PairedController? {
        if (token.length != 43) return null
        val digest = hash(token).toByteArray()
        return trust.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), digest) }?.controller
    }

    @Synchronized
    fun revoke(id: String) {
        val updated = trust.filterNot { it.controller.id == id }
        persist(updated)
        trust.clear()
        trust.addAll(updated)
    }

    @Synchronized
    fun clients(): List<PairedController> = trust.map { it.controller }

    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
