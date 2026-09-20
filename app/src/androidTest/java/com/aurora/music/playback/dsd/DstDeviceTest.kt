package com.aurora.music.playback.dsd

import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Random

@UnstableApi
class DstDeviceTest {
    @Test fun uncodedFramesPreserveEveryByteAndRejectInvalidSizes() {
        for (channels in 1..2) DstDecoder(channels).use { decoder ->
            val source = ByteArray(decoder.frameBytes).also(Random(55)::nextBytes)
            val output = ByteArray(source.size)
            decoder.decode(byteArrayOf(0) + source, output)
            assertArrayEquals(source, output)
            for (bad in listOf(byteArrayOf(0, 0), byteArrayOf(1) + source,
                byteArrayOf(0) + source.copyOf(source.size - 1), byteArrayOf(0) + source + byteArrayOf(0))) {
                try { decoder.decode(bad, output); fail("Invalid frame accepted") } catch (_: IllegalArgumentException) { }
            }
            try { decoder.decode(byteArrayOf(0x80.toByte(), 0), output); fail("Segmentation accepted") }
            catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun indexedDstWithCrcMatchesUncompressedDffAndSeeksExactly() {
        val channels = Array(2) { ByteArray(4704 * 4).also(Random(70L + it)::nextBytes) }
        val interleaved = ByteArray(channels[0].size * 2) { channels[it % 2][it / 2] }
        for (indexed in listOf(false, true)) for (raw in listOf(false, true)) {
            DsdExtractorDeviceTest.Harness(DsdFixtures.dff(channels), raw).use { reference ->
                reference.finish()
                DsdExtractorDeviceTest.Harness(dst(interleaved, indexed), raw).use { compressed ->
                    compressed.finish()
                    assertArrayEquals(reference.output.samples.toByteArray(), compressed.output.samples.toByteArray())
                    assertEquals(reference.output.seekMap!!.durationUs, compressed.output.seekMap!!.durationUs)
                    assertEquals("DFF / DST", DsdSourceInfo.from(compressed.output.format)!!.container)
                    for (time in listOf(0L, 13000L, 26789L, 45000L, reference.output.seekMap!!.durationUs)) {
                        reference.seek(time); reference.finish()
                        compressed.seek(time); compressed.finish()
                        assertArrayEquals("seek=$time raw=$raw", reference.output.samples.toByteArray(), compressed.output.samples.toByteArray())
                    }
                }
            }
        }
    }

    @Test fun damagedCrcIndexCountAndTruncatedFrameFail() {
        val data = ByteArray(9408 * 2).also(Random(71)::nextBytes)
        val valid = dst(data, true)
        fun mutate(id: String, offset: Int): ByteArray = valid.copyOf().apply {
            val at = find(this, id) + 12 + offset
            this[at] = (this[at].toInt() xor 1).toByte()
        }
        for (bad in listOf(mutate("DSTC", 0), mutate("DSTI", 7), mutate("FRTE", 3), valid.copyOf(valid.size - 1))) {
            DsdExtractorDeviceTest.Harness(bad).use { harness ->
                try { harness.finish(); fail("Damaged DST accepted") } catch (_: ParserException) { }
            }
        }
    }

    @Test fun externalCompressedCorpusExportsReferenceDsd() {
        val path = InstrumentationRegistry.getArguments().getString("dstCorpus")
        assumeTrue(path != null)
        val input = File(path!!).readBytes()
        val firstFrame = find(input, "DSTF")
        val frameSize = ByteBuffer.wrap(input, firstFrame + 4, 8).long.toInt()
        val compressed = input.copyOfRange(firstFrame + 12, firstFrame + 12 + frameSize)
        DstDecoder(2).use { decoder ->
            val output = ByteArray(9408)
            for (size in listOf(2, 10, compressed.size / 2, compressed.size - 20)) {
                try { decoder.decode(compressed.copyOf(size), output); fail("Truncated entropy accepted: $size") }
                catch (_: IllegalArgumentException) { }
            }
            decoder.decode(compressed, output)
        }
        DsdExtractorDeviceTest.Harness(input, raw = true).use { harness ->
            harness.finish()
            val all = harness.output.samples.toByteArray()
            assertEquals(94080, all.size)
            File("$path.raw").writeBytes(all)
            val channels = Array(2) { ch -> ByteArray(all.size / 2) { all[it * 2 + ch] } }
            File("$path.decoded.dff").writeBytes(DsdFixtures.dff(channels))
            for (time in listOf(0L, 14000L, 57000L, 120000L)) {
                harness.seek(time); harness.finish()
                val first = time * 176400 / 1_000_000 * 2 * 2
                assertArrayEquals(all.copyOfRange(first.toInt(), all.size), harness.output.samples.toByteArray())
            }
        }
    }

    private fun dst(data: ByteArray, indexed: Boolean): ByteArray {
        val props = "SND ".toByteArray() + chunk("FS  ", ByteBuffer.allocate(4).putInt(2822400).array()) +
            chunk("CHNL", byteArrayOf(0, 2) + "SLFTSRGT".toByteArray()) + chunk("CMPR", "DST ".toByteArray() + byteArrayOf(0))
        val prefix = "DSD ".toByteArray() + chunk("FVER", byteArrayOf(1, 5, 0, 0)) + chunk("PROP", props)
        val frames = ByteArrayOutputStream()
        frames.write(chunk("FRTE", ByteBuffer.allocate(6).putInt(data.size / 9408).putShort(75).array()))
        val index = ByteArrayOutputStream()
        repeat(data.size / 9408) { number ->
            val source = data.copyOfRange(number * 9408, (number + 1) * 9408)
            val packed = byteArrayOf(0) + source
            index.write(ByteBuffer.allocate(12).putLong(12L + prefix.size + 12 + frames.size() + 12).putInt(packed.size).array())
            frames.write(chunk("DSTF", packed))
            frames.write(chunk("DSTC", ByteBuffer.allocate(4).putInt(DstFrameCrc.calculate(source)).array()))
        }
        val body = prefix + chunk("DST ", frames.toByteArray()) + if (indexed) chunk("DSTI", index.toByteArray()) else byteArrayOf()
        return "FRM8".toByteArray() + ByteBuffer.allocate(8).putLong(body.size.toLong()).array() + body
    }

    private fun chunk(id: String, bytes: ByteArray) = id.toByteArray() + ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array() +
        bytes + if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

    private fun find(bytes: ByteArray, id: String): Int = (0..bytes.size - 4).first { at ->
        id.indices.all { bytes[at + it] == id[it].code.toByte() }
    }
}
