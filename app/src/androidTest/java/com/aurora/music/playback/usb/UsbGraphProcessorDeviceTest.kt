package com.aurora.music.playback.usb

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.PrecisionBlockProcessor
import com.aurora.music.playback.engine.BandlimitedResampler
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.pow

class UsbGraphProcessorDeviceTest {
    @Test fun enabledDitherAppliesToIntegerPrecisionReductionEvenWithoutGraphEffects() {
        val inputFormat = format().buildUpon().setPcmEncoding(C.ENCODING_PCM_32BIT).build()
        val processor = UsbGraphProcessor(PrecisionBlockProcessor(), { OutputRatePolicy(tpdfDither = true) })
        processor.configure(inputFormat, intArrayOf(48000), 24)
        val input = ByteBuffer.allocate(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(8192) { input.putInt(128) }; input.flip()
        val output = run(processor, input, 0)
        var ones = 0
        for (sample in 0 until 8192) {
            val value = output[sample * 3].toInt() and 255
            assertTrue(value in 0..1)
            assertEquals(0, output[sample * 3 + 1].toInt()); assertEquals(0, output[sample * 3 + 2].toInt())
            ones += value
        }
        assertEquals(.5, ones / 8192.0, .03)
    }

    @Test fun gaplessMonoKeepsLowBitsAndAppliesGainBeforeFinalPcm32Boundary() {
        val engine = PrecisionBlockProcessor().apply { enabled = true; update(DspParams(preampDb = -6f, limiterEnabled = false)) }
        val processor = UsbGraphProcessor(engine, { OutputRatePolicy() })
        processor.configure(format(channels = 1).buildUpon().setEncoderDelay(3).setEncoderPadding(5).build(), intArrayOf(48000), 32)
        val source = IntArray(1503) { (it * 137 - 75000) or 1 }
        val result = run(processor, pcm24(source), 700000)
        assertEquals((1503 - 8) * 8, result.size)
        val pcm = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 3 until source.size - 5) {
            val expected = floor(source[frame] * 256.0 * 10.0.pow(-6.0 / 20) + .5).toInt()
            assertEquals(expected, pcm.int); assertEquals(expected, pcm.int)
        }
        assertEquals((1503 - 8) * 1000000L / 48000, processor.inputDurationUs)
    }

    @Test fun completeFirTailPassesThroughSrcAtEosUnderPartialOutputReads() {
        val impulse = FloatArray(65).apply { this[0] = .5f; this[lastIndex] = .25f }
        val engine = PrecisionBlockProcessor().apply { convolutionEnabled = true; setImpulse(ImpulseResponse(impulse, impulse, 48000), 0f) }
        val processor = UsbGraphProcessor(engine, { OutputRatePolicy(OutputRateMode.FIXED, 96000) })
        processor.configure(format(), intArrayOf(48000, 96000), 32)
        val source = IntArray(257 * 2).apply { this[size - 2] = 2097152; this[size - 1] = -1048576 }
        val actual = ByteBuffer.wrap(run(processor, pcm24(source), 2000000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((257 + 64) * 2 * 8, actual.remaining())
        val convolved = DoubleArray((257 + 64) * 2).apply {
            this[256 * 2] = .125; this[256 * 2 + 1] = -.0625
            this[320 * 2] = .0625; this[320 * 2 + 1] = -.03125
        }
        val reference = BandlimitedResampler(48000, 96000)
        assertEquals(321, reference.queueInput(convolved, 0, 321)); reference.queueEndOfInput()
        val expected = DoubleArray(642 * 2)
        assertEquals(642, reference.readOutput(expected, 0, 642)); assertTrue(reference.isEnded)
        for (sample in expected) assertEquals(floor(sample * 2147483648.0 + .5).toInt(), actual.int)
    }

    @Test fun seekFlushDropsOldSamplesAndOnlyRestoresEncoderDelayAtStreamStart() {
        val processor = UsbGraphProcessor(PrecisionBlockProcessor(), { OutputRatePolicy() })
        processor.configure(format().buildUpon().setEncoderDelay(4).setEncoderPadding(3).build(), intArrayOf(48000), 24)
        val old = pcm24(IntArray(1024) { 12000 })
        processor.queueInput(old, 0)
        processor.flush()
        val second = IntArray(201 * 2) { -987 }
        assertEquals((201 - 3) * 6, run(processor, pcm24(second), 900000).size)
        processor.flush()
        val start = run(processor, pcm24(second), 0)
        assertEquals((201 - 4 - 3) * 6, start.size)
        assertArrayEquals(pcm24(IntArray((201 - 7) * 2) { -987 }).array(), start)
    }

    private fun run(processor: UsbGraphProcessor, source: ByteBuffer, timeUs: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        val deadline = SystemClock.elapsedRealtime() + 10000
        var eos = false
        var retained: ByteBuffer? = null
        var retainedTime = 0L
        while (!processor.isEnded) {
            if (source.hasRemaining()) processor.queueInput(source, timeUs)
            else if (!eos) { processor.queueEndOfStream(); eos = true }
            val output = processor.getOutput()
            if (output != null) {
                if (retained != null) { assertSame(retained, output); assertEquals(retainedTime, processor.outputTimeUs) }
                else assertEquals(timeUs + bytes.size() / (processor.outputFormat.channelCount * (if (processor.outputFormat.pcmEncoding == C.ENCODING_PCM_32BIT) 4 else 3)) * 1000000L / processor.outputFormat.sampleRate, processor.outputTimeUs)
                val stride = processor.outputFormat.channelCount * if (processor.outputFormat.pcmEncoding == C.ENCODING_PCM_32BIT) 4 else 3
                val chunk = ByteArray(minOf(output.remaining(), stride * 37)); output.get(chunk); bytes.write(chunk)
                retained = output.takeIf { it.hasRemaining() }; retainedTime = processor.outputTimeUs
            } else SystemClock.sleep(1)
            assertTrue("USB graph must finish", SystemClock.elapsedRealtime() < deadline)
        }
        return bytes.toByteArray()
    }

    companion object {
        fun format(rate: Int = 48000, channels: Int = 2) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT).setChannelCount(channels).setSampleRate(rate).build()
        fun pcm24(samples: IntArray): ByteBuffer = ByteBuffer.allocate(samples.size * 3).apply {
            samples.forEach { value -> repeat(3) { put((value shr (8 * it)).toByte()) } }; flip()
        }
    }
}
