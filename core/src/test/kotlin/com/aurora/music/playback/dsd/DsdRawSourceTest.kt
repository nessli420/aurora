package com.aurora.music.playback.dsd

import org.junit.Assert.*
import org.junit.Test

class DsdRawSourceTest {
    @Test fun dsfOrdersChannelsAndBitsWithoutIncludingPadding() {
        for (lsb in listOf(false, true)) for (channels in 1..2) {
            val format = DsdFormat(DsdContainer.DSF, 45_158_400, channels, 32781, 92, 8192L * channels, lsb)
            val source = DsdRawSource(format)
            val values = Array(channels) { channel -> ByteArray(4098) { (it * 17 + channel * 37).toByte() } }
            repeat(2) { block ->
                assertEquals(92L + block * 4096L * channels, source.filePosition)
                val bytes = ByteArray(4096 * channels) { at ->
                    val value = values[at / 4096].getOrNull(block * 4096 + at % 4096)?.toInt()?.and(255) ?: 0xef
                    (if (lsb) Integer.reverse(value) ushr 24 else value).toByte()
                }
                val raw = source.consume(bytes, bytes.size)
                assertEquals(block * 32768L, raw.firstSample)
                assertEquals(if (block == 0) 32768L else 13L, raw.sampleCount)
                assertEquals((if (block == 0) 4096 else 2) * channels, raw.size)
                for (index in 0 until raw.size) assertEquals(values[index % channels][block * 4096 + index / channels], raw.bytes[index])
            }
            assertTrue(source.ended)
            assertEquals(0, source.readSize)
            assertThrows(IllegalArgumentException::class.java) { source.consume(byteArrayOf(), 0) }
            source.seek(4096)
            assertFalse(source.ended)
            assertEquals(92L + 4096 * channels, source.filePosition)
        }
    }

    @Test fun dffIsBoundedAndKeepsInterleavedBytesExactly() {
        val f = DsdFormat(DsdContainer.DFF, 45_158_400, 2, 80_000, 64, 20000)
        val source = DsdRawSource(f)
        val bytes = ByteArray(20000) { it.toByte() }
        var offset = 0
        while (!source.ended) {
            val size = source.readSize
            assertTrue(size <= 8192)
            assertEquals(64L + offset, source.filePosition)
            val result = source.consume(bytes.copyOfRange(offset, offset + size), size)
            assertArrayEquals(bytes.copyOfRange(offset, offset + size), result.bytes.copyOf(result.size))
            offset += size
        }
        assertEquals(bytes.size, offset)
        source.seek(13)
        assertEquals(90L, source.filePosition)
    }

    @Test fun incompleteBlocksAndUnalignedDsfSeeksFail() {
        val source = DsdRawSource(DsdFormat(DsdContainer.DSF, 2_822_400, 2, 9, 92, 8192))
        assertThrows(IllegalArgumentException::class.java) { source.consume(ByteArray(8192), 8191) }
        assertThrows(IllegalArgumentException::class.java) { source.consume(ByteArray(8191), 8192) }
        assertThrows(IllegalArgumentException::class.java) { source.seek(1) }
        assertThrows(IllegalArgumentException::class.java) { source.seek(-1) }
        assertEquals(0, source.bytePosition)
    }
}
