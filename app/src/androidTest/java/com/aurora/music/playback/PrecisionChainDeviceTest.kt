package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exercise Media3's actual processor pump, including backpressure and multi-processor EOS. */
@UnstableApi
class PrecisionChainDeviceTest {
    @Test fun largeDecoderBufferAndPartialEndKeepEveryFrameAcrossTheWholeChain() {
        val dsp = AuroraDspProcessor().apply {
            enabled = true
            update(DspParams(limiterEnabled = false))
        }
        val convolution = ConvolutionProcessor().apply {
            enabled = true
            setImpulse(ImpulseResponse(floatArrayOf(.5f), floatArrayOf(.25f), 48_000), 0f)
        }
        val meter = PcmLevelMeter()
        val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(
            dsp, convolution, LevelMeterAudioProcessor(meter)))
        try {
            pipeline.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
            pipeline.flush()
            // Exceeds the old convolver FIFO and ends between two 1024-frame FFT blocks.
            val frames = 70_013
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            repeat(frames) { i -> input.putShort(sample(i, 0)); input.putShort(sample(i, 1)) }
            input.flip()
            val bytes = processToEnd(pipeline, input)
            assertEquals("No overwritten input or appended padding", frames * 4, bytes.size)
            val output = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            repeat(frames) { i ->
                for (channel in 0..1) {
                    val dspOut = legacy(sample(i, channel) / 32768.0)
                    val expected = legacy(dspOut / 32768.0 * if (channel == 0) .5 else .25)
                    assertEquals("frame $i channel $channel", expected.toInt(), output.short.toInt())
                }
            }
            assertTrue(pipeline.isEnded)
            assertEquals(0L, requireNotNull(meter.snapshot()).invalidSamples)
        } finally { pipeline.reset() }
    }

    @Test fun pendingFormatDrainsOldAudioAndSeekClearsTheCombinedHistories() {
        val params = DspParams(delayLeftMs = 20f, delayRightMs = 13f, limiterEnabled = false,
            parametric = listOf(DspBand(1000f, 4f, 1f)))
        val dsp = AuroraDspProcessor().apply {
            enabled = true
            update(params)
        }
        val ir = FloatArray(1500).apply { this[0] = .5f; this[1400] = .25f }
        val convolution = ConvolutionProcessor().apply {
            enabled = true
            setImpulse(ImpulseResponse(ir, ir.copyOf(), 48_000), 0f)
        }
        val pipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(dsp, convolution))
        try {
            pipeline.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
            pipeline.flush()
            val signal = ByteBuffer.allocateDirect(1234 * 4).order(ByteOrder.nativeOrder())
            repeat(1234) { signal.putShort(8192); signal.putShort(-4096) }
            signal.flip()
            val referenceDsp = AuroraDspProcessor().apply { enabled = true; update(params) }
            val referencePipeline = AudioProcessingPipeline(ImmutableList.of<AudioProcessor>(referenceDsp))
            val reference = try {
                referencePipeline.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
                referencePipeline.flush()
                val bytes = processToEnd(referencePipeline, signal.duplicate())
                assertEquals(1234 * 4, bytes.size)
                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().let { buffer ->
                    ShortArray(buffer.remaining()).also(buffer::get)
                }
            } finally { referencePipeline.reset() }
            val oldOutput = ByteArrayOutputStream()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (signal.hasRemaining()) {
                assertTrue("IR preparation / old input made no progress", SystemClock.elapsedRealtime() < deadline)
                pipeline.queueInput(signal)
                drain(pipeline, oldOutput)
                if (signal.hasRemaining()) SystemClock.sleep(1)
            }
            pipeline.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            pipeline.queueEndOfStream()
            while (!pipeline.isEnded) {
                assertTrue("Old-format drain stalled", SystemClock.elapsedRealtime() < deadline)
                drain(pipeline, oldOutput)
                if (!pipeline.isEnded) SystemClock.sleep(1)
            }
            assertEquals((1234 + ir.size - 1) * 4, oldOutput.size())
            val convolved = ByteBuffer.wrap(oldOutput.toByteArray()).order(ByteOrder.nativeOrder())
            repeat(1234 + ir.size - 1) { frame -> repeat(2) { channel ->
                val direct = if (frame < 1234) reference[frame * 2 + channel] / 32768.0 * .5 else 0.0
                val delayed = if (frame in 1400 until 2634) reference[(frame - 1400) * 2 + channel] / 32768.0 * .25 else 0.0
                val expected = legacy(direct + delayed).toInt()
                val actual = convolved.short.toInt()
                assertTrue("frame $frame channel $channel: $expected != $actual", abs(expected - actual) <= 1)
            } }
            pipeline.flush()
            val silence = ByteBuffer.allocateDirect(4099 * 4).order(ByteOrder.nativeOrder())
            silence.limit(silence.capacity())
            val afterSeek = processToEnd(pipeline, silence)
            // rational sinc padding adds 160 source frames per side.
            val resampledIrFrames = ((ir.size + 320) * 44_100.0 / 48_000).roundToInt()
            assertEquals((4099 + resampledIrFrames - 1) * 4, afterSeek.size)
            assertTrue("No old EQ, delay or convolution tail after flush", afterSeek.all { it == 0.toByte() })
        } finally { pipeline.reset() }
    }

    private fun processToEnd(pipeline: AudioProcessingPipeline, input: ByteBuffer): ByteArray {
        val output = ByteArrayOutputStream()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (input.hasRemaining()) {
            assertTrue("Input processing stalled", SystemClock.elapsedRealtime() < deadline)
            pipeline.queueInput(input)
            drain(pipeline, output)
            if (input.hasRemaining()) SystemClock.sleep(1)
        }
        pipeline.queueEndOfStream()
        while (!pipeline.isEnded) {
            assertTrue("EOS drain stalled", SystemClock.elapsedRealtime() < deadline)
            drain(pipeline, output)
            if (!pipeline.isEnded) SystemClock.sleep(1)
        }
        return output.toByteArray()
    }

    private fun drain(pipeline: AudioProcessingPipeline, output: ByteArrayOutputStream) {
        val buffer = pipeline.output
        if (buffer.hasRemaining()) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        }
    }

    private fun sample(frame: Int, channel: Int): Short =
        (((frame * (if (channel == 0) 37 else 71)) % 8191) - 4095).toShort()

    private fun legacy(sample: Double): Short = (sample * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
}
