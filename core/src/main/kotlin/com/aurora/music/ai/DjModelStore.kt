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

data class DjModelPart(val file: String, val bytes: Long, val sha256: String, val url: String)

data class DjModelSpec(val name: String, val parts: List<DjModelPart>) {
    val bytes: Long get() = parts.sumOf { it.bytes }
}

class DjModelStore(private val dir: File, private val http: OkHttpClient, val spec: DjModelSpec = QWEN) {
    companion object {
        const val NAME = "Qwen3.5 0.8B (Q4_0)"
        const val LICENSE = "Apache-2.0"
        const val BYTES = 563_036_064L
        const val SHA256 = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf"
        const val URL = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/807dfd9a6fadbf703207aac3bfb0b2b3040d077c/Qwen3.5-0.8B-Q4_0.gguf"
        val QWEN = DjModelSpec(NAME, listOf(DjModelPart("qwen3.5-0.8b-q4_0.gguf", BYTES, SHA256, URL)))
        val WHISPER = DjModelSpec("Whisper base (q5_1)", listOf(DjModelPart("ggml-base-q5_1.bin", 59_707_625L,
            "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base-q5_1.bin")))
        val SPEAKER = "https://huggingface.co/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323".let { root ->
            DjModelSpec("Supertonic 3", listOf(
                DjModelPart("duration_predictor.onnx", 3700147L, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db", "$root/onnx/duration_predictor.onnx"),
                DjModelPart("text_encoder.onnx", 36416150L, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff", "$root/onnx/text_encoder.onnx"),
                DjModelPart("vector_estimator.onnx", 256534781L, "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c", "$root/onnx/vector_estimator.onnx"),
                DjModelPart("vocoder.onnx", 101424195L, "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba", "$root/onnx/vocoder.onnx"),
                DjModelPart("tts.json", 8253L, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09", "$root/onnx/tts.json"),
                DjModelPart("unicode_indexer.json", 277676L, "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f", "$root/onnx/unicode_indexer.json"),
                DjModelPart("F1.json", 292046L, "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2", "$root/voice_styles/F1.json"),
                DjModelPart("F2.json", 292423L, "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6", "$root/voice_styles/F2.json"),
                DjModelPart("F3.json", 290794L, "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab", "$root/voice_styles/F3.json"),
                DjModelPart("F4.json", 291808L, "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b", "$root/voice_styles/F4.json"),
                DjModelPart("F5.json", 291479L, "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d", "$root/voice_styles/F5.json"),
                DjModelPart("LICENSE", 15007L, "0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f", "$root/LICENSE"),
            ))
        }
        const val FAIL_WIFI = "wifi"
        const val FAIL_NETWORK = "network"
        const val FAIL_SPACE = "space"
        const val FAIL_CHECKSUM = "checksum"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(if (complete()) DjModelState.Ready else DjModelState.Missing)
    val state: StateFlow<DjModelState> = _state.asStateFlow()
    private var job: Job? = null

    private fun complete() = spec.parts.all { File(dir, it.file).length() == it.bytes }

    // the first part, the rest sit next to it
    fun model(): File? = File(dir, spec.parts.first().file).takeIf { _state.value == DjModelState.Ready && complete() }

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
        File(dir, "model.part").delete()
        val todo = spec.parts.filter { File(dir, it.file).length() != it.bytes }
        val needed = todo.sumOf { it.bytes - partial(it).length().coerceAtMost(it.bytes) }
        if (dir.usableSpace < needed + 64_000_000) return DjModelState.Failed(FAIL_SPACE)
        var finished = spec.bytes - todo.sumOf { it.bytes }
        for (part in todo) {
            fetchPart(part, finished, allowed)?.let { return it }
            finished += part.bytes
        }
        return DjModelState.Ready
    }

    private fun partial(part: DjModelPart) = File(dir, "${part.file}.part")

    private suspend fun fetchPart(part: DjModelPart, finished: Long, allowed: () -> Boolean): DjModelState? {
        val partial = partial(part)
        var have = partial.length().takeIf { it in 1..part.bytes } ?: 0L
        if (have < part.bytes) {
            val request = Request.Builder().url(part.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
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
                        if (have > part.bytes) { partial.delete(); return DjModelState.Failed(FAIL_CHECKSUM) }
                        out.write(buffer, 0, n)
                        if (have - reported >= 2_000_000) {
                            reported = have
                            _state.value = DjModelState.Downloading(finished + have, spec.bytes)
                            if (!allowed()) return DjModelState.Failed(FAIL_WIFI)
                        }
                    }
                }
            }
        }
        if (have != part.bytes) return DjModelState.Failed(FAIL_NETWORK)
        _state.value = DjModelState.Verifying
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != part.sha256) { partial.delete(); return DjModelState.Failed(FAIL_CHECKSUM) }
        if (!partial.renameTo(File(dir, part.file))) return DjModelState.Failed(FAIL_SPACE)
        return null
    }
}
