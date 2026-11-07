package com.aurora.music.playback

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmLevelMeterSpectrumGateTest {
    private fun tone(frames: Int): ByteBuffer = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(frames) { putFloat(0.5f); putFloat(-0.25f) }
        flip()
    }

    @Test fun disabledSpectrumKeepsLevelsAndResumesWithAFreshWindow() {
        val meter = PcmLevelMeter().apply { configure(PcmConstants.ENCODING_PCM_FLOAT, 2, 48_000) }
        val window = tone(PcmSpectrumTap.SIZE)
        meter.observe(window, 0, window.limit(), 1_000_000)
        assertEquals(1, meter.spectrum.windows().size)

        meter.spectrumEnabled = false
        val longer = tone(PcmSpectrumTap.SIZE * 3)
        meter.observe(longer, 0, longer.limit(), 1_100_000)
        assertEquals(0.5, requireNotNull(meter.snapshot()).leftPeak, 0.000001)
        assertEquals(1, meter.spectrum.windows().size)

        val generation = meter.spectrum.generation
        meter.spectrumEnabled = true
        meter.observe(window, 0, window.limit(), 2_000_000)
        assertTrue(meter.spectrum.generation > generation)
        assertEquals(2_000_000L, meter.spectrum.windows().single().timeUs)
    }
}
