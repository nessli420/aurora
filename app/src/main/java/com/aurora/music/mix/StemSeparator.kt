package com.aurora.music.mix

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import ai.onnxruntime.*
import com.aurora.music.data.AudioDecoder
import com.aurora.music.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.*

data class SeparatedStems(val vocals: String, val backing: String)

/** On-device neural separation. Audio is never uploaded; only the public model is downloaded. */
@UnstableApi
class StemSeparator(private val context: Context, private val resolve: (String) -> String?) {
    companion object {
        const val MODEL_SHA256 = "534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111"
        private const val MODEL_BYTES = 66762490L
        private const val MODEL_URL = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx"
        private const val CORE = StemSpectrogram.FRAMES - StemSpectrogram.FFT
        private const val OVERLAP = 8192
        private const val STEP = CORE - OVERLAP
    }
    private val lock = Mutex()
    private val models = File(context.filesDir, "separation-models").apply { mkdirs() }
    private val cache = File(context.cacheDir, "separated-stems-v1").apply { mkdirs() }
    private val model = File(models, "uvr-voc-ft.onnx")
    fun modelReady() = model.length() == MODEL_BYTES
    private fun key(account: String, song: Song) = sha("$account|${song.id}|${song.durationSec}|$MODEL_SHA256".toByteArray())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun cached(account: String, song: Song): SeparatedStems? {
        val id = key(account, song)
        val vocals = File(cache, "$id-vocals.wav")
        val backing = File(cache, "$id-backing.wav")
        return if (File(cache, "$id.ready").exists() && vocals.length() > 44 && backing.length() == vocals.length())
            SeparatedStems(Uri.fromFile(vocals).toString(), Uri.fromFile(backing).toString()) else null
    }

