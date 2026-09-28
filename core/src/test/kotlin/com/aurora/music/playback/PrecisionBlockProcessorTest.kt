package com.aurora.music.playback

import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class PrecisionBlockProcessorTest {
    private val format = AudioStreamFormat(48_000, ChannelLayout.STEREO)
    private val neutral = DspParams(limiterEnabled = false, graphicFreqs = floatArrayOf(24_000f))

    @Test fun bypassPreservesBinary64SamplesAndOutputIsTakenOnce() {
        val processor = PrecisionBlockProcessor()
        processor.configure(48_000)
        val input = block(doubleArrayOf(1.0 + 2.0.pow(-40), -2.0.pow(-80), 2.0, -3.0), 42)
        val original = input.samples.copyOf()
        assertTrue(processor.queueInput(input))
        assertArrayEquals(original, input.samples, 0.0)
        assertTrue(processor.hasPendingData)
        val output = requireNotNull(processor.getOutput())
        assertEquals(42L, output.firstFramePosition)
        assertArrayEquals(original.copyOf(input.sampleCount), output.samples.copyOf(output.sampleCount), 0.0)
        assertNull(processor.getOutput())
        assertFalse(processor.hasPendingData)
        assertFalse(processor.processingActive)
        processor.queueEndOfStream(); assertTrue(processor.isEnded)
        processor.reset()
    }

    @Test fun effectsAndConvolutionKeepQuietSamplesWithoutIntermediateQuantization() {
        val processor = prepared(floatArrayOf(1f, 0.5f), floatArrayOf(0.5f, -0.25f)).apply {
            enabled = true; update(neutral.copy(preampDb = -6f)); setMakeup(3f)
        }
        val input = DoubleArray(1_907 * 2) { ((it % 17) - 8) * 2.0.pow(-30) }
        val gain = 10.0.pow(-6.0 / 20.0) * 10.0.pow(3.0 / 20.0)
        val output = stream(processor, input, 227, tailFrames = 1)
        output.indices.forEach { i ->
            val current = input.getOrElse(i) { 0.0 }
            val previous = input.getOrElse(i - 2) { 0.0 }
            val expected = if (i % 2 == 0) current + previous * 0.5
                else current * 0.5 - previous * 0.25
            assertEquals(expected * gain, output[i], 2e-21)
        }
        assertTrue(output.any { it != 0.0 })
        assertTrue(processor.processingActive)
        processor.reset()
    }

    @Test fun aRejectedInputDoesNotAdvanceEffectsOrChangeCallerSamples() {
        val processor = PrecisionBlockProcessor().apply { enabled = true; update(neutral.copy(delayLeftMs = 1f)) }
        processor.configure(48_000)
        val first = block(DoubleArray(120) { 0.25 }, 0)
        val next = block(DoubleArray(24) { -0.5 }, 60)
        assertTrue(processor.queueInput(first))
        val before = next.samples.copyOf()
        repeat(3) { assertFalse(processor.queueInput(next)); assertArrayEquals(before, next.samples, 0.0) }
        val firstOutput = requireNotNull(processor.getOutput()).samples.copyOf(120)
        repeat(48) { assertEquals(0.0, firstOutput[it * 2], 0.0) }
        assertTrue(processor.queueInput(next))
        val nextOutput = requireNotNull(processor.getOutput())
        repeat(12) { assertEquals(0.25, nextOutput.samples[it * 2], 0.0) }
        processor.reset()
    }

    @Test fun oddInputSizesCrossConvolverBoundariesWithoutLossOrMetadataDrift() {
        val processor = prepared(floatArrayOf(1f), floatArrayOf(1f))
        val input = DoubleArray(7_013 * 2) { ((it % 101) - 50) / 128.0 }
        val output = stream(processor, input, 253)
        assertArrayEquals(input, output, 2e-14)
        assertTrue(processor.isEnded)
        processor.reset()
    }

    @Test fun replacingImpulseDrainsTheOldPartialBlockBeforeAcceptingNewInput() {
        val processor = prepared(floatArrayOf(1f), floatArrayOf(1f))
        val old = block(DoubleArray(146) { 0.25 }, 100)
        assertTrue(processor.queueInput(old)); assertNull(processor.getOutput())
        processor.setImpulse(ImpulseResponse(floatArrayOf(2f), floatArrayOf(2f), 48_000), 0f)
        awaitPrepared(processor)
        val next = block(DoubleArray(38) { -0.25 }, 173)
        assertFalse(processor.queueInput(next))
        val oldOutput = requireNotNull(processor.getOutput())
        assertEquals(73, oldOutput.frameCount); assertEquals(100L, oldOutput.firstFramePosition)
        repeat(oldOutput.sampleCount) { assertEquals(0.25, oldOutput.samples[it], 1e-15) }
        assertTrue(processor.queueInput(next))
        processor.queueEndOfStream()
        val nextOutput = requireNotNull(processor.getOutput())
        assertEquals(19, nextOutput.frameCount); assertEquals(173L, nextOutput.firstFramePosition)
        repeat(nextOutput.sampleCount) { assertEquals(-0.5, nextOutput.samples[it], 1e-15) }
        assertNull(processor.getOutput()); assertTrue(processor.isEnded)
        processor.reset()
    }

    @Test fun disablingConvolutionPreservesPreviouslyAcceptedPartialAudio() {
        val processor = prepared(floatArrayOf(2f), floatArrayOf(2f))
        assertTrue(processor.queueInput(block(DoubleArray(26) { 0.25 }, 0)))
        processor.convolutionEnabled = false
        val dry = block(DoubleArray(18) { 0.25 }, 13)
        assertFalse(processor.queueInput(dry))
        val old = requireNotNull(processor.getOutput())
        assertEquals(13, old.frameCount)
        repeat(old.sampleCount) { assertEquals(0.5, old.samples[it], 1e-15) }
        assertTrue(processor.queueInput(dry))
        val next = requireNotNull(processor.getOutput())
        assertEquals(9, next.frameCount)
        repeat(next.sampleCount) { assertEquals(0.25, next.samples[it], 0.0) }
        assertFalse(processor.convolutionProcessingActive)
        processor.reset()
    }

    @Test fun failedPreparationFallsBackToDryAudioAndExplainsTheFailure() {
        val processor = PrecisionBlockProcessor().apply { convolutionEnabled = true }
        processor.setImpulse(ImpulseResponse(floatArrayOf(), floatArrayOf(), 48_000), 0f)
        processor.configure(48_000)
        awaitPrepared(processor, ConvolutionPreparationState.FAILED)
        assertTrue(requireNotNull(processor.preparationFailure).contains("empty"))
        val input = block(doubleArrayOf(2.0.pow(-50), -2.0.pow(-50)), 0)
        assertTrue(processor.queueInput(input))
        val output = requireNotNull(processor.getOutput())
        assertArrayEquals(input.samples.copyOf(2), output.samples.copyOf(2), 0.0)
        assertFalse(processor.convolutionProcessingActive)
        processor.reset()
    }

    @Test fun seekClearsEffectsAndConvolutionHistoryWhileRetainingPreparedControls() {
        val processor = prepared(floatArrayOf(1f, 0.5f, 0.25f), floatArrayOf(1f)).apply {
            enabled = true; update(neutral.copy(delayLeftMs = 2f, crossfeed = 0.7f))
        }
        assertTrue(processor.queueInput(block(DoubleArray(200) { 0.5 }, 0)))
        assertTrue(processor.hasPendingData)
        processor.flush()
        assertFalse(processor.hasPendingData)
        assertFalse(processor.processingActive)
        assertEquals(ConvolutionPreparationState.READY, processor.preparationState)
        val output = stream(processor, DoubleArray(2_046), 255, tailFrames = 2)
        assertTrue(output.all { it == 0.0 })
        processor.reset()
    }

    @Test fun latestImpulseRequestWinsAndAFormatChangeRepreparesItsRate() {
        val processor = PrecisionBlockProcessor().apply { convolutionEnabled = true }
        processor.configure(48_000)
        processor.setImpulse(ImpulseResponse(FloatArray(8_192) { 1f }, FloatArray(8_192) { 1f }, 48_000), 0f)
        processor.setImpulse(ImpulseResponse(floatArrayOf(3f), floatArrayOf(3f), 48_000), 0f)
        awaitPrepared(processor)
        assertArrayEquals(doubleArrayOf(0.75, -0.75), stream(processor, doubleArrayOf(0.25, -0.25), 1), 1e-15)
        processor.configure(96_000)
        awaitPrepared(processor)
        assertEquals(ConvolutionPreparationState.READY, processor.preparationState)
        processor.queueEndOfStream(); assertTrue(processor.isEnded)
        processor.reset()
    }

    private fun prepared(left: FloatArray, right: FloatArray): PrecisionBlockProcessor = PrecisionBlockProcessor().also {
        it.convolutionEnabled = true
        it.setImpulse(ImpulseResponse(left, right, 48_000), 0f)
        it.configure(48_000)
        awaitPrepared(it)
    }

    private fun awaitPrepared(processor: PrecisionBlockProcessor, state: ConvolutionPreparationState = ConvolutionPreparationState.READY) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (processor.preparationState == ConvolutionPreparationState.PREPARING && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(processor.preparationFailure, state, processor.preparationState)
    }

    private fun block(samples: DoubleArray, firstFrame: Long): AudioBlock = AudioBlock(format, samples.size / 2).apply {
        begin(samples.size / 2, 1_000_000 + firstFrame * 1_000_000L / 48_000, firstFrame)
        samples.copyInto(this.samples)
    }

    private fun stream(processor: PrecisionBlockProcessor, input: DoubleArray, chunk: Int, tailFrames: Int = 0): DoubleArray {
        val result = ArrayList<Double>()
        var position = 0
        var iterations = 0
        fun drain() {
            while (true) {
                val output = processor.getOutput() ?: return
                assertTrue(output.frameCount <= PrecisionBlockProcessor.OUTPUT_FRAMES)
                val expectedFrame = result.size / 2L
                assertEquals(expectedFrame, output.firstFramePosition)
                val expectedTime = 1_000_000 + expectedFrame * 1_000_000L / 48_000
                assertTrue("timestamp ${output.presentationTimeUs} expected $expectedTime", abs(output.presentationTimeUs - expectedTime) <= 1)
                repeat(output.sampleCount) { result += output.samples[it] }
            }
        }
        while (position < input.size) {
            check(iterations++ < 10_000) { "Pipeline stalled" }
            drain()
            val count = minOf(chunk * 2, input.size - position)
            val owned = block(input.copyOfRange(position, position + count), position / 2L)
            if (processor.queueInput(owned)) position += count
        }
        processor.queueEndOfStream()
        while (!processor.isEnded) { check(iterations++ < 10_000) { "EOS stalled" }; drain() }
        drain()
        assertEquals(input.size + tailFrames * 2, result.size)
        return result.toDoubleArray()
    }
}
