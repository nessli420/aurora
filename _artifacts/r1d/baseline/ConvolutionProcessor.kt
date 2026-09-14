package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.aurora.music.playback.engine.PrecisionConvolver
import com.aurora.music.playback.engine.SamplePrecision
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.pow

/** Float-array construction stays compatible; WAV decoding retains its original PCM precision. */
class ImpulseResponse private constructor(
    internal val preciseLeft: DoubleArray,
    internal val preciseRight: DoubleArray,
    val sampleRate: Int,
    val sourcePrecision: SamplePrecision,
    val sourceValidBits: Int
) {
    constructor(left: FloatArray, right: FloatArray, sampleRate: Int) : this(
        DoubleArray(left.size) { left[it].toDouble() },
        DoubleArray(right.size) { right[it].toDouble() }, sampleRate, SamplePrecision.FLOAT_32, 24
    )
    // Compatibility views; processing always uses the owned binary64 samples above.
    val left: FloatArray get() = FloatArray(preciseLeft.size) { preciseLeft[it].toFloat() }
    val right: FloatArray get() = FloatArray(preciseRight.size) { preciseRight[it].toFloat() }
    val frameCount: Int get() = maxOf(preciseLeft.size, preciseRight.size)

    companion object {
        internal fun decoded(left: DoubleArray, right: DoubleArray, rate: Int, precision: SamplePrecision, validBits: Int) =
            ImpulseResponse(left, right, rate, precision, validBits)
    }
}

enum class ConvolutionPreparationState { IDLE, PREPARING, READY, FAILED }

/**
 * PCM16 Media3 adapter around the binary64 kernel. Output retains exactly the input duration;
 * reverb beyond EOS is deliberately truncated for existing gapless/queue timing. A pending IR
 * applies only between blocks, after any valid old partial block is emitted. Preparation uses
 * a shared worker, with one replaceable request per instance; the playback callback never waits
 * on that worker, takes a lock, allocates buffers or performs file I/O. It applies backpressure
 * until the requested IR is ready. Preparation failure is visible and falls back to dry audio.
 */
@UnstableApi
class ConvolutionProcessor : AudioProcessor {
    @Volatile var enabled: Boolean = false
    @Volatile private var makeupLinear = 1.0
    private val controlLock = Any()
    private var rawImpulse: ImpulseResponse? = null
    @Volatile private var activeRate = 0
    @Volatile private var requested: Request? = null
    @Volatile private var completed: Completion? = null
    private var workerRunning = false
    private data class Request(val impulse: ImpulseResponse, val rate: Int)
    private data class Completion(val request: Request, val engine: PrecisionConvolver?, val failure: String?)
    @Volatile private var active: Completion? = null // Mutated only by the playback thread.
    private var pendingFormat = AudioFormat.NOT_SET
    private var format = AudioFormat.NOT_SET
    private val pcm = ByteBuffer.allocateDirect(BLOCK_FRAMES * 4).order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
    private val samples = DoubleArray(BLOCK_FRAMES * 2)
    private var output: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var endOfStream = false

    val preparationState: ConvolutionPreparationState get() {
        val request = requested ?: return ConvolutionPreparationState.IDLE
        val result = completed
        return if (result?.request !== request) ConvolutionPreparationState.PREPARING
        else if (result.engine != null) ConvolutionPreparationState.READY else ConvolutionPreparationState.FAILED
    }
    val preparationFailure: String? get() = completed?.takeIf { it.request === requested }?.failure
    val impulseSourcePrecision: SamplePrecision? get() = requested?.impulse?.sourcePrecision
    val processingActive: Boolean get() = enabled && activeRate > 0 && active?.engine != null && active?.request === requested

    fun setMakeup(db: Float) { makeupLinear = if (db.isFinite()) 10.0.pow(db.toDouble() / 20.0) else 1.0 }