    private suspend fun ensureModel(progress: (String, Float) -> Unit): File {
        val job = currentCoroutineContext()[Job]!!
        if (modelReady()) {
            val digest = MessageDigest.getInstance("SHA-256")
            model.inputStream().use { input -> val block = ByteArray(65536); while (true) { job.ensureActive(); val n = input.read(block); if (n < 0) break; digest.update(block, 0, n) } }
            if (digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256) return model
        }
        val partial = File(models, "model.pending")
        val connection = URL(MODEL_URL).openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            connection.getInputStream().use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    job.ensureActive(); val n = input.read(buffer); if (n < 0) break
                    bytes += n; check(bytes <= MODEL_BYTES) { "Unexpected separation model size." }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    progress("Downloading vocal model · 64 MB, once", bytes.toFloat() / MODEL_BYTES)
                }
            } }
            check(bytes == MODEL_BYTES && digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256) { "The model download was incomplete. Please retry." }
            check(partial.renameTo(model)) { "Could not save the separation model." }
            return model
        } finally { partial.delete(); (connection as? java.net.HttpURLConnection)?.disconnect() }
    }

    suspend fun separate(account: String, song: Song, progress: (String, Float) -> Unit): SeparatedStems = lock.withLock {
        withContext(Dispatchers.IO) {
            cached(account, song)?.let { return@withContext it }
            require(song.durationSec in 1..1800) { "Vocal separation supports tracks up to 30 minutes." }
            check(cache.usableSpace > song.durationSec * 44100L * 12 + 128_000_000) { "Free more space before separating this track." }
            val job = currentCoroutineContext()[Job]!!
            val modelFile = ensureModel(progress)
            val id = key(account, song)
            val pcm = File(cache, "$id.pcm.pending")
            val vocalTemp = File(cache, "$id-vocals.pending")
            val backingTemp = File(cache, "$id-backing.pending")
            val vocalFile = File(cache, "$id-vocals.wav")
            val backingFile = File(cache, "$id-backing.wav")
            try {
                val uri = if (song.streamUrl.startsWith("aurora-yt:")) resolve(song.streamUrl).orEmpty() else song.streamUrl
                check(uri.isNotBlank()) { "The audio source is unavailable." }
                progress("Reading audio stream", 0f)
                decodeStereo(uri, pcm, job) { frames -> progress("Reading audio stream", (frames / 44100f / song.durationSec).coerceIn(0f, 1f)) }
                job.ensureActive()
                val frames = (pcm.length() / 4).toInt()
                check(frames > 44100) { "Not enough audio to separate." }
                val env = OrtEnvironment.getEnvironment()
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                    // Release large convolution workspaces between chunks instead of retaining a multi-GB arena.
                    options.setCPUArenaAllocator(false)
                    options.setMemoryPatternOptimization(false)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    env.createSession(modelFile.absolutePath, options).use { session ->
                        RandomAccessFile(pcm, "r").use { input ->
                            WavWriter(vocalTemp, frames).use { vocals -> WavWriter(backingTemp, frames).use { backing ->
                                val transform = StemSpectrogram()
                                var start = 0
                                var tail: Array<FloatArray>? = null
                                while (start < frames) {
                                    job.ensureActive()
                                    progress("Separating vocals on this device", start.toFloat() / frames)
                                    val source = readStereo(input, start - StemSpectrogram.TRIM, StemSpectrogram.FRAMES, frames)
                                    val spectrogram = transform.encode(source) { job.ensureActive() }
                                    val predicted = OnnxTensor.createTensor(env, FloatBuffer.wrap(spectrogram), longArrayOf(1, 4, StemSpectrogram.BINS.toLong(), StemSpectrogram.TIMES.toLong())).use { tensor ->
                                        session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                                            val output = result[0] as OnnxTensor
                                            val data = output.floatBuffer
                                            FloatArray(data.remaining()).also { data.get(it) }
                                        }
                                    }
                                    job.ensureActive()
                                    val waveform = transform.decode(predicted) { job.ensureActive() }
                                    val count = min(CORE, frames - start)
                                    val separated = Array(2) { ch -> FloatArray(count) { waveform[ch][it + StemSpectrogram.TRIM] * 1.021f } }
                                    tail?.let { old ->
                                        for (i in 0 until min(OVERLAP, count)) {
                                            val t = i.toFloat() / OVERLAP
                                            for (ch in 0..1) separated[ch][i] = old[ch][i] * (1 - t) + separated[ch][i] * t
                                        }
                                    }
                                    val last = start + count >= frames
                                    val writeCount = if (last) count else STEP
                                    val original = readStereo(input, start, writeCount, frames)
                                    vocals.write(separated, writeCount)
                                    backing.write(Array(2) { ch -> FloatArray(writeCount) { original[ch][it] - separated[ch][it] } }, writeCount)
                                    if (!last) tail = Array(2) { ch -> separated[ch].copyOfRange(STEP, CORE) }
                                    start += writeCount
                                }
                            } }
                        }
                    }
                }
                job.ensureActive()
                check(vocalTemp.renameTo(vocalFile) && backingTemp.renameTo(backingFile)) { "Could not save separated tracks." }
                File(cache, "$id.ready").writeText(MODEL_SHA256)
                progress("Vocals and backing ready", 1f)
                cached(account, song) ?: error("Separated tracks could not be read.")
            } finally { pcm.delete(); vocalTemp.delete(); backingTemp.delete() }
        }
    }

    private fun decodeStereo(uri: String, file: File, job: Job, progress: (Long) -> Unit) {
        var rate = 44100; var channels = 2; var written = 0L
        val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(44100) }
        try {
            file.outputStream().buffered().use { output ->
                fun write(buffer: ByteBuffer) {
                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); output.write(bytes); written += bytes.size / 4
                    check(written <= 1800L * 44100) { "Track exceeds separation duration limit." }
                }
                val done = AudioDecoder.decode(uri, { sr, ch ->
                    require(ch in 1..2) { "Separate a mono or stereo version of this track." }
                    rate = sr; channels = ch
                    resampler.configure(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT)); resampler.flush()
                }, { samples, length ->
                    val buffer = ByteBuffer.allocateDirect(length / channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (frame in 0 until length / channels) {
                        buffer.putShort(samples[frame * channels]); buffer.putShort(samples[frame * channels + if (channels == 1) 0 else 1])
                    }
                    buffer.flip()
                    if (rate == 44100) write(buffer) else { resampler.queueInput(buffer); write(resampler.output) }
                    progress(written)
                }, { !job.isActive }, context)
                job.ensureActive(); check(done) { "Could not finish reading this stream. Reconnect and retry." }
                if (rate != 44100) { resampler.queueEndOfStream(); write(resampler.output) }
            }
        } finally { resampler.reset() }
    }

    private fun readStereo(file: RandomAccessFile, start: Int, count: Int, frames: Int): Array<FloatArray> {
        val result = Array(2) { FloatArray(count) }
        val from = max(0, start); val end = min(frames, start + count)
        if (end <= from) return result
        val bytes = ByteArray((end - from) * 4)
        file.seek(from * 4L); file.readFully(bytes)
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in from until end) { result[0][i - start] = shorts.get() / 32768f; result[1][i - start] = shorts.get() / 32768f }
        return result
    }

    private class WavWriter(file: File, frames: Int) : Closeable {
        private val output = file.outputStream().buffered()
        init {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(2).putInt(44100).putInt(176400).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(frames * 4)
            output.write(header.array())
        }
        fun write(stereo: Array<FloatArray>, count: Int) {
            val bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) for (ch in 0..1) {
                val v = stereo[ch][i]; check(v.isFinite()) { "The model returned invalid audio." }
                bytes.putShort((v * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            output.write(bytes.array())
        }
        override fun close() = output.close()
    }
}
