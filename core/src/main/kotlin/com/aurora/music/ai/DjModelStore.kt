package com.aurora.music.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

sealed interface DjModelState {
    data object Missing : DjModelState
    data class Downloading(val bytes: Long, val total: Long) : DjModelState
    data object Verifying : DjModelState
    data object Ready : DjModelState
    data class Failed(val reason: String) : DjModelState
}

class DjModelStore(private val dir: File, private val http: OkHttpClient) {
    companion object {
        const val NAME = "Qwen3.5 0.8B (Q4_0)"
        const val LICENSE = "Apache-2.0"
        const val BYTES = 563_036_064L
        const val SHA256 = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf"
        const val URL = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/807dfd9a6fadbf703207aac3bfb0b2b3040d077c/Qwen3.5-0.8B-Q4_0.gguf"
        const val FAIL_WIFI = "wifi"
        const val FAIL_NETWORK = "network"
        const val FAIL_SPACE = "space"
        const val FAIL_CHECKSUM = "checksum"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(dir, "qwen3.5-0.8b-q4_0.gguf")
    private val partial = File(dir, "model.part")
    private val _state = MutableStateFlow(if (file.length() == BYTES) DjModelState.Ready else DjModelState.Missing)
    val state: StateFlow<DjModelState> = _state.asStateFlow()
    private var job: Job? = null

    fun model(): File? = file.takeIf { _state.value == DjModelState.Ready && it.length() == BYTES }

    fun bytes(): Long = (dir.listFiles() ?: emptyArray()).sumOf { it.length() }

    // allowed is polled while downloading so leaving wi-fi pauses instead of spending mobile data
    fun download(allowed: () -> Boolean = { true }) {
        if (job?.isActive == true || _state.value == DjModelState.Ready) return
        if (!allowed()) { _state.value = DjModelState.Failed(FAIL_WIFI); return }
        job = scope.launch {
            try { _state.value = fetch(allowed) } catch (e: CancellationException) {
                _state.value = DjModelState.Missing; throw e
            } catch (_: IOException) { _state.value = DjModelState.Failed(FAIL_NETWORK) }
        }
    }

    fun cancel() { job?.cancel() }

    suspend fun delete(release: suspend () -> Unit) {
        job?.cancel()
        job?.join()
        release()
        dir.listFiles()?.forEach { it.delete() }
        _state.value = DjModelState.Missing
    }

    private suspend fun fetch(allowed: () -> Boolean): DjModelState {
        dir.mkdirs()
        var have = partial.length().takeIf { it in 1..BYTES } ?: 0L
        if (dir.usableSpace < BYTES - have + 64_000_000) return DjModelState.Failed(FAIL_SPACE)
        if (have < BYTES) {
            val request = Request.Builder().url(URL).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return DjModelState.Failed(FAIL_NETWORK)
                if (response.code != 206) have = 0
                val body = response.body ?: return DjModelState.Failed(FAIL_NETWORK)
                FileOutputStream(partial, have > 0).use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(1 shl 16)
                    var reported = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        have += n
                        if (have > BYTES) { partial.delete(); return DjModelState.Failed(FAIL_CHECKSUM) }
                        out.write(buffer, 0, n)
                        if (have - reported >= 2_000_000) {
                            reported = have
                            _state.value = DjModelState.Downloading(have, BYTES)
                            if (!allowed()) return DjModelState.Failed(FAIL_WIFI)
                        }
                    }
                }
            }
        }
        if (have != BYTES) return DjModelState.Failed(FAIL_NETWORK)
        _state.value = DjModelState.Verifying
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != SHA256) { partial.delete(); return DjModelState.Failed(FAIL_CHECKSUM) }
        if (!partial.renameTo(file)) return DjModelState.Failed(FAIL_SPACE)
        return DjModelState.Ready
    }
}
