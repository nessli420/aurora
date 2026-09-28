package com.aurora.music.playback.network.audio

import com.aurora.music.playback.engine.PcmEncoding
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NetworkWaveSourceTest {
    @Test fun oddMetadataChunksAndPcmPrecisionArePreserved() {
        for ((tag, bits, encoding) in listOf(Triple(1, 24, PcmEncoding.SIGNED_24_LE),
            Triple(1, 32, PcmEncoding.SIGNED_32_LE), Triple(3, 64, PcmEncoding.FLOAT_64_LE))) {
            val stride = bits / 8 * 2
            val bytes = ByteBuffer.allocate(54 + stride * 7).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVE".toByteArray())
                put("JUNK".toByteArray()); putInt(1); put(42); put(0)
                put("fmt ".toByteArray()); putInt(16); putShort(tag.toShort()); putShort(2)
                putInt(96_000); putInt(96_000 * stride); putShort(stride.toShort()); putShort(bits.toShort())
                put("data".toByteArray()); putInt(stride * 7)
            }.array()
            temporary(bytes) { file ->
                val source = checkNotNull(NetworkWaveSource.inspect(file))
                assertEquals(encoding, source.encoding)
                assertEquals(96_000, source.sampleRate)
                assertEquals(54L, source.dataOffset)
                assertEquals(stride * 7L, source.dataBytes)
            }
        }
    }

    @Test fun truncatedChunksAndMisalignedPcmAreRejected() {
        val bytes = NetworkWaveWriter.header(4) + byteArrayOf(0, 0, 0, 0)
        temporary(bytes.copyOf(47)) { file ->
            assertThrows(IllegalArgumentException::class.java) { NetworkWaveSource.inspect(file) }
        }
        val bad = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 3) }
        temporary(bad) { file ->
            assertThrows(IllegalArgumentException::class.java) { NetworkWaveSource.inspect(file) }
        }
    }

    @Test fun nonWaveFilesAreLeftForTheCompressedDecoder() {
        temporary(ByteArray(48) { 42 }) { file -> assertNull(NetworkWaveSource.inspect(file)) }
    }

    private fun temporary(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("aurora-wave-", ".wav")
        try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }
}
