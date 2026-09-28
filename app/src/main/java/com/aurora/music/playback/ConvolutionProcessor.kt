package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.aurora.music.playback.engine.PrecisionConvolver
import com.aurora.music.playback.engine.SamplePrecision
import com.aurora.music.playback.engine.ConvolutionTailMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.pow

/** pcm16 adapter with worker preparation, bounded backpressure and complete eos tails. */
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
        active?.engine?.queueEndOfInput(ConvolutionTailMode.FULL)
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
                Completion(request, ir.createConvolver(request.rate, BLOCK_FRAMES), null)
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
    }
}
