package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@UnstableApi
class PrecisionConvolutionDeviceTest {
    @Test fun largeInputAndPartialEndPreserveEveryFrameWithBoundedOutput() {
        val processor = processor(floatArrayOf(1f))
        try {
            val output = ArrayList<Int>()
            val input = pcm(70_003) { frame, channel -> ((frame * 37 + channel * 17) % 24_000) - 12_000 }
            consume(processor, input, output)
            finish(processor, output)
            assertEquals(70_003 * 2, output.size)
            output.forEachIndexed { sample, value ->
                val original = ((sample / 2 * 37 + sample % 2 * 17) % 24_000) - 12_000
                assertTrue("frame ${sample / 2}", abs(value - legacyBoundary(original.toDouble())) <= 1)
            }
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(processor.output.hasRemaining())
        } finally { processor.reset() }
    }

    @Test fun disableDrainsOldPartialBlockAndReenableStartsWithCleanHistory() {
        val processor = processor(floatArrayOf(1f, 1f))
        try {
            val output = ArrayList<Int>()
            consume(processor, pcm(300) { _, _ -> 4096 }, output)
            assertTrue(output.isEmpty())
            processor.enabled = false
            consume(processor, pcm(400) { _, _ -> 4096 }, output)
            assertEquals(700 * 2, output.size)
            assertClose(legacyBoundary(4096.0), output[0])
            assertClose(legacyBoundary(8192.0), output[2])
            for (sample in 600 until 1400) assertEquals(4096, output[sample])
            processor.enabled = true
            consume(processor, pcm(400) { _, _ -> 4096 }, output)
            finish(processor, output)
            assertEquals(1101 * 2, output.size)
            for (frame in 700..1100) repeat(2) { channel ->
                val expected = if (frame == 700 || frame == 1100) 4096.0 else 8192.0
                assertClose(legacyBoundary(expected), output[frame * 2 + channel])
            }
        } finally { processor.reset() }
    }

    @Test fun pendingFormatOnlyBecomesActiveAtFlushAndSeekClearsOldTail() {
        val impulse = FloatArray(1200).apply { this[0] = 1f; this[1100] = 0.5f }
        val processor = processor(impulse, 8_000)
        try {
            val output = ArrayList<Int>()
            consume(processor, pcm(2048) { frame, _ -> if (frame == 0) 8192 else 0 }, output)
            processor.configure(AudioFormat(16_000, 2, C.ENCODING_PCM_16BIT))
            assertEquals(ConvolutionPreparationState.READY, processor.preparationState)
            consume(processor, pcm(20) { _, _ -> 0 }, output)
            finish(processor, output)
            assertEquals((2068 + impulse.size - 1) * 2, output.size)
            output.forEachIndexed { sample, value ->
                val expected = when (sample / 2) { 0 -> 8192.0; 1100 -> 4096.0; else -> 0.0 }
                assertClose(legacyBoundary(expected), value)
            }
            processor.flush()
            awaitPrepared(processor)
            val silence = ArrayList<Int>()
            consume(processor, pcm(2400) { _, _ -> 0 }, silence)
            finish(processor, silence)
            // sinc support adds 35 source frames per side.
            val resampledIrFrames = (impulse.size + 70) * 2
            assertEquals((2400 + resampledIrFrames - 1) * 2, silence.size)
            assertTrue(silence.all { it == 0 })
        } finally { processor.reset() }
    }

    @Test fun newestImpulseWinsAndAChangedIrDoesNotLoseBufferedFrames() {
        val processor = processor(floatArrayOf(1f))
        try {
            val output = ArrayList<Int>()
            consume(processor, pcm(123) { _, _ -> 8192 }, output)
            processor.setImpulse(ImpulseResponse(floatArrayOf(0.25f), floatArrayOf(0.25f), 48_000), 0f)
            processor.setImpulse(ImpulseResponse(floatArrayOf(0.5f), floatArrayOf(0.5f), 48_000), 0f)
            consume(processor, pcm(257) { _, _ -> 8192 }, output)
            finish(processor, output)
            assertEquals(380 * 2, output.size)
            for (sample in 0 until 123 * 2) assertClose(legacyBoundary(8192.0), output[sample])
            for (sample in 123 * 2 until output.size) assertClose(legacyBoundary(4096.0), output[sample])
        } finally { processor.reset() }
    }

