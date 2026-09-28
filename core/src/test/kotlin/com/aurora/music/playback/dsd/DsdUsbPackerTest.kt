package com.aurora.music.playback.dsd

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DsdUsbPackerTest {
    @Test fun dopMatchesIndependentLittleEndian24And32BitWords() {
        val raw = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte(), 0xde.toByte(), 0xf0.toByte())
        assertArrayEquals(hex(0x56, 0x12, 5, 0x78, 0x34, 5, 0xde, 0x9a, 0xfa, 0xf0, 0xbc, 0xfa),
            DsdUsbPacker(2, DsdWireFormat.DOP, 3).pack(raw))
        assertArrayEquals(hex(0, 0x56, 0x12, 5, 0, 0x78, 0x34, 5, 0, 0xde, 0x9a, 0xfa, 0, 0xf0, 0xbc, 0xfa),
            DsdUsbPacker(2, DsdWireFormat.DOP, 4).pack(raw))
    }

    @Test fun arbitraryChannelAlignedSplitsKeepDataAndMarkersAcrossFinalPadding() {
        for (channels in 1..2) for (wire in DsdWireFormat.entries) for (length in 1..53) {
            val input = ByteArray(length * channels) { (it * 37).toByte() }
            val reference = DsdUsbPacker(channels, wire, 4)
            val expected = reference.pack(input) + reference.finish()
            for (split in 1..length) {
                val packer = DsdUsbPacker(channels, wire, 4)
                val output = ByteArrayOutputStream()
                var at = 0
                while (at < input.size) {
                    val end = minOf(at + split * channels, input.size)
                    output.write(packer.pack(input.copyOfRange(at, end)))
                    at = end
                }
                output.write(packer.finish())
                assertArrayEquals(expected, output.toByteArray())
                assertTrue(packer.finish().isEmpty())
                packer.reset()
                assertArrayEquals(expected, packer.pack(input) + packer.finish())
            }
        }
    }

    @Test fun finalDopFrameUsesIdleDsdAndContinuesMarkerPhase() {
        val packer = DsdUsbPacker(2, DsdWireFormat.DOP, 3)
        packer.pack(hex(1, 2, 3, 4, 5, 6))
        assertArrayEquals(hex(0x69, 5, 0xfa, 0x69, 6, 0xfa), packer.finish())
        assertThrows(IllegalStateException::class.java) { packer.pack(byteArrayOf()) }
    }

    @Test fun nativeMswFirstPackingNeverChangesSourceBits() {
        assertArrayEquals(hex(7, 5, 3, 1, 8, 6, 4, 2),
            DsdUsbPacker(2, DsdWireFormat.NATIVE_MSB32, 4).pack(hex(1, 2, 3, 4, 5, 6, 7, 8)))
        val packer = DsdUsbPacker(1, DsdWireFormat.NATIVE_MSB32, 4)
        assertTrue(packer.pack(hex(1)).isEmpty())
        assertArrayEquals(hex(0x69, 0x69, 0x69, 1), packer.finish())
    }

    private fun hex(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
