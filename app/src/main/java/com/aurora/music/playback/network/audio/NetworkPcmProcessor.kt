package com.aurora.music.playback.network.audio

import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.BandlimitedResampler
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.ProductionSerialRack

class NetworkPcmProcessor(
    private val graph: ProductionSerialRack,
    private val writer: NetworkWaveWriter,
    private val outputGain: Double = 1.0,
    encoderDelay: Int = 0,
    private val encoderPadding: Int = 0,
    private val checkCancelled: () -> Unit = {},
) {
    private val input = AudioBlock(graph.format, ProductionSerialRack.INPUT_FRAMES)
    private val converted = AudioBlock(AudioStreamFormat(writer.sampleRate, ChannelLayout.STEREO), 4096)
    private val resampler = if (graph.format.sampleRate == writer.sampleRate) null
        else BandlimitedResampler(graph.format.sampleRate, writer.sampleRate)
    private val held: DoubleArray
    private var head = encoderDelay
    private var heldRead = 0
    private var heldFrames = 0
    private var ended = false
    var sourceFrames: Long = 0; private set
    val graphLatencyFrames: Int get() = graph.latencyFrames
    val graphTailFrames: Int get() = graph.tailFrames
    val resamplerLatencyFrames: Int get() = resampler?.lookaheadFrames ?: 0

    init {
        require(encoderDelay in 0..131072 && encoderPadding in 0..131072)
        require(outputGain.isFinite() && outputGain in 0.0..1.0)
        require(graph.convolutionUnavailableReason == null) { "An impulse response is unavailable." }
        held = DoubleArray((encoderPadding + input.capacityFrames) * 2)
    }

    fun append(block: AudioBlock) {
        check(!ended)
        require(block.format.sampleRate == graph.format.sampleRate && block.format.channelCount in 1..2)
        val channels = block.format.channelCount
        val capacity = held.size / 2
        for (frame in 0 until block.frameCount) {
            if (head > 0) { head--; continue }
            val index = (heldRead + heldFrames) % capacity
            val left = block.samples[frame * channels]
            val right = block.samples[frame * channels + channels - 1]
            require(left.isFinite() && right.isFinite()) { "The source contains invalid audio samples." }
            held[index * 2] = left; held[index * 2 + 1] = right
            heldFrames++
            if (heldFrames == capacity) submitHeld()
        }
        submitHeld()
    }

    private fun submitHeld() {
        val count = minOf(input.capacityFrames, heldFrames - encoderPadding)
        if (count <= 0) return
        checkCancelled()
        input.begin(count, sourceFrames * 1_000_000L / graph.format.sampleRate, sourceFrames)
        val capacity = held.size / 2
        for (frame in 0 until count) {
            val index = (heldRead + frame) % capacity
            input.samples[frame * 2] = held[index * 2]
            input.samples[frame * 2 + 1] = held[index * 2 + 1]
        }
        while (!graph.queueInput(input)) check(drainGraph()) { "Network processing stalled." }
        heldRead = (heldRead + count) % capacity
        heldFrames -= count
        sourceFrames += count
        drainGraph()
    }

    private fun drainGraph(): Boolean {
        var progressed = false
        while (true) {
            checkCancelled()
            val output = graph.getOutput() ?: break
            val converter = resampler
            if (converter == null) writer.write(output, outputGain)
            else {
                var position = 0
                while (position < output.frameCount) {
                    checkCancelled()
                    val accepted = converter.queueInput(output.samples, position, output.frameCount - position)
                    position += accepted
                    val emitted = drainResampler()
                    check(accepted > 0 || emitted) { "Network resampling stalled." }
                }
            }
            progressed = true
        }
        return progressed
    }

    private fun drainResampler(): Boolean {
        val converter = checkNotNull(resampler)
        var progressed = false
        while (true) {
            checkCancelled()
            val frames = converter.readOutput(converted.samples, 0, converted.capacityFrames)
            if (frames == 0) return progressed
            converted.begin(frames)
            writer.write(converted, outputGain)
            progressed = true
        }
    }

    fun finish() {
        check(!ended)
        checkCancelled()
        while (heldFrames > encoderPadding) submitHeld()
        heldFrames = 0
        graph.queueEndOfStream()
        while (!graph.isEnded) check(drainGraph() || graph.isEnded) { "Network processing did not finish." }
        resampler?.let {
            it.queueEndOfInput()
            while (!it.isEnded) check(drainResampler() || it.isEnded) { "Network resampling did not finish." }
        }
        writer.finish()
        ended = true
    }
}
