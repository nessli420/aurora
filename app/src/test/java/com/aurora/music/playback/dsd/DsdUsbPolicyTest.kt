package com.aurora.music.playback.dsd

import org.junit.Assert.*
import org.junit.Test

class DsdUsbPolicyTest {
    @Test fun unknownDacsRequireOptInAndUseExactCarrierRates() {
        for (wire in DsdWireFormat.entries) for (rate in DsdFormat.supportedBitRates) {
            assertThrows(IllegalArgumentException::class.java) {
                DsdUsbPolicy.carrierRate(0x1234, 0x5678, rate, wire, false)
            }
            assertEquals(rate / (wire.sourceBytes * 8),
                DsdUsbPolicy.carrierRate(0x1234, 0x5678, rate, wire, true))
        }
        assertEquals(1411200, DsdUsbPolicy.carrierRate(1, 1, 45158400, DsdWireFormat.NATIVE_MSB32, true))
        assertEquals(2822400, DsdUsbPolicy.carrierRate(1, 1, 45158400, DsdWireFormat.DOP, true))
    }

    @Test fun experimentalModeDoesNotOverrideKnownKa13LimitsOrAcceptUnknownRates() {
        for (experimental in listOf(false, true)) for (wire in DsdWireFormat.entries) {
            val maximum = if (wire == DsdWireFormat.DOP) 5644800 else 11289600
            assertEquals(maximum / (wire.sourceBytes * 8),
                DsdUsbPolicy.carrierRate(0x2972, 0x0062, maximum, wire, experimental))
            assertThrows(IllegalArgumentException::class.java) {
                DsdUsbPolicy.carrierRate(0x2972, 0x0062, maximum * 2, wire, experimental)
            }
        }
        for (rate in listOf(-1, 0, 49152000, 90316800)) assertThrows(IllegalArgumentException::class.java) {
            DsdUsbPolicy.carrierRate(1, 1, rate, DsdWireFormat.DOP, true)
        }
    }
}
