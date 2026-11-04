package com.aurora.music.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil

private val SPEAKER_LANGUAGES = setOf("en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi")
private val EMOJI = Regex("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F8FF}\\x{1F900}-\\x{1FAFF}\\x{1F1E6}-\\x{1F1FF}\\u2600-\\u27BF]+")
private val SPOKEN_AS_SPACE = Regex("[_\\[\\]|/#→←]")
private val SPOKEN_AS_DASH = Regex("[–‑—]")
private val SILENT = Regex("[♥☆♡©\\\\]")
private val SPACE_BEFORE_MARK = Regex(" +([,.!?;:'])")
private val RUN_OF_SPACE = Regex("\\s+")
private val ENDS_A_PHRASE = Regex("[.!?;:,'\")\\]}…。」』】〉》›»]$")
private val SENTENCE = Regex("(?<=[.!?。！？])\\s*")

// in is what android calls indonesian
fun speakerLanguage(code: String): String? = (if (code == "in") "id" else code).takeIf { it in SPEAKER_LANGUAGES }

internal fun speakerText(raw: String, language: String): String {
    val clean = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        .replace(EMOJI, "").replace(SPOKEN_AS_DASH, "-").replace(SPOKEN_AS_SPACE, " ").replace(SILENT, "")
        .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'').replace('´', '\'').replace('`', '\'')
        .replace("@", " at ").replace(SPACE_BEFORE_MARK, "$1").replace(RUN_OF_SPACE, " ").trim()
    val closed = if (ENDS_A_PHRASE.containsMatchIn(clean)) clean else "$clean."
    return "<$language>$closed</$language>"
}

