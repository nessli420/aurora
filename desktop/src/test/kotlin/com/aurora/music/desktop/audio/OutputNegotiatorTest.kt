package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import org.junit.Assert.*
import org.junit.Test

class OutputNegotiatorTest {
    private val device = FakeBackend(mixRate = 44_100, exclusive = setOf(
        44_100 to OutputEncoding.S16, 44_100 to OutputEncoding.S24_IN_32,
        48_000 to OutputEncoding.S16, 48_000 to OutputEncoding.S24_IN_32, 48_000 to OutputEncoding.S32,
        96_000 to OutputEncoding.S24,
    ))

    @Test fun sharedModeUsesTheMixRateInFloat() {
        val shared = OutputNegotiator(device).negotiate(null, false, 96_000, OutputRatePolicy())
        assertEquals(NegotiatedOutput(null, false, 44_100, OutputEncoding.F32), shared)
    }

    @Test fun exclusiveFollowsTheSourceWithThePreferredIntegerEncoding() {
        val negotiator = OutputNegotiator(device)
        assertEquals(NegotiatedOutput(null, true, 48_000, OutputEncoding.S24_IN_32), negotiator.negotiate(null, true, 48_000, OutputRatePolicy()))
        assertEquals(OutputEncoding.S24, negotiator.negotiate(null, true, 96_000, OutputRatePolicy()).encoding)
    }

    @Test fun unsupportedRatesFallBackWithinTheFamily() {
        val chosen = OutputNegotiator(device).negotiate(null, true, 88_200, OutputRatePolicy())
        assertEquals(44_100, chosen.sampleRate)
        assertEquals("88200 Hz is unavailable; using 44100 Hz.", chosen.rateFallbackReason)
        val maximum = OutputNegotiator(device).negotiate(null, true, 48_000,
            OutputRatePolicy(OutputRateMode.COMPATIBLE_MAXIMUM, maximumRate = 192_000))
        assertEquals(96_000, maximum.sampleRate)
    }

    @Test fun forgettingADeviceReprobesOnlyThatDevice() {
        val negotiator = OutputNegotiator(device)
        negotiator.negotiate(null, true, 48_000, OutputRatePolicy())
        negotiator.negotiate("dac", true, 48_000, OutputRatePolicy())
        negotiator.negotiate("dac", false, 48_000, OutputRatePolicy())
        val probed = device.probes.get()
        negotiator.forget("hdmi")
        negotiator.negotiate(null, true, 48_000, OutputRatePolicy())
        negotiator.negotiate("dac", true, 48_000, OutputRatePolicy())
        assertEquals(probed, device.probes.get())
        negotiator.forget("dac")
        negotiator.negotiate(null, true, 48_000, OutputRatePolicy())
        assertEquals(probed, device.probes.get())
        negotiator.negotiate("dac", true, 48_000, OutputRatePolicy())
        negotiator.negotiate("dac", false, 48_000, OutputRatePolicy())
        assertTrue(device.probes.get() > probed)
        val reprobed = device.probes.get()
        negotiator.forget(null)
        negotiator.negotiate("dac", true, 48_000, OutputRatePolicy())
        assertEquals(reprobed, device.probes.get())
        negotiator.negotiate(null, true, 48_000, OutputRatePolicy())
        assertTrue(device.probes.get() > reprobed)
    }

    @Test fun devicesWithoutExclusiveSupportStayShared() {
        val chosen = OutputNegotiator(FakeBackend()).negotiate("speakers", true, 44_100, OutputRatePolicy())
        assertFalse(chosen.exclusive)
        assertEquals(48_000, chosen.sampleRate)
        assertNotNull(chosen.fallbackReason)
    }
}
