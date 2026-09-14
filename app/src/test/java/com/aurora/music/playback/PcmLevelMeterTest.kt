package com.aurora.music.playback

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class PcmLevelMeterTest {
    @Test fun stereoSineHasIndependentPeakAndRmsWithoutChangingTheBuffer() {
        val meter = PcmLevelMeter().apply { configure(C.ENCODING_PCM_FLOAT, 2, 1_000) }
        val buffer = ByteBuffer.allocate(800).order(ByteOrder.LITTLE_ENDIAN)
        repeat(100) { i ->
            buffer.putFloat((0.5 * sin(2 * PI * i / 100)).toFloat())
            buffer.putFloat((0.25 * sin(2 * PI * i / 100)).toFloat())
        }
        buffer.flip()
        val bytes = buffer.array().copyOf()
        // PCM encoding defines byte order, not the caller's ByteBuffer metadata.
        buffer.order(ByteOrder.BIG_ENDIAN)
        meter.observe(buffer, 0, 800, 1_000_000)
        val result = requireNotNull(meter.snapshot())
        assertEquals(0.5, result.leftPeak, 0.000001)
        assertEquals(0.25, result.rightPeak, 0.000001)
        assertEquals(0.5 / sqrt(2.0), result.leftRms, 0.000001)
        assertEquals(0.25 / sqrt(2.0), result.rightRms, 0.000001)
        assertEquals(100, result.windowFrames)
        assertEquals(100L, result.framesSinceReset)
        assertEquals(1_100_000L, result.presentationEndUs)
        assertEquals(0L, result.fullScaleSamples)
        assertEquals(0, buffer.position())
        assertEquals(800, buffer.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
        assertArrayEquals(bytes, buffer.array())
    }

    @Test fun dcWindowsWaitUntilCompleteAndDoNotCarryOldPeaks() {
        val meter = PcmLevelMeter().apply { configure(C.ENCODING_PCM_16BIT, 2, 500) }
        val first = pcm16(50, 16_384, -8_192)
        meter.observe(first, 0, 100)
        assertNull(meter.snapshot())
        meter.observe(first, 100, 200)
        val loud = requireNotNull(meter.snapshot())
        assertEquals(0.5, loud.leftRms, 0.0)
        assertEquals(0.25, loud.rightRms, 0.0)
        assertEquals(0.5, loud.leftPeak, 0.0)
        assertEquals(50L, loud.framesSinceReset)
        assertNull(loud.presentationEndUs)
        val second = pcm16(50, 4_096, -2_048)
        meter.observe(second, 0, second.limit())
        val quiet = requireNotNull(meter.snapshot())
        assertEquals(100L, quiet.framesSinceReset)
        assertEquals(50, quiet.windowFrames)
        assertEquals(0.125, quiet.leftPeak, 0.0)
        assertEquals(0.0625, quiet.rightRms, 0.0)
    }

    @Test fun integerAndFloatFullScaleBoundariesUseLittleEndian() {
        listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT).forEach { encoding ->
            val bits = when (encoding) { C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; else -> 32 }
            val buffer = ByteBuffer.allocate(bits / 8 * 2).order(ByteOrder.LITTLE_ENDIAN)
            val positive: Double
            if (encoding == C.ENCODING_PCM_FLOAT) {
                buffer.putFloat(-1f).putFloat(1f)
                positive = 1.0
            } else {
                val minimum = -(1L shl (bits - 1))
                val maximum = (1L shl (bits - 1)) - 1
                for (sample in listOf(minimum, maximum)) repeat(bits / 8) { byte ->
                    buffer.put((sample shr (8 * byte)).toByte())
                }
                positive = maximum.toDouble() / -minimum
            }
            buffer.flip()
            buffer.order(ByteOrder.BIG_ENDIAN)
            val meter = PcmLevelMeter().apply { configure(encoding, 1, 20) }
            meter.observe(buffer, 0, buffer.limit())
            val result = requireNotNull(meter.snapshot())
            assertEquals("Encoding $encoding", 1.0, result.leftPeak, 0.0)
            assertEquals(sqrt((1 + positive * positive) / 2), result.leftRms, 0.000000001)
            assertEquals(result.leftRms, result.rightRms, 0.0)
            assertEquals(2L, result.fullScaleSamples)
            assertEquals(0L, result.invalidSamples)
        }
    }

    @Test fun invalidFloatSamplesAreCountedWithoutPoisoningLevels() {
        val samples = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.25f, -1f, 0.5f)
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        buffer.flip()
        val meter = PcmLevelMeter().apply { configure(C.ENCODING_PCM_FLOAT, 1, 60) }
        meter.observe(buffer, 0, buffer.limit())
        val result = requireNotNull(meter.snapshot())
        assertEquals(3L, result.invalidSamples)
        assertEquals(2L, result.fullScaleSamples)
        assertEquals(1.25, result.leftPeak, 0.0)
        assertEquals(sqrt((1.25 * 1.25 + 1 + 0.25) / 6), result.leftRms, 0.000001)
        assertEquals(result.leftRms, result.rightRms, 0.0)
    }

    @Test fun stereoCountersCountSamplesAndAccumulateAcrossWindows() {
        val meter = PcmLevelMeter().apply { configure(C.ENCODING_PCM_FLOAT, 2, 10) }
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY)
            .putFloat(-1f).putFloat(1f)
        buffer.flip()
        meter.observe(buffer, 0, 8)
        assertEquals(2L, requireNotNull(meter.snapshot()).invalidSamples)
        meter.observe(buffer, 8, 16)
        val result = requireNotNull(meter.snapshot())
        assertEquals(2L, result.framesSinceReset)
        assertEquals(2L, result.invalidSamples)
        assertEquals(2L, result.fullScaleSamples)
        assertEquals(1.0, result.leftRms, 0.0)
    }

    @Test fun resetAndReconfigureDiscardCompletedAndPartialWindows() {
        val meter = PcmLevelMeter().apply { configure(C.ENCODING_PCM_16BIT, 2, 20) }
        val full = pcm16(3, Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        meter.observe(full, 0, full.limit())
        assertEquals(4L, requireNotNull(meter.snapshot()).fullScaleSamples)
        meter.reset()
        assertNull(meter.snapshot())
        val quiet = pcm16(2, 2_048, -2_048)
        meter.observe(quiet, 0, 4)
        assertNull("The old partial window must be discarded", meter.snapshot())
        meter.observe(quiet, 4, 8)
        val result = requireNotNull(meter.snapshot())
        assertEquals(2L, result.framesSinceReset)
        assertEquals(0L, result.fullScaleSamples)
        assertEquals(0.0625, result.leftRms, 0.0)
        meter.configure(C.ENCODING_PCM_FLOAT, 1, 10)
        assertNull(meter.snapshot())
        val mono = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f)
        mono.flip()
        meter.observe(mono, 0, 4)
        val configured = requireNotNull(meter.snapshot())
        assertEquals(1, configured.channels)
        assertEquals(10, configured.sampleRate)
        assertEquals(1L, configured.framesSinceReset)
    }

    @Test fun unsupportedFormatsAndIncompleteFramesHaveNoMeasurement() {
        val buffer = pcm16(10, 1_000, 1_000)
        val meter = PcmLevelMeter()
        meter.configure(C.ENCODING_PCM_8BIT, 2, 10)
        meter.observe(buffer, 0, buffer.limit())
        assertNull(meter.snapshot())
        meter.configure(C.ENCODING_PCM_16BIT, 3, 10)
        meter.observe(buffer, 0, 36)
        assertNull(meter.snapshot())
        meter.configure(C.ENCODING_PCM_16BIT, 2, 10)
        meter.observe(buffer, -1, 3)
        meter.observe(buffer, 0, buffer.limit() + 4)
        meter.observe(buffer, 0, 3)
        assertNull(meter.snapshot())
        meter.observe(buffer, 0, 4)
        assertEquals(1L, requireNotNull(meter.snapshot()).framesSinceReset)
    }

    private fun pcm16(frames: Int, left: Int, right: Int): ByteBuffer =
        ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { putShort(left.toShort()); putShort(right.toShort()) }
            flip()
        }
}