    @Test fun invalidImpulseReportsFailureAndDryFallbackStillEnds() {
        val processor = ConvolutionProcessor()
        try {
            processor.enabled = true
            processor.setImpulse(ImpulseResponse(floatArrayOf(Float.NaN), floatArrayOf(1f), 48_000), 0f)
            processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)); processor.flush()
            awaitPrepared(processor)
            assertEquals(ConvolutionPreparationState.FAILED, processor.preparationState)
            assertTrue(processor.preparationFailure!!.contains("non-finite"))
            val output = ArrayList<Int>()
            consume(processor, pcm(17) { _, _ -> 1234 }, output)
            finish(processor, output)
            assertEquals(34, output.size)
            assertTrue(output.all { it == 1234 })
            processor.flush()
            processor.queueEndOfStream()
            assertFalse(processor.output.hasRemaining())
            assertTrue(processor.isEnded)
        } finally { processor.reset() }
    }

    @Test fun outputHeldByCallerIsNotOverwrittenWhenEndIsQueued() {
        val processor = processor(floatArrayOf(1f))
        try {
            processor.queueInput(pcm(1024) { _, _ -> 4096 })
            val held = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(4096, held.remaining())
            processor.queueEndOfStream()
            assertFalse(processor.output.hasRemaining())
            assertFalse(processor.isEnded)
            while (held.hasRemaining()) assertClose(legacyBoundary(4096.0), held.short.toInt())
            assertTrue(processor.isEnded)
        } finally { processor.reset() }
    }

    @Test fun makeupOnlyUpdateKeepsConvolutionHistory() {
        val processor = processor(floatArrayOf(1f, 1f))
        try {
            val output = ArrayList<Int>()
            consume(processor, pcm(1024) { _, _ -> 4096 }, output)
            processor.setMakeup(-6.0206f)
            consume(processor, pcm(1) { _, _ -> 4096 }, output)
            finish(processor, output)
            assertEquals(1026 * 2, output.size)
            output.forEachIndexed { sample, value ->
                val expected = when (sample / 2) {
                    0, 1024 -> 4096.0
                    1025 -> 2048.0
                    else -> 8192.0
                }
                assertClose(legacyBoundary(expected), value)
            }
        } finally { processor.reset() }
    }

    private fun processor(impulse: FloatArray, rate: Int = 48_000): ConvolutionProcessor = ConvolutionProcessor().also {
        it.enabled = true
        it.setImpulse(ImpulseResponse(impulse, impulse, rate), 0f)
        it.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)); it.flush()
        awaitPrepared(it)
        assertEquals(ConvolutionPreparationState.READY, it.preparationState)
    }
    private fun awaitPrepared(processor: ConvolutionProcessor) {
        val end = System.nanoTime() + 10_000_000_000L
        while (processor.preparationState == ConvolutionPreparationState.PREPARING && System.nanoTime() < end) Thread.sleep(1)
        assertNotEquals(ConvolutionPreparationState.PREPARING, processor.preparationState)
    }
    private fun consume(processor: ConvolutionProcessor, input: ByteBuffer, output: MutableList<Int>) {
        val end = System.nanoTime() + 10_000_000_000L
        while (input.hasRemaining()) {
            val before = input.position()
            processor.queueInput(input)
            drain(processor, output)
            if (input.position() == before) {
                assertTrue("Convolver did not consume input", System.nanoTime() < end)
                Thread.sleep(1)
            }
        }
    }
    private fun finish(processor: ConvolutionProcessor, output: MutableList<Int>) {
        processor.queueEndOfStream()
        drain(processor, output)
        assertTrue(processor.isEnded)
    }
    private fun drain(processor: ConvolutionProcessor, output: MutableList<Int>) {
        while (true) {
            val buffer = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            assertTrue(buffer.remaining() <= ConvolutionProcessor.BLOCK_FRAMES * 4)
            if (!buffer.hasRemaining()) return
            while (buffer.hasRemaining()) output.add(buffer.short.toInt())
        }
    }
    private fun pcm(frames: Int, value: (Int, Int) -> Int): ByteBuffer =
        ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame -> repeat(2) { channel -> putShort(value(frame, channel).toShort()) } }
            flip()
        }
    private fun legacyBoundary(sample: Double): Int = Math.floor(sample / 32768.0 * 32767.0 + 0.5).toInt()
    private fun assertClose(expected: Int, actual: Int) = assertTrue("expected=$expected actual=$actual", abs(expected - actual) <= 1)
}
