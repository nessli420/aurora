package com.aurora.music.playback.dsd

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DsdHeadersTest {
    @Test fun dsfReadsBothBitOrdersAndRequiresCompleteChannelBlocks() {
        for (bits in listOf(1, 8)) {
            val f = DsdHeaders.dsf(dsf(bits = bits), 92, 16384)
            assertEquals(bits == 1, f.leastSignificantBitFirst)
            assertEquals(44_100L, f.sampleCount)
            assertEquals(2, f.channels)
            assertEquals(2_757L, f.pcmFrames)
        }
        assertThrows(IllegalArgumentException::class.java) { DsdHeaders.dsf(dsf(), 92, 16383) }
        assertThrows(IllegalArgumentException::class.java) { DsdHeaders.dsf(dsf(block = 8192), 92, 8192) }
        assertThrows(IllegalArgumentException::class.java) { DsdHeaders.dsf(dsf(bits = 2), 92, 8192) }
        assertThrows(IllegalArgumentException::class.java) { DsdHeaders.dsf(dsf(rate = 90_316_800), 92, 8192) }
        assertThrows(IllegalArgumentException::class.java) { DsdHeaders.dsf(dsf(samples = Long.MAX_VALUE), 92, 8192) }
    }

    @Test fun everySupportedRateHasBoundedDecimationAndExactEndSeeking() {
        for (rate in DsdFormat.supportedBitRates) {
            val f = DsdHeaders.dsf(dsf(rate = rate), 92, 16384)
            assertEquals(176400, f.pcmRate)
            assertEquals(rate / 176400, f.decimation)
            assertTrue(f.filterTaps in 512..8192)
            assertEquals(0L, f.seekFrame(-1))
            assertEquals(0L, f.seekFrame(0))
            assertEquals(f.pcmFrames, f.seekFrame(f.durationUs))
            assertEquals(f.pcmFrames, f.seekFrame(Long.MAX_VALUE))
            assertTrue(f.position(f.prerollByte(f.pcmFrames)) <= f.dataOffset + f.dataBytes)
        }
        for (rate in listOf(0, 2_822_401, 6_144_000, 90_316_800, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DsdFormat(DsdContainer.DFF, rate, 2, 8192, 0, 2048) }
        }
    }

    @Test fun dffAcceptsEverySupportedRateAndRejectsUnsupportedRates() {
        for (rate in DsdFormat.supportedBitRates + listOf(45_158_400, 6_144_000)) {
            val properties = "SND ".toByteArray() + chunk("FS  ", ByteBuffer.allocate(4).putInt(rate).array()) +
                chunk("CHNL", ByteBuffer.allocate(10).putShort(2).put("SLFTSRGT".toByteArray()).array()) +
                chunk("CMPR", "DSD ".toByteArray() + byteArrayOf(0))
            if (DsdFormat.supportsBitRate(rate)) assertEquals(rate to 2, DsdHeaders.dffProperties(properties))
            else assertThrows(IllegalArgumentException::class.java) { DsdHeaders.dffProperties(properties) }
        }
    }

    @Test fun dffPropertiesValidateRateChannelsCompressionAndUnknownChunkPadding() {
        val rate = ByteBuffer.allocate(4).putInt(5_644_800).array()
        val channels = ByteBuffer.allocate(10).putShort(2).put("SLFTSRGT".toByteArray()).array()
        val plain = "DSD ".toByteArray() + byteArrayOf(3) + "raw".toByteArray()
        val properties = "SND ".toByteArray() + chunk("JUNK", byteArrayOf(1)) + chunk("FS  ", rate) + chunk("CHNL", channels) + chunk("CMPR", plain)
        assertEquals(5_644_800 to 2, DsdHeaders.dffProperties(properties))
        val dst = "DST ".toByteArray() + byteArrayOf(3) + "DST".toByteArray()
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            DsdHeaders.dffProperties("SND ".toByteArray() + chunk("FS  ", rate) + chunk("CHNL", channels) + chunk("CMPR", dst))
        }.message!!.contains("DST"))
        assertThrows(IllegalArgumentException::class.java) { DsdHeaders.dffProperties(properties + chunk("FS  ", rate)) }
        assertThrows(IllegalArgumentException::class.java) { DsdHeaders.dffProperties(properties.copyOf(properties.size - 1)) }
        val reversed = ByteBuffer.allocate(10).putShort(2).put("SRGTSLFT".toByteArray()).array()
        assertThrows(IllegalArgumentException::class.java) {
            DsdHeaders.dffProperties("SND ".toByteArray() + chunk("FS  ", rate) + chunk("CHNL", reversed) + chunk("CMPR", plain))
        }
    }

    private fun dsf(bits: Int = 1, block: Int = 4096, rate: Int = 2_822_400, samples: Long = 44_100): ByteArray =
        ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(0).putInt(2).putInt(2)
            .putInt(rate).putInt(bits).putLong(samples).putInt(block).putInt(0).array()

    private fun chunk(id: String, bytes: ByteArray): ByteArray = ByteBuffer.allocate(12 + bytes.size + bytes.size % 2)
        .put(id.toByteArray()).putLong(bytes.size.toLong()).put(bytes).array()
}
