package com.aurora.music.data

import com.aurora.music.playback.engine.*
import org.junit.Assert.*
import org.junit.Test

class OutputRatePolicyTest {
    @Test fun policyRoundTripIsStrictAndMissingPreferenceUsesFollowSource() {
        val policy = OutputRatePolicy(OutputRateMode.FIXED, 96_000, false, 192_000, true)
        val json = OutputRatePolicyCodec.encode(policy)
        assertEquals(policy, OutputRatePolicyCodec.decode(json).getOrThrow())
        assertEquals(OutputRatePolicy(), OutputRatePolicyCodec.decode(null).getOrThrow())
        for (bad in listOf(json + "{}", json.replace("96000", "96000.5"), json.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
            json.replace("\"tpdfDither\":true", "\"tpdfDither\":\"true\""), json.replace("\"mode\":\"FIXED\"", "\"mode\":null"))) {
            assertTrue(bad, OutputRatePolicyCodec.decode(bad).isFailure)
        }
    }

    @Test fun legacyDitherRemainsFlatAndNewModeRoundTripsStrictly() {
        for (enabled in listOf(false, true)) {
            val old = """{"schemaVersion":1,"mode":"FOLLOW_SOURCE","fixedRate":48000,"preserveFamily":true,"maximumRate":192000,"tpdfDither":$enabled}"""
            val policy = OutputRatePolicyCodec.decode(old).getOrThrow()
            assertEquals(OutputRatePolicy(tpdfDither = enabled), policy)
            assertEquals(if (enabled) OutputDitherMode.TPDF else OutputDitherMode.OFF, policy.ditherMode)
        }
        val policy = OutputRatePolicy(tpdfDither = true, noiseShaping = true)
        val json = OutputRatePolicyCodec.encode(policy)
        assertEquals(policy, OutputRatePolicyCodec.decode(json).getOrThrow())
        assertEquals(OutputDitherMode.OFF, policy.copy(tpdfDither = false).ditherMode)
        assertEquals("TPDF dither", policy.ditherLabel(32000))
        assertEquals("First-order noise-shaped dither", policy.ditherLabel(44100))
        for (bad in listOf(json.replace("\"noiseShaping\":true", "\"noiseShaping\":null"),
            json.replace("\"noiseShaping\":true", "\"noiseShaping\":\"true\""),
            json.replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
            json.replace(",\"noiseShaping\":true", ""))) {
            assertTrue(bad, OutputRatePolicyCodec.decode(bad).isFailure)
        }
    }
    @Test fun compatibleMaximumPreservesSourceFamilyAndReportsFallback() {
        val supported = intArrayOf(44_100, 48_000, 96_000, 176_400, 192_000)
        val policy = OutputRatePolicy(mode = OutputRateMode.COMPATIBLE_MAXIMUM)
        assertEquals(176_400, OutputRateNegotiator.choose(44_100, policy, supported).sampleRate)
        assertEquals(176_400, OutputRateNegotiator.choose(11_025, policy, supported).sampleRate)
        assertEquals(192_000, OutputRateNegotiator.choose(48_000, policy, supported).sampleRate)
        assertNotNull(OutputRateNegotiator.choose(44_100, policy, intArrayOf(48_000, 96_000)).fallbackReason)
        assertNotNull(OutputRateNegotiator.choose(48_000, policy, intArrayOf()).fallbackReason)
        assertEquals(48_000, OutputRateNegotiator.choose(48_000, policy.copy(mode = OutputRateMode.FIXED, fixedRate = 384_000), supported).sampleRate)
    }
}
