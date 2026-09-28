package com.aurora.music.playback.chain

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.playback.engine.BandlimitedResampler
import com.aurora.music.playback.engine.OutputRatePolicy
import com.aurora.music.playback.engine.PcmEncoding
import com.aurora.music.playback.engine.SamplePrecision
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class DspChainTest {
    private val signal = DoubleArray(5_001 * 2) { sin(it * 0.0137) * 0.6 + (it % 7) * 2.0.pow(-23) }

    @Test fun bypassIsBitExactAcrossOddBlockSizes() {
        val chain = DspChain()
        chain.configure(ChainFormat(48_000, 48_000, PcmEncoding.SIGNED_24_LE, SamplePrecision.PCM_SIGNED_24))
        val output = stream(chain, signal, 77, 131)
        assertArrayEquals(signal, output, 0.0)
        assertTrue(chain.isEnded)
        assertEquals(5_001L, chain.inputFrames)
        assertEquals(5_001L, chain.outputFrames)
        val report = chain.report()
        assertTrue(report.bitExact)
        assertFalse(report.resampling)
        assertNull(report.ditherLabel)
        assertFalse(chain.needsQuantization())
        assertTrue(chain.needsQuantization(0.5))
    }

    @Test fun resamplingMatchesOfflineConversionPerChannel() {
        val chain = DspChain()
        chain.configure(ChainFormat(44_100, 48_000, PcmEncoding.SIGNED_16_LE, SamplePrecision.PCM_SIGNED_16,
            OutputRatePolicy(tpdfDither = true)))
        val output = stream(chain, signal, 256, 1024)
        val left = DoubleArray(signal.size / 2) { signal[it * 2] }
        val expected = BandlimitedResampler.resample(left, 44_100, 48_000)
        assertEquals((5_001 * 48_000.0 / 44_100).roundToInt() * 2, output.size)
        assertArrayEquals(expected, DoubleArray(output.size / 2) { output[it * 2] }, 0.0)
        val report = chain.report()
        assertTrue(report.resampling)
        assertFalse(report.bitExact)
        assertEquals("TPDF dither", report.ditherLabel)
        assertTrue(chain.needsQuantization())
    }

    @Test fun speedResamplesFromTheScaledRate() {
        val chain = DspChain()
        chain.configure(ChainFormat(48_000, 48_000, speed = 1.25))
        val output = stream(chain, signal, 200, 500)
        assertEquals((5_001 / 1.25).roundToInt() * 2, output.size)
        assertTrue(chain.report().resampling)
    }

    @Test fun drainedChainReconfiguresToANewRateAndFlushDropsPendingAudio() {
        val chain = DspChain()
        chain.configure(ChainFormat(44_100, 48_000))
        stream(chain, signal, 256, 256)
        chain.configure(ChainFormat(48_000, 48_000))
        assertEquals(0L, chain.inputFrames)
        assertEquals(128, chain.queueInput(signal, 0, 128))
        chain.flush()
        assertEquals(0, chain.readOutput(DoubleArray(512), 0, 256))
        assertArrayEquals(signal, stream(chain, signal, 100, 300), 0.0)
    }

    @Test fun inputIsRefusedUntilPendingOutputIsRead() {
        val chain = DspChain()
        chain.configure(ChainFormat(48_000, 48_000))
        assertEquals(256, chain.queueInput(signal, 0, 1_000))
        val first = DoubleArray(20)
        assertEquals(10, chain.readOutput(first, 0, 10))
        assertEquals(0, chain.queueInput(signal, 256, 256))
        val rest = DoubleArray(512)
        assertEquals(246, chain.readOutput(rest, 0, 256))
        assertEquals(256, chain.queueInput(signal, 256, 256))
    }

    @Test fun customDspChangesSamplesAndSelectsDither() {
        val chain = DspChain()
        chain.engine.applySettings(DspChainSettings(AudioPrefs(dspMode = DspMode.CUSTOM, dspPreampDb = -6f, dspLimiterEnabled = false)))
        chain.configure(ChainFormat(48_000, 48_000, PcmEncoding.SIGNED_24_LE, SamplePrecision.PCM_SIGNED_24,
            OutputRatePolicy(tpdfDither = true, noiseShaping = true)))
        val output = stream(chain, signal, 256, 1024)
        assertEquals(signal.size, output.size)
        assertEquals(signal[4_000] * 10.0.pow(-6.0 / 20.0), output[4_000], 1e-9)
        val report = chain.report()
        assertTrue(report.processingChangesSamples)
        assertFalse(report.bitExact)
        assertEquals("First-order noise-shaped dither", report.ditherLabel)
    }

    @Test fun floatOutputHoldsSixteenAndTwentyFourBitSourcesOnly() {
        assertTrue(PcmEncoding.FLOAT_32_LE.holds(SamplePrecision.PCM_SIGNED_24))
        assertFalse(PcmEncoding.FLOAT_32_LE.holds(SamplePrecision.PCM_SIGNED_32))
        assertTrue(PcmEncoding.SIGNED_24_LE.holds(SamplePrecision.PCM_SIGNED_16))
        assertFalse(PcmEncoding.SIGNED_24_LE.holds(SamplePrecision.FLOAT_32))
        assertFalse(PcmEncoding.SIGNED_16_LE.holds(SamplePrecision.PCM_SIGNED_24))
    }

    private fun stream(chain: DspChain, input: DoubleArray, inputBlock: Int, outputBlock: Int): DoubleArray {
        val result = ArrayList<Double>()
        val buffer = DoubleArray(outputBlock * 2)
        fun drain() {
            while (true) {
                val count = chain.readOutput(buffer, 0, outputBlock)
                if (count == 0) break
                for (i in 0 until count * 2) result += buffer[i]
            }
        }
        var offset = 0
        val frames = input.size / 2
        while (offset < frames) {
            val accepted = chain.queueInput(input, offset, minOf(inputBlock, frames - offset))
            offset += accepted
            drain()
        }
        chain.queueEndOfStream()
        repeat(64) { if (!chain.isEnded) drain() }
        assertTrue(chain.isEnded)
        return result.toDoubleArray()
    }
}
