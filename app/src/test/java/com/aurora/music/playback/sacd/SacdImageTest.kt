package com.aurora.music.playback.sacd

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class SacdImageTest {
    @Test fun virtualDffReadsOnlySelectedTrackAndSupportsArbitraryByteSeeks() {
        for (format in listOf(0, 2, 3)) {
            val fixture = Fixture(2048, 0, format)
            val image = SacdImage(fixture)
            var decodes = 0
            val stream = SacdTrackStream(image, 2) { data -> decodes++; data.copyOfRange(1, data.size) }
            val all = ByteArray(stream.length.toInt())
            var at = 0
            while (at < all.size) at += stream.read(at.toLong(), all, at, minOf(37, all.size - at))
            assertEquals("FRM8", String(all, 0, 4))
            assertEquals(all.size - 12L, ByteBuffer.wrap(all, 4, 8).long)
            assertArrayEquals(fixture.audio.copyOfRange(28224, 56448), all.copyOfRange(all.size - 28224, all.size))
            assertEquals(if (format == 0) 3 else 0, decodes)
            for (position in listOf(0, 75, 9408, all.size - 15, 2048)) {
                val output = ByteArray(64)
                val count = stream.read(position.toLong(), output, 0, output.size)
                assertArrayEquals(all.copyOfRange(position, position + count), output.copyOf(count))
            }
            assertEquals(-1, stream.read(stream.length, ByteArray(1), 0, 1))
            assertThrows(IllegalArgumentException::class.java) { SacdTrackStream(image, 3) }
        }
    }
    @Test fun dstPacketsReassembleFramesAcrossSectorsAndRandomSeeks() {
        val fixture = Fixture(2048, 0, 0)
        val image = SacdImage(fixture)
        for (frame in listOf(0, 5, 1, 4, 2, 3)) {
            assertArrayEquals(byteArrayOf(0) + fixture.audio.copyOfRange(frame * 9408, (frame + 1) * 9408), image.compressedFrame(frame))
        }
        fixture.sectors[580]!![6] = 0
        assertThrows(IllegalArgumentException::class.java) { image.compressedFrame(0) }
    }

    @Test fun malformedPacketHeadersFailBeforePayloadAccess() {
        val valid = Fixture(2048, 0, 0).sectors[580]!!
        for ((offset, value) in listOf(0 to 0, 0 to 0x29, 1 to 0xff, 2 to 0xff, 5 to 75, 6 to 0x81)) {
            val invalid = valid.copyOf().apply { this[offset] = value.toByte() }
            assertThrows(IllegalArgumentException::class.java) { SacdPackets.parse(invalid) }
        }
    }
    @Test fun stereoMetadataAndFixedFramesAcrossSectorLayouts() {
        for (layout in listOf(2048 to 0, 2054 to 6, 2064 to 12)) for (format in listOf(2, 3)) {
            val fixture = Fixture(layout.first, layout.second, format)
            val image = SacdImage(fixture)
            assertEquals("Test album", image.disc.album)
            assertEquals("Test artist", image.disc.artist)
            assertEquals(listOf(0, 3), image.disc.tracks.map { it.firstFrame })
            assertEquals(listOf(3, 3), image.disc.tracks.map { it.frames })
            assertEquals("Track 1", image.disc.tracks[0].title)
            for (frame in listOf(0, 2, 5, 1, 4, 3)) assertArrayEquals(fixture.audio.copyOfRange(frame * 9408, (frame + 1) * 9408), image.rawFrame(frame))
            assertTrue(fixture.largestRead <= 2048)
            assertThrows(IllegalArgumentException::class.java) { image.rawFrame(6) }
        }
    }

    @Test fun largeImageOffsetsUseLongsAndBackupMasterTocWorks() {
        val fixture = Fixture(2064, 12, 2, 3_000_000)
        fixture.sectors[520] = fixture.sectors.remove(510)!!
        fixture.sectors[521] = fixture.sectors.remove(511)!!
        val image = SacdImage(fixture)
        assertEquals(3_000_000L, image.disc.areaStart)
        assertArrayEquals(fixture.audio.copyOfRange(47040, 56448), image.rawFrame(5))
    }

    @Test fun missingStereoMalformedTrackAndOutOfImageAreaAreRejected() {
        for (change in listOf<(Fixture) -> Unit>(
            { it.sectors[510]!!.put32(64, 0) },
            { it.sectors[540]!![32] = 6 },
            { it.sectors[540]!!.put32(76, 9000000) },
            { it.sectors[541]!!.put32(1028, 99999) },
            { it.sectors[542]!![1030] = 75 },
            { it.sectors[540]!!.put16(128, 99) },
        )) {
            val fixture = Fixture(2048, 0, 2).also(change)
            assertThrows(IllegalArgumentException::class.java) { SacdImage(fixture) }
        }
    }

    private class Fixture(private val width: Int, private val prefix: Int, format: Int, start: Int = 580) : SacdInput {
        val sectors = HashMap<Long, ByteArray>()
        val audio = ByteArray(9408 * 6) { (it * 19 + it / 29).toByte() }
        override val length = (start.toLong() + 33) * width
        var largestRead = 0
        init {
            sectors[510] = ByteArray(2048).apply {
                id("SACDMTOC"); this[8] = 1; this[9] = 20; put32(64, 540); this[138] = 2
            }
            sectors[511] = ByteArray(2048).apply {
                id("SACDText"); put16(16, 64); put16(18, 90)
                "Test album".toByteArray().copyInto(this, 64)
                "Test artist".toByteArray().copyInto(this, 90)
            }
            sectors[540] = ByteArray(2048).apply {
                id("TWOCHTOC"); this[8] = 1; this[9] = 20; put16(10, 3)
                this[20] = 4; this[21] = format.toByte(); this[32] = 2; this[69] = 2
                put32(72, start); put32(76, start + 31)
            }
            sectors[541] = ByteArray(2048).apply {
                val firstLength = if (format == 0) 15 else 14
                id("SACDTRL1"); put32(8, start); put32(12, start + firstLength)
                put32(1028, firstLength); put32(1032, 32 - firstLength)
            }
            sectors[542] = ByteArray(2048).apply {
                id("SACDTRL2"); this[14] = 3; this[1030] = 3; this[1034] = 3
            }
            val payload = if (format == 2) 2016 else 1764
            for (at in audio.indices step payload) {
                val sector = ByteArray(2048)
                audio.copyInto(sector, 2048 - payload, at, minOf(at + payload, audio.size))
                var cursor = at
                var left = payload
                val parts = ArrayList<Triple<Int, Boolean, Int>>()
                while (left > 0) {
                    val size = minOf(left, 9408 - cursor % 9408)
                    parts += Triple(size, cursor % 9408 == 0, cursor / 9408)
                    cursor += size; left -= size
                }
                val starts = parts.filter { it.second }.map { it.third }
                val count = parts.size + 1
                sector[0] = ((count shl 5) or (starts.size shl 2)).toByte()
                sector.put16(1, 0x2000 or (2048 - payload - (1 + count * 2 + starts.size * 3)))
                parts.forEachIndexed { i, p -> sector.put16(3 + i * 2, 0x1000 or p.first or if (p.second) 0x8000 else 0) }
                starts.forEachIndexed { i, frame -> sector[1 + count * 2 + i * 3 + 2] = frame.toByte() }
                sectors[start + at / payload.toLong()] = sector
            }
            if (format == 0) repeat(6) { frame ->
                val source = byteArrayOf(0) + audio.copyOfRange(frame * 9408, (frame + 1) * 9408)
                var offset = 0
                repeat(5) { part ->
                    val dataOffset = if (part == 0) 7 else 3
                    val size = minOf(2048 - dataOffset, source.size - offset)
                    sectors[start + frame * 5L + part] = ByteArray(2048).apply {
                        this[0] = if (part == 0) 0x25 else 0x21
                        put16(1, 0x1000 or size or if (part == 0) 0x8000 else 0)
                        if (part == 0) { this[5] = frame.toByte(); this[6] = 20 }
                        source.copyInto(this, dataOffset, offset, offset + size)
                    }
                    offset += size
                }
                assertEquals(source.size, offset)
            }
        }
        override fun read(position: Long, size: Int): ByteArray {
            largestRead = maxOf(largestRead, size)
            val block = sectors[position / width] ?: return ByteArray(size)
            val inside = (position % width).toInt() - prefix
            if (inside < 0 || inside + size > 2048) return ByteArray(size)
            return block.copyOfRange(inside, inside + size)
        }
    }

    companion object {
        private fun ByteArray.id(value: String) = value.toByteArray().copyInto(this)
        private fun ByteArray.put16(at: Int, value: Int) { ByteBuffer.wrap(this, at, 2).putShort(value.toShort()) }
        private fun ByteArray.put32(at: Int, value: Int) { ByteBuffer.wrap(this, at, 4).putInt(value) }
    }
}
