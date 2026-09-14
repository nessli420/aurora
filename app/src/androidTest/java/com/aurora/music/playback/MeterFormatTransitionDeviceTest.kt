package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class MeterFormatTransitionDeviceTest {
    @Test fun sharedEmptyBufferIsSafeBeforeAudioAndDuringDrain() {
        val meter = PcmLevelMeter()
        val processor = LevelMeterAudioProcessor(meter)
        try {
            processor.configure(AudioFormat(8_000, 2, C.ENCODING_PCM_16BIT))
            processor.flush()
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(processor.output.hasRemaining())
            assertNull(meter.snapshot())
            processor.queueInput(pcm(800, 2, 8192))
            val output = processor.output
            output.position(output.limit())
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            processor.queueEndOfStream()
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(processor.output.hasRemaining())
            assertTrue(processor.isEnded)
            assertEquals(800L, requireNotNull(meter.snapshot()).framesSinceReset)
        } finally { processor.reset() }
    }

    @Test fun pendingFormatDoesNotChangeTheMeterUntilFlushActivatesIt() {
        val meter = PcmLevelMeter()
        val processor = LevelMeterAudioProcessor(meter)
        try {
            processor.configure(AudioFormat(8_000, 2, C.ENCODING_PCM_16BIT))
            processor.flush()
            val initial = pcm(800, 2, 8192)
            val expected = initial.array().copyOf()
            processor.queueInput(initial)
            val output = processor.output
            val actual = ByteArray(output.remaining()); output.get(actual)
            assertArrayEquals(expected, actual)
            assertEquals(initial.limit(), initial.position())
            assertEquals(8_000, requireNotNull(meter.snapshot()).sampleRate)
            assertEquals(2, requireNotNull(meter.snapshot()).channels)
            assertEquals(800L, requireNotNull(meter.snapshot()).framesSinceReset)

            // BaseAudioProcessor.configure is pending; the old format can still drain.
            processor.configure(AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT))
            processor.queueInput(pcm(800, 2, 16384))
            val drainingOutput = processor.output
            drainingOutput.position(drainingOutput.limit())
            val draining = requireNotNull(meter.snapshot())
            assertEquals(8_000, draining.sampleRate)
            assertEquals(2, draining.channels)
            assertEquals(1600L, draining.framesSinceReset)
            assertEquals(0.5, draining.leftRms, 0.0)

            processor.flush()
            assertNull(meter.snapshot())
            processor.queueInput(pcm(1600, 1, 4096))
            val active = requireNotNull(meter.snapshot())
            assertEquals(16_000, active.sampleRate)
            assertEquals(1, active.channels)
            assertEquals(1600L, active.framesSinceReset)
            assertEquals(1600, active.windowFrames)
            assertEquals(0.125, active.leftRms, 0.0)
            assertEquals(active.leftRms, active.rightRms, 0.0)

            processor.reset()
            assertNull(meter.snapshot())
        } finally {
            processor.reset()
        }
    }

    private fun pcm(frames: Int, channels: Int, value: Int): ByteBuffer =
        ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames * channels) { putShort(value.toShort()) }
            flip()
        }
}
