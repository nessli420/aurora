package com.aurora.music.playback

import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackCodec
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AUDIO_TIME_UNSET
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.PrecisionConvolver
import com.aurora.music.playback.engine.PrecisionDspCoeffBuilder
import com.aurora.music.playback.engine.PrecisionDspCoefficients
import com.aurora.music.playback.engine.PrecisionEffectsKernel
import com.aurora.music.playback.engine.ProductionRackProcessor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/** binary64 pipeline; accepted input is copied, output is take-once, eos drains the fir tail. */
class PrecisionBlockProcessor {
    @Volatile var enabled: Boolean = false
        set(value) { field = value; notifyRack() }
    @Volatile var convolutionEnabled: Boolean = false
        set(value) { field = value; notifyRack() }
    @Volatile var processingActive: Boolean = false
        private set
    @Volatile var convolutionProcessingActive: Boolean = false
        private set
    @Volatile private var makeupLinear = 1.0
    @Volatile private var makeupDb = 0f
    @Volatile private var rackRequested: ProcessingRack? = null
    @Volatile private var rackEverRequested = false
    @Volatile private var rackManaged = false
    private val rackProcessor = ProductionRackProcessor()
    @Volatile private var rackImpulses: Map<String, ImpulseResponse> = emptyMap()
    var relativeVolume: Double
        get() = rackProcessor.relativeVolume
        set(value) { rackProcessor.relativeVolume = value.coerceIn(0.0, 1.0) }
    val rackLatencyFrames: Int get() = rackProcessor.latencyFrames
    val rackTailFrames: Int get() = rackProcessor.tailFrames
    fun rackMeters() = rackProcessor.meterSnapshot()
    fun setRackImpulses(impulses: Map<String, ImpulseResponse>) {
        rackImpulses = impulses.toMap()
        notifyRack()
    }
    val rackActive: Boolean get() = rackManaged && processingActive && rackProcessor.rackActive
    val processingChangesSamples: Boolean get() = if (rackManaged) rackProcessor.processingChangesSamples
        else processingActive || convolutionProcessingActive
    val rackDescription: String get() = if (rackManaged) rackProcessor.description else "Legacy processing"
    val convolutionUnavailableReason: String? get() = if (rackManaged) rackProcessor.convolutionUnavailableReason else null

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
    private var drainTail = true
    private var convolutionTimeUs = AUDIO_TIME_UNSET
    private var convolutionFramePosition = AUDIO_TIME_UNSET

    private val controlLock = Any()
    @Volatile private var rawImpulse: ImpulseResponse? = null
    @Volatile private var activeRate = 0
    private var workerRunning = false
    private data class Request(val impulse: ImpulseResponse, val rate: Int)
    private data class Completion(val request: Request, val engine: PrecisionConvolver?, val failure: String?)
    @Volatile private var requested: Request? = null
    @Volatile private var completed: Completion? = null
    private var active: Completion? = null

    val preparationState: ConvolutionPreparationState get() {
        if (rackEverRequested) return rackProcessor.preparationState
        val request = requested ?: return ConvolutionPreparationState.IDLE
        val result = completed
        return if (result?.request !== request) ConvolutionPreparationState.PREPARING
            else if (result.engine != null) ConvolutionPreparationState.READY else ConvolutionPreparationState.FAILED
    }
    val preparationFailure: String? get() {
        if (rackEverRequested) return rackProcessor.preparationFailure
        val request = requested
        val result = completed
        return if (result?.request === request) result?.failure else null
    }
    private val legacyHasPendingData: Boolean get() = outputReady || stageCount > stagePosition ||
        (active?.engine?.bufferedInputFrames ?: 0) > 0 || (active?.engine?.availableOutputFrames ?: 0) > 0 ||
        (endOfStream && active?.engine?.isEnded == false)
    val hasPendingData: Boolean get() = if (rackManaged) rackProcessor.hasPendingData else legacyHasPendingData
    val isEnded: Boolean get() = if (rackManaged) rackProcessor.isEnded else endOfStream && !legacyHasPendingData

    /** Disabled/null racks return to the current legacy settings through the same graph transition. */
    fun updateRack(rack: ProcessingRack?) {
        rackRequested = rack?.takeIf { it.enabled }?.let(ProcessingRackCodec::validate)
        if (rackRequested != null) rackEverRequested = true
        notifyRack()
    }

    private fun notifyRack() {
        if (!rackEverRequested) return
        rackProcessor.update(rackRequested, effectsControl.get().params, enabled, convolutionEnabled, makeupDb, rawImpulse, rackImpulses)
        val rate = activeRate
        if (rate > 0) rackProcessor.prepareRate(rate)
    }