    fun setImpulse(ir: ImpulseResponse?, makeupDb: Float) {
        setMakeup(makeupDb)
        synchronized(controlLock) {
            rawImpulse = ir
            requestLocked(activeRate)
        }
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        pendingFormat = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2)
            inputAudioFormat else AudioFormat.NOT_SET
        // Initial negotiation can prepare early. A pending new format must not disturb an old
        // stream still draining: that request starts when flush activates the new format.
        if (activeRate == 0 && pendingFormat != AudioFormat.NOT_SET) {
            synchronized(controlLock) { requestLocked(pendingFormat.sampleRate) }
        }
        return pendingFormat
    }

    override fun isActive(): Boolean = pendingFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining() || pcm.hasRemaining() || endOfStream) return
        require(format != AudioFormat.NOT_SET && inputBuffer.remaining() % 4 == 0)
        val request = requested
        val old = active
        if (old != null && (!enabled || old.request !== request)) {
            // Finish the old partial block once, without padded samples, before replacing it.
            if (old.engine!!.bufferedInputFrames > 0) {
                old.engine.queueEndOfInput()
                emit(old.engine)
                active = null
                return
            }
            active = null
        }
        if (enabled && request != null && active == null) {
            val candidate = completed
            if (candidate?.request !== request) return // bounded, nonblocking backpressure
            if (candidate.engine != null) {
                candidate.engine.reset()
                active = candidate
            }
        }
        val engine = if (enabled) active?.engine else null
        if (engine == null) {
            val bytes = minOf(inputBuffer.remaining(), pcm.capacity())
            pcm.clear()
            val oldLimit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + bytes)
            pcm.put(inputBuffer)
            inputBuffer.limit(oldLimit)
            pcm.flip(); output = pcm
            return
        }
        val frames = minOf(inputBuffer.remaining() / 4, BLOCK_FRAMES - engine.bufferedInputFrames)
        var i = 0
        while (i < frames * 2) {
            val low = inputBuffer.get().toInt() and 0xff
            val high = inputBuffer.get().toInt()
            samples[i++] = ((high shl 8) or low) / 32768.0
        }
        check(engine.queueInput(samples, 0, frames) == frames)
        emit(engine)
    }

    private fun emit(engine: PrecisionConvolver) {
        val count = engine.readOutput(samples, 0, BLOCK_FRAMES)
        if (count == 0) return
        pcm.clear()
        val makeup = makeupLinear
        var i = 0
        while (i < count * 2) {
            val value = samples[i++] * makeup
            // Keep the established PCM16 adapter boundary while the kernel itself stays binary64.
            val quantized = if (!value.isFinite()) 0 else Math.floor(value * 32767.0 + 0.5).coerceIn(-32768.0, 32767.0).toInt()
            pcm.putShort(quantized.toShort())
        }
        pcm.flip(); output = pcm
    }

    override fun queueEndOfStream() {
        endOfStream = true
        active?.engine?.queueEndOfInput()
        if (!pcm.hasRemaining()) active?.engine?.let { emit(it) }
    }
    override fun getOutput(): ByteBuffer {
        if (!pcm.hasRemaining() && endOfStream) active?.engine?.let { emit(it) }
        val result = output
        output = AudioProcessor.EMPTY_BUFFER
        return result
    }
    override fun isEnded(): Boolean = endOfStream && !pcm.hasRemaining() && (active?.engine?.isEnded != false)

    override fun flush() {
        active?.engine?.reset()
        active = null
        format = pendingFormat
        activeRate = if (format == AudioFormat.NOT_SET) 0 else format.sampleRate
        output = AudioProcessor.EMPTY_BUFFER
        pcm.clear().limit(0)
        endOfStream = false
        synchronized(controlLock) { requestLocked(activeRate) }
    }
    override fun reset() {
        active?.engine?.reset()
        active = null
        pendingFormat = AudioFormat.NOT_SET; format = AudioFormat.NOT_SET; activeRate = 0
        output = AudioProcessor.EMPTY_BUFFER; pcm.clear().limit(0); endOfStream = false
        synchronized(controlLock) { requested = null; completed = null }
    }

    private fun requestLocked(rate: Int) {
        val impulse = rawImpulse
        if (impulse == null || rate <= 0) { requested = null; completed = null; return }
        val previous = requested
        if (previous?.impulse === impulse && previous.rate == rate) return
        requested = Request(impulse, rate)
        completed = null
        if (!workerRunning) {
            workerRunning = true
            PREPARATION.execute { prepareLatest() }
        }
    }
    private fun prepareLatest() {
        while (true) {
            val request = synchronized(controlLock) {
                requested ?: run { workerRunning = false; return }
            }
            val result = try {
                val ir = request.impulse
                require(ir.sampleRate in 8_000..768_000 && request.rate in 8_000..768_000) { "Unsupported impulse-response sample rate" }
                val left = resample(ir.preciseLeft, ir.sampleRate, request.rate)
                val right = resample(ir.preciseRight, ir.sampleRate, request.rate)
                Completion(request, PrecisionConvolver(left, right, BLOCK_FRAMES), null)
            } catch (failure: Exception) {
                Completion(request, null, failure.message ?: "Impulse response could not be prepared")
            }
            synchronized(controlLock) {
                if (requested === request) {
                    completed = result
                    workerRunning = false
                    return
                }
            }
        }
    }

    companion object {
        const val BLOCK_FRAMES = 1024
        private val PREPARATION = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Aurora-IR-prepare").apply { isDaemon = true }
        }
        private fun resample(source: DoubleArray, sourceRate: Int, targetRate: Int): DoubleArray {
            require(source.isNotEmpty()) { "Impulse response is empty" }
            val ratio = targetRate.toDouble() / sourceRate
            val length = (source.size * ratio).toLong().coerceAtLeast(1)
            require(length <= PrecisionConvolver.MAX_IR_FRAMES) {
                "Impulse response exceeds ${PrecisionConvolver.MAX_IR_FRAMES} frames per channel at $targetRate Hz"
            }
            require(source.all { it.isFinite() }) { "Impulse response contains non-finite samples" }
            if (sourceRate == targetRate) return source
            return DoubleArray(length.toInt()) { index ->
                val position = index / ratio
                val first = position.toInt()
                val fraction = position - first
                val a = source.getOrElse(first) { 0.0 }
                val b = source.getOrElse(first + 1) { a }
                a + (b - a) * fraction
            }
        }

        const val MAX_DECODED_IR_FRAMES = 1_048_576

        fun loadWav(file: File): ImpulseResponse? = loadWavResult(file).getOrNull()

        /** Bounded streaming import: at most 16 MiB decoded stereo samples plus a 64 KiB buffer. */
        fun loadWavResult(file: File): Result<ImpulseResponse> = runCatching {
            require(file.length() in 44..64L * 1024 * 1024) { "Impulse-response WAV must be between 44 bytes and 64 MiB" }
            java.io.RandomAccessFile(file, "r").use { input ->
                fun intLe(): Int = Integer.reverseBytes(input.readInt())
                fun shortLe(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                require(intLe() == 0x46464952) { "Impulse response is not a RIFF WAV" }
                val riffEnd = (intLe().toLong() and 0xffffffffL) + 8
                require(riffEnd == input.length() && intLe() == 0x45564157) { "Invalid WAV container length" }
                var code = 0; var channels = 0; var rate = 0; var bits = 0; var validBits = 0; var alignment = 0
                var dataOffset = -1L; var dataLength = 0L; var hasFormat = false
                while (input.filePointer + 8 <= riffEnd) {
                    val chunk = intLe()
                    val length = intLe().toLong() and 0xffffffffL
                    val start = input.filePointer
                    val end = start + length
                    require(end <= riffEnd) { "WAV chunk exceeds the container" }
                    when (chunk) {
                        0x20746d66 -> {
                            require(!hasFormat && length >= 16) { "Invalid or duplicate WAV format chunk" }
                            code = shortLe(); channels = shortLe(); rate = intLe()
                            val byteRate = intLe().toLong() and 0xffffffffL
                            alignment = shortLe(); bits = shortLe()
                            validBits = bits
                            if (code == 0xfffe) {
                                require(length >= 40) { "Incomplete extensible WAV format" }
                                val extensionBytes = shortLe()
                                require(extensionBytes >= 22 && extensionBytes.toLong() <= length - 18) { "Invalid extensible WAV format size" }
                                validBits = shortLe()
                                intLe() // Channel mask: the mono/stereo channel count owns routing.
                                val subFormat = intLe()
                                require(intLe() == 0x00100000 && intLe() == 0xaa000080.toInt() && intLe() == 0x719b3800 && subFormat in intArrayOf(1, 3)) {
                                    "Unsupported extensible WAV subformat"
                                }
                                code = subFormat
                            }
                            require(channels in 1..2 && rate in 8_000..768_000 &&
                                (code == 1 && bits in listOf(8, 16, 24, 32) || code == 3 && bits == 32) &&
                                validBits in 1..bits && (code != 3 || validBits == 32) &&
                                alignment == channels * (bits / 8) && byteRate == rate.toLong() * alignment) {
                                "Unsupported WAV format; use mono/stereo integer PCM or float32"
                            }
                            hasFormat = true
                        }
                        0x61746164 -> {
                            require(dataOffset < 0) { "Duplicate WAV data chunk" }
                            dataOffset = start; dataLength = length
                        }
                    }
                    val next = end + (length and 1)
                    require(next <= riffEnd) { "Missing WAV chunk padding" }
                    input.seek(next)
                }
                require(input.filePointer == riffEnd) { "Incomplete trailing WAV chunk" }
                require(hasFormat && dataOffset >= 0 && dataLength > 0 && dataLength % alignment == 0L) { "Missing or misaligned WAV samples" }
                val frames = dataLength / alignment
                require(frames <= MAX_DECODED_IR_FRAMES) { "Impulse response exceeds $MAX_DECODED_IR_FRAMES source frames per channel" }
                val left = DoubleArray(frames.toInt()); val right = DoubleArray(frames.toInt())
                val bytes = ByteArray(64 * 1024)
                val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                fun sample(): Double = when {
                    code == 3 -> data.float.toDouble()
                    bits == 8 -> ((data.get().toInt() and 0xff) - 128) / 128.0
                    bits == 16 -> data.short / 32768.0
                    bits == 24 -> {
                        val low = data.get().toInt() and 0xff; val mid = data.get().toInt() and 0xff
                        val high = data.get().toInt()
                        ((high shl 16) or (mid shl 8) or low) / 8388608.0
                    }
                    else -> data.int / 2147483648.0
                }
                input.seek(dataOffset)
                var offset = 0
                while (offset < frames) {
                    val count = minOf(bytes.size / alignment, frames.toInt() - offset)
                    input.readFully(bytes, 0, count * alignment)
                    data.clear().limit(count * alignment)
                    repeat(count) {
                        val l = sample(); val r = if (channels == 2) sample() else l
                        require(l.isFinite() && r.isFinite()) { "Impulse response contains non-finite samples" }
                        left[offset] = l; right[offset] = r
                        offset++
                    }
                }
                val precision = when {
                    code == 3 -> SamplePrecision.FLOAT_32
                    bits == 32 -> SamplePrecision.PCM_SIGNED_32
                    bits == 24 -> SamplePrecision.PCM_SIGNED_24
                    bits == 8 -> SamplePrecision.PCM_SIGNED_8
                    else -> SamplePrecision.PCM_SIGNED_16
                }
                ImpulseResponse.decoded(left, right, rate, precision, if (code == 3) 24 else validBits)
            }
        }
    }
}