internal fun speakerChunks(text: String, max: Int): List<String> {
    val chunks = ArrayList<String>()
    var current = ""
    for (sentence in text.split(SENTENCE).filter { it.isNotBlank() }) {
        if (current.isNotEmpty() && current.length + sentence.length + 1 > max) { chunks += current; current = "" }
        current = if (current.isEmpty()) sentence.take(max) else "$current $sentence"
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

internal fun waveBytes(pcm: FloatArray, rate: Int): ByteArray {
    val peak = pcm.maxOfOrNull { abs(it) }?.coerceAtLeast(0.01f) ?: 1f
    val gain = minOf(0.89f / peak, 4f)
    val data = pcm.size * 2
    val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(data)
    for (x in pcm) out.putShort((x * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort())
    return out.array()
}

// supertonic 3: reads plain text, so no phonemizer. four onnx graphs: durations, text, a denoising loop, vocoder
class DjSpeaker(private val model: () -> File?, private val threads: Int, private val style: String = "F2", private val idleMs: Long = 120_000) {
    private class Voice(
        val env: OrtEnvironment,
        val duration: OrtSession,
        val encoder: OrtSession,
        val estimator: OrtSession,
        val vocoder: OrtSession,
        val indexer: IntArray,
        val rate: Int,
        val chunk: Int,
        val latent: Int,
        val styleText: OnnxTensor,
        val styleDuration: OnnxTensor,
    ) {
        fun close() {
            styleText.close(); styleDuration.close()
            duration.close(); encoder.close(); estimator.close(); vocoder.close()
        }
    }

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = Random()
    private var voice: Voice? = null
    private var unload: Job? = null

    // false means use another voice: model missing, language it can't read, or the run failed
    suspend fun speak(text: String, language: String, out: File): Boolean {
        val lang = speakerLanguage(language) ?: return false
        return lock.withLock {
            withContext(Dispatchers.IO) {
                unload?.cancel()
                try {
                    val v = voice ?: model()?.parentFile?.let(::load)?.also { voice = it } ?: return@withContext false
                    val gap = FloatArray((v.rate * 0.3f).toInt())
                    val clips = speakerChunks(text, if (lang == "ko" || lang == "ja") 120 else 300).map { infer(v, it, lang) }
                    if (clips.isEmpty()) return@withContext false
                    val pcm = clips.reduce { all, clip -> all + gap + clip }
                    // room around the words, so a sink starting up or the next song can't touch them
                    out.writeBytes(waveBytes(FloatArray((v.rate * 0.15f).toInt()) + pcm + FloatArray((v.rate * 0.35f).toInt()), v.rate))
                    true
                } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                finally { unload = scope.launch { delay(idleMs); release() } }
            }
        }
    }

    suspend fun release() {
        lock.withLock { voice?.close(); voice = null }
    }

    private fun load(dir: File): Voice {
        val env = OrtEnvironment.getEnvironment()
        val config = JsonParser.parseString(File(dir, "tts.json").readText()).asJsonObject
        val base = config["ae"].asJsonObject["base_chunk_size"].asInt
        val compress = config["ttl"].asJsonObject["chunk_compress_factor"].asInt
        val indexer = JsonParser.parseString(File(dir, "unicode_indexer.json").readText()).asJsonArray.let { all -> IntArray(all.size()) { all[it].asInt } }
        val styles = JsonParser.parseString(File(dir, "$style.json").readText()).asJsonObject
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return options.use {
            fun session(name: String) = env.createSession(File(dir, name).absolutePath, it)
            Voice(env, session("duration_predictor.onnx"), session("text_encoder.onnx"), session("vector_estimator.onnx"), session("vocoder.onnx"),
                indexer, config["ae"].asJsonObject["sample_rate"].asInt, base * compress, config["ttl"].asJsonObject["latent_dim"].asInt * compress,
                styleTensor(env, styles["style_ttl"]), styleTensor(env, styles["style_dp"]))
        }
    }

    private fun styleTensor(env: OrtEnvironment, style: JsonElement): OnnxTensor {
        val dims = style.asJsonObject["dims"].asJsonArray.map { it.asLong }.toLongArray()
        val values = ArrayList<Float>(dims.fold(1L) { all, d -> all * d }.toInt())
        fun flatten(e: JsonElement) { if (e.isJsonArray) e.asJsonArray.forEach(::flatten) else values += e.asFloat }
        flatten(style.asJsonObject["data"])
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(values.toFloatArray()), dims)
    }

    private fun infer(v: Voice, text: String, language: String): FloatArray {
        val ids = speakerText(text, language).map { v.indexer[it.code].toLong() }.filter { it >= 0 }.toLongArray()
        val textShape = longArrayOf(1, ids.size.toLong())
        return OnnxTensor.createTensor(v.env, LongBuffer.wrap(ids), textShape).use { textIds ->
            OnnxTensor.createTensor(v.env, FloatBuffer.wrap(FloatArray(ids.size) { 1f }), longArrayOf(1, 1, ids.size.toLong())).use { textMask ->
                val seconds = v.duration.run(mapOf("text_ids" to textIds, "style_dp" to v.styleDuration, "text_mask" to textMask)).use {
                    (it[0] as OnnxTensor).floatBuffer[0]
                } / SPEED
                val samples = (seconds * v.rate).toInt()
                val frames = ceil(samples.toDouble() / v.chunk).toInt().coerceAtLeast(1)
                v.encoder.run(mapOf("text_ids" to textIds, "style_ttl" to v.styleText, "text_mask" to textMask)).use { encoded ->
                    denoise(v, encoded[0] as OnnxTensor, textMask, frames).let { vocode(v, it, frames).copyOf(samples) }
                }
            }
        }
    }

    private fun denoise(v: Voice, text: OnnxTensor, textMask: OnnxTensor, frames: Int): FloatArray {
        val shape = longArrayOf(1, v.latent.toLong(), frames.toLong())
        var latent = FloatArray(v.latent * frames) { random.nextGaussian().toFloat() }
        OnnxTensor.createTensor(v.env, FloatBuffer.wrap(FloatArray(frames) { 1f }), longArrayOf(1, 1, frames.toLong())).use { latentMask ->
            OnnxTensor.createTensor(v.env, FloatBuffer.wrap(floatArrayOf(STEPS.toFloat())), longArrayOf(1)).use { total ->
                repeat(STEPS) { step ->
                    OnnxTensor.createTensor(v.env, FloatBuffer.wrap(latent), shape).use { noisy ->
                        OnnxTensor.createTensor(v.env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1)).use { current ->
                            latent = v.estimator.run(mapOf("noisy_latent" to noisy, "text_emb" to text, "style_ttl" to v.styleText, "text_mask" to textMask,
                                "latent_mask" to latentMask, "current_step" to current, "total_step" to total)).use { (it[0] as OnnxTensor).floats() }
                        }
                    }
                }
            }
        }
        return latent
    }

    private fun vocode(v: Voice, latent: FloatArray, frames: Int): FloatArray =
        OnnxTensor.createTensor(v.env, FloatBuffer.wrap(latent), longArrayOf(1, v.latent.toLong(), frames.toLong())).use { input ->
            v.vocoder.run(mapOf("latent" to input)).use { (it[0] as OnnxTensor).floats() }
        }

    private fun OnnxTensor.floats(): FloatArray = floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }

    private companion object {
        const val STEPS = 8
        const val SPEED = 1.05f
    }
}
