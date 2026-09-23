package com.aurora.music.playback.engine

import com.aurora.music.data.ProcessingRack
import com.aurora.music.playback.ConvolutionPreparationState
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.ImpulseResponse
import java.util.concurrent.Executors

private class RackOutputDelay(private val format: AudioStreamFormat) {
    private val capacity = format.sampleRate * 2 + 1
    private val history = DoubleArray(capacity * 2)
    private val tail = AudioBlock(format, ProductionSerialRack.OUTPUT_FRAMES)
    private var position = 0
    var frames = 0; private set
    private var previousFrames = 0
    private var changePosition = 0
    private var changeFrames = 0
    private var seenInput = false
    private var remainingTail = -1
    private var time = AUDIO_TIME_UNSET
    private var frame = AUDIO_TIME_UNSET
    val isEnded get() = remainingTail == 0
    fun setFrames(value: Int, fadeFrames: Int = 0) {
        require(value in 0 until capacity)
        previousFrames = frames; frames = value; changePosition = 0; changeFrames = fadeFrames
    }
    fun process(block: AudioBlock) {
        seenInput = seenInput || block.frameCount > 0
        for (i in 0 until block.frameCount) {
            val blend = if (changeFrames == 0 || changePosition >= changeFrames) 1.0 else (++changePosition).toDouble().div(changeFrames).coerceAtMost(1.0)
            val read = (position - frames + capacity) % capacity
            val oldRead = (position - previousFrames + capacity) % capacity
            for (channel in 0..1) {
                history[position * 2 + channel] = block.samples[i * 2 + channel]
                block.samples[i * 2 + channel] = history[oldRead * 2 + channel] * (1 - blend) + history[read * 2 + channel] * blend
            }
            if (++position == capacity) position = 0
        }
        time = if (block.presentationTimeUs == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else block.presentationTimeUs + block.frameCount * 1_000_000L / format.sampleRate
        frame = if (block.firstFramePosition == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else block.firstFramePosition + block.frameCount
    }
    fun finish(drainTail: Boolean = true) { if (remainingTail < 0) remainingTail = if (seenInput && drainTail) frames else 0 }
    fun getTail(drainTail: Boolean): AudioBlock? {
        finish(drainTail)
        if (remainingTail == 0) return null
        val count = minOf(remainingTail, tail.capacityFrames)
        tail.begin(count, time, frame); tail.samples.fill(0.0); process(tail); remainingTail -= count
        return tail
    }
    fun reset() {
        history.fill(0.0); position = 0; previousFrames = frames; changeFrames = 0; changePosition = 0
        seenInput = false; remainingTail = -1; time = AUDIO_TIME_UNSET; frame = AUDIO_TIME_UNSET
    }
    fun copyStateFrom(previous: RackOutputDelay) {
        if (frames != previous.frames || format != previous.format) return
        previous.history.copyInto(history); position = previous.position; previousFrames = frames; changeFrames = 0
        seenInput = previous.seenInput; time = previous.time; frame = previous.frame
    }
}

// - swap graphs at natural block boundaries
// - align crossfade output by source frame
class ProductionRackProcessor {
    private data class Request(val rate: Int, val rack: ProcessingRack?, val legacy: DspParams,
        val effects: Boolean, val convolution: Boolean, val makeupDb: Float, val impulse: ImpulseResponse?, val legacyKey: List<Any>, val impulseMap: Map<String, ImpulseResponse>)
    private data class Prepared(val request: Request, val graph: ProductionSerialRack?, val failure: String?,
        val mixOutput: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.OUTPUT_FRAMES) },
        val input: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.INPUT_FRAMES) },
        val inputSlice: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.INPUT_FRAMES) },
        val fadingSlice: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.INPUT_FRAMES) },
        val outputDelay: RackOutputDelay? = graph?.let { RackOutputDelay(it.format) })
    private val lock = Any()
    private var rate = 0
    private var desiredRack: ProcessingRack? = null
    private var legacy = DspParams()
    private var effects = false
    private var convolution = false
    private var makeupDb = 0f
    private var impulse: ImpulseResponse? = null
    private var impulseMap: Map<String, ImpulseResponse> = emptyMap()
    @Volatile private var requested: Request? = null
    @Volatile private var completed: Prepared? = null
    private var workerRunning = false
    private var active: Prepared? = null
    private var fading: ProductionSerialRack? = null
    private var fadingPrepared: Prepared? = null
    private var output: AudioBlock? = null
    private var input: AudioBlock? = null
    private var inputSlice: AudioBlock? = null
    private var fadingSlice: AudioBlock? = null
    private var inputPosition = 0
    private var inputCount = 0
    private val oldOutput = SampleFifo(4096)
    private val newOutput = SampleFifo(4096)
    private var blendPosition = 0L
    private var blendFrames = 0L
    private var preserveTail = false
    private var fadeInputPosition = 0L
    private var handoverFrames = 0L
    private var outputFrames = 0L
    private var anchorTimeUs = AUDIO_TIME_UNSET
    private var anchorFrame = AUDIO_TIME_UNSET
    private var anchorSet = false
    private var ended = false
    private var drainTailOnEnd = true
    private var initialLegacyHistory: PrecisionEffectsKernel? = null
    @Volatile var relativeVolume: Double = 1.0
    val latencyFrames: Int get() = (active?.graph?.latencyFrames ?: 0) + (active?.outputDelay?.frames ?: 0)
    val tailFrames: Int get() = (active?.graph?.tailFrames ?: 0) + (active?.outputDelay?.frames ?: 0)
    val processingChangesSamples: Boolean get() = active?.graph?.processingChangesSamples == true || fading?.processingChangesSamples == true ||
        (active?.outputDelay?.frames ?: 0) > 0 || (fadingPrepared?.outputDelay?.frames ?: 0) > 0
    fun meterSnapshot(): List<RackNodeMeter> = active?.graph?.meterSnapshot() ?: emptyList()
    @Volatile var rackActive = false; private set
    @Volatile var description = "Preparing processing rack"; private set
    @Volatile var convolutionUnavailableReason: String? = null; private set
    val convolutionActive: Boolean get() = active?.graph?.convolutionActive == true
    val preparationState: ConvolutionPreparationState get() {
        val request = requested ?: return ConvolutionPreparationState.IDLE
        val result = completed
        return if (result?.request !== request) ConvolutionPreparationState.PREPARING
            else if (result.failure == null && result.graph != null) ConvolutionPreparationState.READY else ConvolutionPreparationState.FAILED
    }
    val preparationFailure: String? get() = completed?.takeIf { it.request === requested }?.failure
    val hasPendingData: Boolean get() = inputPosition < inputCount || oldOutput.frames > 0 || newOutput.frames > 0 ||
        active?.graph?.hasPendingData == true || fading?.hasPendingData == true ||
        ended && (active?.let { !fullyEnded(it) } == true || fadingPrepared?.let { !fullyEnded(it) } == true)
    val isEnded: Boolean get() = ended && !hasPendingData

    fun update(rack: ProcessingRack?, params: DspParams, enabled: Boolean, conv: Boolean, makeup: Float, ir: ImpulseResponse?, impulseMap: Map<String, ImpulseResponse> = emptyMap()) {
        synchronized(lock) {
            desiredRack = rack?.takeIf { it.enabled }
            legacy = params
            effects = enabled; convolution = conv; makeupDb = makeup; impulse = ir; this.impulseMap = impulseMap.toMap()
            requestLocked()
        }
    }

    fun configure(sampleRate: Int) {
        check(!hasPendingData)
        flush()
        output = null; input = null; inputSlice = null
        active = null
        prepareRate(sampleRate)
    }

    // - prepare without replacing the active audio graph
    fun prepareRate(sampleRate: Int) { synchronized(lock) { rate = sampleRate; requestLocked() } }

    fun preserveInitialLegacyHistory(kernel: PrecisionEffectsKernel?) { initialLegacyHistory = kernel }

    fun queueInput(block: AudioBlock): Boolean {
        if (ended || inputPosition < inputCount) return false
        applyPrepared()
        val graph = active?.graph ?: return false
        graph.relativeVolume = relativeVolume; fading?.relativeVolume = relativeVolume
        require(block.format == graph.format && block.frameCount <= ProductionSerialRack.INPUT_FRAMES)
        val owned = checkNotNull(input)
        owned.begin(block.frameCount, block.presentationTimeUs, block.firstFramePosition)
        System.arraycopy(block.samples, 0, owned.samples, 0, block.sampleCount)
        inputPosition = 0; inputCount = block.frameCount
        feedInput()
        return true
    }

    fun getOutput(): AudioBlock? {
        var iteration = 0
        while (iteration++ < 4) {
            applyPrepared()
            val graph = active?.graph ?: return null
            if (ended && inputPosition == inputCount) {
                graph.queueEndOfStream(drainTailOnEnd); fading?.queueEndOfStream(drainTailOnEnd)
            }
            if (fading == null) {
                outputOf(checkNotNull(active))?.let { return it }
            } else {
                blendOutput()?.let { return it }
            }
            // - apply edits after convolution reaches a boundary
            applyPrepared()
            if (!feedInput()) return null
        }
        return null
    }

    private fun feedInput(): Boolean {
        if (inputPosition == inputCount) return false
        val graph = active?.graph ?: return false
        val previous = fading
        if (!graph.canAcceptInput || previous?.canAcceptInput == false) return false
        if (oldOutput.frames > 2816 || newOutput.frames > 2816) return false
        val count = minOf(inputCount - inputPosition, graph.framesUntilBoundary,
            previous?.framesUntilBoundary ?: ProductionSerialRack.INPUT_FRAMES)
        val owned = checkNotNull(input); val slice = checkNotNull(inputSlice)
        val time = if (owned.presentationTimeUs == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else
            owned.presentationTimeUs + inputPosition * 1_000_000L / graph.format.sampleRate
        val frame = if (owned.firstFramePosition == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else owned.firstFramePosition + inputPosition
        slice.begin(count, time, frame)
        System.arraycopy(owned.samples, inputPosition * 2, slice.samples, 0, count * 2)
        if (previous != null && preserveTail) {
            val oldSlice = checkNotNull(fadingSlice)
            oldSlice.begin(count, time, frame)
            for (i in 0 until count) {
                val gain = ((fadeInputPosition + i + 1).toDouble() / blendFrames).coerceIn(0.0, 1.0)
                oldSlice.samples[i * 2] = slice.samples[i * 2] * (1 - gain)
                oldSlice.samples[i * 2 + 1] = slice.samples[i * 2 + 1] * (1 - gain)
                slice.samples[i * 2] *= gain; slice.samples[i * 2 + 1] *= gain
            }
            fadeInputPosition += count
            check(previous.queueInput(oldSlice))
        } else if (previous != null) check(previous.queueInput(slice))
        check(graph.queueInput(slice))
        if (previous != null && !anchorSet) {
            anchorTimeUs = time; anchorFrame = frame; anchorSet = true
        }
        inputPosition += count
        return true
    }

    private fun blendOutput(): AudioBlock? {
        val graph = checkNotNull(active?.graph)
        val previous = checkNotNull(fading)
        drainToFifo(previous, oldOutput)
        drainToFifo(graph, newOutput)
        if (ended && fullyEnded(checkNotNull(fadingPrepared)) && oldOutput.frames == 0 && newOutput.frames > 0) oldOutput.pushSilence(newOutput.frames)
        if (ended && fullyEnded(checkNotNull(active)) && newOutput.frames == 0 && oldOutput.frames > 0) {
            if (preserveTail) newOutput.pushSilence(oldOutput.frames) else { oldOutput.reset(); fading = null; fadingPrepared = null; return null }
        }
        if (ended && fullyEnded(checkNotNull(active)) && fullyEnded(checkNotNull(fadingPrepared)) && oldOutput.frames == 0 && newOutput.frames == 0) { fading = null; fadingPrepared = null; return null }
        val count = minOf(oldOutput.frames, newOutput.frames, ProductionSerialRack.OUTPUT_FRAMES)
        if (count == 0) return null
        val result = checkNotNull(output)
        result.begin(count, if (anchorTimeUs == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else anchorTimeUs + outputFrames * 1_000_000L / graph.format.sampleRate,
            if (anchorFrame == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else anchorFrame + outputFrames)
        var i = 0
        while (i < count) {
            val wet = ((blendPosition + 1).toDouble() / blendFrames).coerceIn(0.0, 1.0)
            val oldL = oldOutput.takeLeft(); val oldR = oldOutput.takeRight()
            val newL = newOutput.takeLeft(); val newR = newOutput.takeRight()
            result.samples[i * 2] = if (preserveTail) oldL + newL else oldL * (1.0 - wet) + newL * wet
            result.samples[i * 2 + 1] = if (preserveTail) oldR + newR else oldR * (1.0 - wet) + newR * wet
            blendPosition++; i++
        }
        outputFrames += count
        if (blendPosition >= handoverFrames && newOutput.frames == 0) {
            fading = null; fadingPrepared = null; oldOutput.reset()
        }
        return result
    }

    private fun drainToFifo(graph: ProductionSerialRack, fifo: SampleFifo) {
        val prepared = if (active?.graph === graph) checkNotNull(active) else checkNotNull(fadingPrepared)
        var i = 0
        while (i++ < 4 && fifo.frames <= 3072) {
            val block = outputOf(prepared) ?: return
            fifo.push(block.samples, 0, block.frameCount)
        }
    }

    private fun outputOf(prepared: Prepared): AudioBlock? {
        val graph = checkNotNull(prepared.graph); val delay = checkNotNull(prepared.outputDelay)
        val block = graph.getOutput()
        if (block != null) { delay.process(block); return block }
        return if (graph.isEnded) delay.getTail(drainTailOnEnd) else null
    }
    private fun fullyEnded(prepared: Prepared): Boolean {
        if (prepared.graph?.isEnded != true) return false
        prepared.outputDelay?.finish(drainTailOnEnd)
        return prepared.outputDelay?.isEnded == true
    }

    private fun applyPrepared() {
        val candidate = completed ?: return
        if (candidate.request !== requested || candidate.graph == null || candidate === active || ended || fading != null) return
        if (candidate.failure != null && active != null) return
        val old = active?.graph
        val oldPrepared = active
        if (old?.hasPendingData == true) return
        val inputTailHandover = old != null && old.supportsInputTailHandover && candidate.graph.supportsInputTailHandover &&
            (old.tailFrames > 0 || candidate.graph.tailFrames > 0 || (oldPrepared?.outputDelay?.frames ?: 0) > 0)
        candidate.graph.reset()
        if (old != null && !inputTailHandover) candidate.graph.copyNodeHistoriesFrom(old)
        else initialLegacyHistory?.let { candidate.graph.copyLegacyHistoryFrom(it) }
        initialLegacyHistory = null
        active = candidate
        output = candidate.mixOutput
        fadingSlice = candidate.fadingSlice
        if (input == null || input?.format != candidate.graph.format) {
            check(inputPosition == inputCount)
            input = candidate.input; inputSlice = candidate.inputSlice
        }
        rackActive = candidate.graph.explicitRack
        description = candidate.graph.description
        convolutionUnavailableReason = candidate.graph.convolutionUnavailableReason
        if (old != null) {
            fadingPrepared = oldPrepared
            fading = old; blendPosition = 0; blendFrames = (candidate.graph.format.sampleRate / 50).coerceAtLeast(1).toLong()
            val oldDelay = checkNotNull(oldPrepared?.outputDelay)
            val newDelay = checkNotNull(candidate.outputDelay)
            val commonLatency = maxOf(old.latencyFrames + oldDelay.frames, candidate.graph.latencyFrames)
            oldDelay.setFrames(commonLatency - old.latencyFrames, blendFrames.toInt())
            newDelay.setFrames(commonLatency - candidate.graph.latencyFrames)
            // - feed full level input to nonlinear stages
            preserveTail = inputTailHandover
            if (!preserveTail) newDelay.copyStateFrom(oldDelay)
            fadeInputPosition = 0
            handoverFrames = blendFrames + if (preserveTail) old.tailFrames + oldDelay.frames else 0
            outputFrames = 0; anchorTimeUs = AUDIO_TIME_UNSET; anchorFrame = AUDIO_TIME_UNSET
            anchorSet = false
            oldOutput.reset(); newOutput.reset()
        }
    }

    fun queueEndOfStream(drainTail: Boolean = true) {
        if (!ended) drainTailOnEnd = drainTail
        ended = true
        if (inputPosition == inputCount) {
            active?.graph?.queueEndOfStream(drainTailOnEnd); fading?.queueEndOfStream(drainTailOnEnd)
        }
    }
    fun flush() {
        active?.graph?.reset(); active?.outputDelay?.setFrames(0); active?.outputDelay?.reset(); fading = null; fadingPrepared = null
        oldOutput.reset(); newOutput.reset(); ended = false; drainTailOnEnd = true
        inputPosition = 0; inputCount = 0
        anchorTimeUs = AUDIO_TIME_UNSET; anchorFrame = AUDIO_TIME_UNSET; outputFrames = 0
        anchorSet = false
        initialLegacyHistory = null
    }
    fun reset() {
        flush(); active = null; output = null; input = null; inputSlice = null; rackActive = false
        convolutionUnavailableReason = null
        synchronized(lock) { rate = 0; requested = null; completed = null }
    }

    private fun requestLocked() {
        if (rate == 0) return
        val p = legacy
        val key = listOf<Any>(p.graphic.toList(), p.graphicFreqs.toList(), p.parametric,
            p.graphicQ, p.preampDb, p.balance, p.width, p.crossfeed, p.saturation,
            p.delayLeftMs, p.delayRightMs, p.trimLeftDb, p.trimRightDb, p.limiterEnabled, p.limiterCeilingDb,
            p.compEnabled, p.compThreshDb, p.compRatio, effects, convolution, makeupDb)
        val previous = requested
        if (previous != null && previous.rate == rate && previous.rack == desiredRack && previous.impulse === impulse && previous.impulseMap == impulseMap &&
            (desiredRack != null || previous.legacyKey == key)) return
        requested = Request(rate, desiredRack, legacy, effects, convolution, makeupDb, impulse, key, impulseMap)
        completed = null
        if (!workerRunning) { workerRunning = true; PREPARATION.execute { prepare() } }
    }

    private fun prepare() {
        while (true) {
            val request = synchronized(lock) { requested ?: run { workerRunning = false; return } }
            val result = try {
                Prepared(request, if (request.rack != null) ProductionSerialRack.compile(request.rack, request.rate, request.impulse, request.impulseMap)
                    else ProductionSerialRack.compileLegacy(request.rate, request.legacy, request.effects, request.convolution,
                        request.makeupDb, request.impulse), null)
            } catch (failure: Exception) {
                // - use dry convolution when impulse preparation fails
                val fallback = runCatching {
                    if (request.rack != null) ProductionSerialRack.compile(request.rack, request.rate, null)
                    else ProductionSerialRack.compileLegacy(request.rate, request.legacy, request.effects, false, 0f, null)
                }.getOrElse { ProductionSerialRack.compileLegacy(request.rate, DspParams(limiterEnabled = false), false, false, 0f, null) }
                Prepared(request, fallback, failure.message ?: "Processing rack could not be prepared")
            }
            synchronized(lock) {
                if (requested === request) { completed = result; workerRunning = false; return }
            }
        }
    }

    companion object {
        private val PREPARATION = Executors.newSingleThreadExecutor { task -> Thread(task, "Aurora-rack-prepare").apply { isDaemon = true } }
    }
}
