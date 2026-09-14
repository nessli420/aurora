package com.aurora.music.playback

import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AUDIO_TIME_UNSET
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.PrecisionConvolver
import com.aurora.music.playback.engine.PrecisionDspCoeffBuilder
import com.aurora.music.playback.engine.PrecisionDspCoefficients
import com.aurora.music.playback.engine.PrecisionEffectsKernel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/**
 * Stereo production block pipeline: effects -> convolution -> makeup, entirely binary64.
 * Single playback-thread owner for configure/queue/output/EOS/flush/reset. Controls may update
 * concurrently. Input is copied only when queueInput returns true and is never modified. A false
 * result means the caller must drain getOutput and retry the same input (or wait for IR preparation).
 * Output is take-once and owned here: copy it before the next mutating method call.
 *
 * At most 256 accepted input frames plus 1024 output frames are staged. Convolution's own bounded
 * partition buffers collect up to 1024 frames. Live IR changes finish previously accepted input
 * and emit its valid partial block before switching. EOS truncates the IR tail at input duration.
 * queueInput/getOutput allocate nothing, take no locks and do no file I/O.
 */
class PrecisionBlockProcessor {
    @Volatile var enabled: Boolean = false
    @Volatile var convolutionEnabled: Boolean = false
    @Volatile var processingActive: Boolean = false
        private set
    @Volatile var convolutionProcessingActive: Boolean = false
        private set
    @Volatile private var makeupLinear = 1.0

    private data class EffectsControl(
        val params: DspParams = DspParams(), val rate: Int = 0,
        val coefficients: PrecisionDspCoefficients? = null,
    )
    private val effectsControl = AtomicReference(EffectsControl())
    private var effects: PrecisionEffectsKernel? = null
    private var format: AudioStreamFormat? = null
    private var stage: AudioBlock? = null
    private var output: AudioBlock? = null
    private var stagePosition = 0
    private var stageCount = 0
    private var stageEngine: PrecisionConvolver? = null
    private var outputReady = false
    private var endOfStream = false
    private var convolutionTimeUs = AUDIO_TIME_UNSET
    private var convolutionFramePosition = AUDIO_TIME_UNSET

    private val controlLock = Any()
    private var rawImpulse: ImpulseResponse? = null
    private var activeRate = 0
    private var workerRunning = false
    private data class Request(val impulse: ImpulseResponse, val rate: Int)
    private data class Completion(val request: Request, val engine: PrecisionConvolver?, val failure: String?)
    @Volatile private var requested: Request? = null
    @Volatile private var completed: Completion? = null
    private var active: Completion? = null

    val preparationState: ConvolutionPreparationState get() {
        val request = requested ?: return ConvolutionPreparationState.IDLE
        val result = completed
        return if (result?.request !== request) ConvolutionPreparationState.PREPARING
            else if (result.engine != null) ConvolutionPreparationState.READY else ConvolutionPreparationState.FAILED
    }
    val preparationFailure: String? get() {
        val request = requested
        val result = completed
        return if (result?.request === request) result?.failure else null
    }
    val hasPendingData: Boolean get() = outputReady || stageCount > stagePosition ||
        (active?.engine?.bufferedInputFrames ?: 0) > 0 || (active?.engine?.availableOutputFrames ?: 0) > 0
    val isEnded: Boolean get() = endOfStream && !hasPendingData

    fun update(params: DspParams) {
        val snapshot = params.copy(graphic = params.graphic.copyOf(), graphicFreqs = params.graphicFreqs.copyOf(),
            parametric = params.parametric.toList())
        while (true) {
            val previous = effectsControl.get()
            val coefficients = if (previous.rate > 0) PrecisionDspCoeffBuilder.build(snapshot, previous.rate) else null
            if (effectsControl.compareAndSet(previous, EffectsControl(snapshot, previous.rate, coefficients))) return
        }
    }

    fun setMakeup(db: Float) { makeupLinear = if (db.isFinite()) 10.0.pow(db.toDouble() / 20.0) else 1.0 }

    fun setImpulse(impulse: ImpulseResponse?, makeupDb: Float) {
        setMakeup(makeupDb)
        synchronized(controlLock) {
            rawImpulse = impulse
            requestLocked(activeRate)
        }
    }

    /** Configuration is a preparation boundary; the output owner must first drain the old format. */
    fun configure(sampleRate: Int) {
        require(sampleRate in 8_000..768_000)
        check(!hasPendingData) { "Drain the previous precision format before configuring a new one" }
        flush()
        val nextFormat = AudioStreamFormat(sampleRate, ChannelLayout.STEREO)
        format = nextFormat
        effects = PrecisionEffectsKernel(nextFormat)
        stage = AudioBlock(nextFormat, INPUT_FRAMES)
        output = AudioBlock(nextFormat, OUTPUT_FRAMES)
        while (true) {
            val previous = effectsControl.get()
            val coefficients = PrecisionDspCoeffBuilder.build(previous.params, sampleRate)
            if (effectsControl.compareAndSet(previous, previous.copy(rate = sampleRate, coefficients = coefficients))) break
        }
        synchronized(controlLock) {
            activeRate = sampleRate
            requestLocked(sampleRate)
        }
    }

