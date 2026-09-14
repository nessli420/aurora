package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class PrecisionEffectsDeviceTest {
    @Test fun pcm16BoundaryAndDisabledBypassKeepTheirEstablishedEndpointRules() {
        val processor = AuroraDspProcessor()
        try {
            processor.update(DspParams(limiterEnabled = false, graphicFreqs = floatArrayOf(24_000f)))
            processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)); processor.flush()
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(processor.output.hasRemaining())
            val samples = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, -1, 1, 0, 16_384)
            assertArrayEquals(samples, consume(processor, samples))
            assertFalse(processor.processingActive)
            processor.enabled = true
            val result = consume(processor, samples)
            val expected = samples.map { Math.round(it / 32768.0 * 32767.0).toShort() }.toShortArray()
            assertArrayEquals(expected, result)
            assertTrue(processor.processingActive)
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            processor.queueEndOfStream()
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertTrue(processor.isEnded)
            processor.flush(); assertFalse(processor.processingActive)
            processor.enabled = false
            assertArrayEquals(samples, consume(processor, samples))
            assertFalse(processor.processingActive)
        } finally { processor.reset() }
    }

    @Test fun pendingFormatsAndLiveUpdatesDoNotRetuneOrResetDrainingSamples() {
        val processor = AuroraDspProcessor().apply { enabled = true }
        val reference = AuroraDspProcessor().apply { enabled = true }
        try {
            val first = DspParams(limiterEnabled = false, delayLeftMs = 1f, delayRightMs = 2f,
                parametric = listOf(DspBand(700f, 3f, 1f)))
            listOf(processor, reference).forEach {
                it.update(first); it.configure(AudioFormat(8_000, 2, C.ENCODING_PCM_16BIT)); it.flush()
            }
            val initial = ShortArray(54) { (it * 113 - 2_000).toShort() }
            assertArrayEquals(consume(reference, initial), consume(processor, initial))
            processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
            val changed = first.copy(preampDb = -3f)
            processor.update(changed); reference.update(changed)
            val draining = ShortArray(78) { (it * 89 - 3_000).toShort() }
            assertArrayEquals(consume(reference, draining), consume(processor, draining))
            assertTrue(processor.processingActive)
            processor.flush()
            assertFalse(processor.processingActive)
            val newStream = consume(processor, ShortArray(80) { 4_000 })
            // The 48-frame minimum delay at the newly active rate must start with fresh history.
            assertTrue(newStream.all { it == 0.toShort() })
            assertTrue(processor.processingActive)
            processor.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
            assertFalse(processor.isActive)
            assertTrue(processor.processingActive) // isActive describes pending, evidence describes active.
            processor.flush(); assertFalse(processor.processingActive)
        } finally { processor.reset(); reference.reset() }
    }

    @Test fun updateCopiesCallerArraysBeforeAnyFormatHasBeenConfigured() {
        val processor = AuroraDspProcessor().apply { enabled = true }
        val reference = AuroraDspProcessor().apply { enabled = true }
        try {
            val gains = FloatArray(10).apply { this[5] = 6f }
            val frequencies = DspCoeffBuilder.GRAPHIC_FREQS.copyOf()
            processor.update(DspParams(graphic = gains, graphicFreqs = frequencies, limiterEnabled = false))
            reference.update(DspParams(graphic = gains.copyOf(), graphicFreqs = frequencies.copyOf(), limiterEnabled = false))
            gains[5] = -12f; frequencies[5] = 7_000f
            listOf(processor, reference).forEach {
                it.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)); it.flush()
            }
            val impulse = ShortArray(1_024).apply { this[0] = 8_192; this[1] = -4_096 }
            assertArrayEquals(consume(reference, impulse), consume(processor, impulse))
        } finally { processor.reset(); reference.reset() }
    }

    private fun consume(processor: AuroraDspProcessor, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(input::putShort); input.flip()
        processor.queueInput(input)
        assertEquals(input.limit(), input.position())
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(output.remaining() / 2) { output.short }
    }
}