    private fun manageRackAtBoundary() {
        if (!rackManaged && rackEverRequested && !legacyHasPendingData) {
            rackProcessor.preserveInitialLegacyHistory(effects)
            rackManaged = true
            processingActive = false; convolutionProcessingActive = false
        } else if (!rackManaged && rackEverRequested) {
            // First activation drains the old adapter's accepted partial block exactly once.
            // All later graph edits use bounded raw input slicing and matched-input blending.
            endOfStream = true
        }
    }

    fun update(params: DspParams) {
        val snapshot = params.copy(graphic = params.graphic.copyOf(), graphicFreqs = params.graphicFreqs.copyOf(),
            parametric = params.parametric.toList())
        while (true) {
            val previous = effectsControl.get()
            val coefficients = if (previous.rate > 0) PrecisionDspCoeffBuilder.build(snapshot, previous.rate) else null
            if (effectsControl.compareAndSet(previous, EffectsControl(snapshot, previous.rate, coefficients))) {
                notifyRack(); return
            }
        }
    }

    fun setMakeup(db: Float) {
        makeupDb = if (db.isFinite()) db else 0f
        makeupLinear = 10.0.pow(makeupDb.toDouble() / 20.0)
        notifyRack()
    }

    fun setImpulse(impulse: ImpulseResponse?, makeupDb: Float) {
        setMakeup(makeupDb)
        synchronized(controlLock) {
            rawImpulse = impulse
            if (!rackEverRequested) requestLocked(activeRate)
        }
        notifyRack()
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
            if (!rackEverRequested) requestLocked(sampleRate)
            else { requested = null; completed = null }
        }
        if (rackEverRequested) {
            notifyRack()
            rackProcessor.configure(sampleRate)
            rackManaged = true
        }
    }

    fun queueInput(block: AudioBlock): Boolean {
        require(block.format == format && block.frameCount <= INPUT_FRAMES)
        if (block.frameCount == 0) return true
        manageRackAtBoundary()
        if (rackManaged) {
            val accepted = rackProcessor.queueInput(block)
            if (accepted) {
                processingActive = rackProcessor.rackActive || enabled
                convolutionProcessingActive = rackProcessor.convolutionActive
            }
            return accepted
        }
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
        if (rackManaged) return rackProcessor.getOutput()
        if (rackEverRequested) endOfStream = true
        if (!outputReady) {
            // Already accepted input keeps its original convolver even if controls just changed.
            val engine = active?.engine
            if (engine != null && engine.availableOutputFrames > 0) emit(engine)
            if (!outputReady && stageCount > stagePosition) feedStage()
            if (!outputReady && stageCount == stagePosition) {
                val current = active
                if (current != null && (endOfStream || !convolutionEnabled || current.request !== requested)) {
                    current.engine!!.queueEndOfInput(if (endOfStream && drainTail && !rackEverRequested)
                        com.aurora.music.playback.engine.ConvolutionTailMode.FULL else com.aurora.music.playback.engine.ConvolutionTailMode.TRUNCATE_AT_INPUT)
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
        convolutionTimeUs = timeAt(convolutionTimeUs, count)
        convolutionFramePosition = frameAt(convolutionFramePosition, count)
    }

    fun queueEndOfStream(drainTail: Boolean = true) {
        endOfStream = true
        this.drainTail = drainTail
        if (rackManaged) rackProcessor.queueEndOfStream(drainTail)
    }

    /** Seek drops pending samples and histories but keeps the prepared format and controls. */
    fun flush() {
        effects?.reset()
        active?.engine?.reset()
        active = null
        stagePosition = 0; stageCount = 0; stageEngine = null
        outputReady = false; endOfStream = false
        drainTail = true
        processingActive = false; convolutionProcessingActive = false
        convolutionTimeUs = AUDIO_TIME_UNSET; convolutionFramePosition = AUDIO_TIME_UNSET
        rackProcessor.flush()
    }

    fun reset() {
        flush()
        format = null; effects = null; stage = null; output = null
        while (true) {
            val previous = effectsControl.get()
            if (effectsControl.compareAndSet(previous, previous.copy(rate = 0, coefficients = null))) break
        }
        synchronized(controlLock) { activeRate = 0; requested = null; completed = null }
        rackProcessor.reset(); rackManaged = false
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
                require(impulse.frameCount > 0) { "Impulse response is empty" }
                require(impulse.sampleRate in 8_000..768_000) { "Unsupported impulse-response sample rate" }
                Completion(request, impulse.createConvolver(request.rate, OUTPUT_FRAMES), null)
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
    }
}