    fun queueInput(block: AudioBlock): Boolean {
        require(block.format == format && block.frameCount <= INPUT_FRAMES)
        if (block.frameCount == 0) return true
        if (endOfStream || outputReady || stageCount > stagePosition) return false
        val current = active
        val request = requested
        // getOutput owns old-IR transition draining; never process an input twice during retries.
        if (current != null && (!convolutionEnabled || current.request !== request)) return false
        if ((current?.engine?.availableOutputFrames ?: 0) > 0) return false
        val candidate = if (convolutionEnabled && request != null && current == null) completed else null
        if (convolutionEnabled && request != null && current == null && candidate?.request !== request) return false

        if (candidate?.engine != null) {
            candidate.engine.reset()
            active = candidate
        }
        val owned = checkNotNull(stage)
        owned.begin(block.frameCount, block.presentationTimeUs, block.firstFramePosition)
        System.arraycopy(block.samples, 0, owned.samples, 0, block.sampleCount)
        val useEffects = enabled
        if (useEffects) checkNotNull(effects).process(owned, checkNotNull(effectsControl.get().coefficients))
        processingActive = useEffects
        stagePosition = 0; stageCount = block.frameCount
        stageEngine = if (convolutionEnabled) active?.engine else null
        convolutionProcessingActive = stageEngine != null
        feedStage()
        return true
    }

    fun getOutput(): AudioBlock? {
        if (!outputReady) {
            // Already accepted input keeps its original convolver even if controls just changed.
            val engine = active?.engine
            if (engine != null && engine.availableOutputFrames > 0) emit(engine)
            if (!outputReady && stageCount > stagePosition) feedStage()
            if (!outputReady && stageCount == stagePosition) {
                val current = active
                if (current != null && (endOfStream || !convolutionEnabled || current.request !== requested)) {
                    current.engine!!.queueEndOfInput()
                    emit(current.engine)
                    if (current.engine.isEnded) {
                        active = null
                        if (!endOfStream) convolutionProcessingActive = false
                    }
                }
            }
        }
        if (!outputReady) return null
        outputReady = false
        return output
    }

    /** Feed only already-owned input. A full convolver block may leave at most 255 staged frames. */
    private fun feedStage() {
        if (outputReady || stagePosition == stageCount) return
        val owned = checkNotNull(stage)
        val engine = stageEngine
        if (engine == null) {
            val target = checkNotNull(output)
            val count = stageCount - stagePosition
            target.begin(count, timeAt(owned.presentationTimeUs, stagePosition), frameAt(owned.firstFramePosition, stagePosition))
            System.arraycopy(owned.samples, stagePosition * 2, target.samples, 0, count * 2)
            stagePosition = stageCount
            outputReady = true
            return
        }
        if (engine.availableOutputFrames > 0) { emit(engine); return }
        if (engine.bufferedInputFrames == 0) {
            convolutionTimeUs = timeAt(owned.presentationTimeUs, stagePosition)
            convolutionFramePosition = frameAt(owned.firstFramePosition, stagePosition)
        }
        stagePosition += engine.queueInput(owned.samples, stagePosition, stageCount - stagePosition)
        emit(engine)
    }

    private fun emit(engine: PrecisionConvolver) {
        if (outputReady) return
        val target = checkNotNull(output)
        val count = engine.readOutput(target.samples, 0, OUTPUT_FRAMES)
        if (count == 0) return
        target.begin(count, convolutionTimeUs, convolutionFramePosition)
        val makeup = makeupLinear
        var i = 0
        while (i < count * 2) { target.samples[i] *= makeup; i++ }
        outputReady = true
        convolutionTimeUs = AUDIO_TIME_UNSET
        convolutionFramePosition = AUDIO_TIME_UNSET
    }

    fun queueEndOfStream() { endOfStream = true }

    /** Seek drops pending samples and histories but keeps the prepared format and controls. */
    fun flush() {
        effects?.reset()
        active?.engine?.reset()
        active = null
        stagePosition = 0; stageCount = 0; stageEngine = null
        outputReady = false; endOfStream = false
        processingActive = false; convolutionProcessingActive = false
        convolutionTimeUs = AUDIO_TIME_UNSET; convolutionFramePosition = AUDIO_TIME_UNSET
    }

    fun reset() {
        flush()
        format = null; effects = null; stage = null; output = null
        while (true) {
            val previous = effectsControl.get()
            if (effectsControl.compareAndSet(previous, previous.copy(rate = 0, coefficients = null))) break
        }
        synchronized(controlLock) { activeRate = 0; requested = null; completed = null }
    }

    private fun timeAt(timeUs: Long, offset: Int): Long =
        if (timeUs == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else timeUs + offset * 1_000_000L / checkNotNull(format).sampleRate
    private fun frameAt(frame: Long, offset: Int): Long = if (frame == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else frame + offset

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
                val impulse = request.impulse
                require(impulse.sampleRate in 8_000..768_000) { "Unsupported impulse-response sample rate" }
                val left = resample(impulse.preciseLeft, impulse.sampleRate, request.rate)
                val right = resample(impulse.preciseRight, impulse.sampleRate, request.rate)
                Completion(request, PrecisionConvolver(left, right, OUTPUT_FRAMES), null)
            } catch (failure: Exception) {
                Completion(request, null, failure.message ?: "Impulse response could not be prepared")
            }
            synchronized(controlLock) {
                if (requested === request) { completed = result; workerRunning = false; return }
            }
        }
    }

    companion object {
        const val INPUT_FRAMES = 256
        const val OUTPUT_FRAMES = 1024
        private val PREPARATION = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Aurora-precision-IR").apply { isDaemon = true }
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
    }
}
