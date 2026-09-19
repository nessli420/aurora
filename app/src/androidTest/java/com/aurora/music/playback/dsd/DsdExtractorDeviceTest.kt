package com.aurora.music.playback.dsd

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

@UnstableApi
class DsdExtractorDeviceTest {
    @Test fun dsfBothBitOrdersAndDffProduceIdenticalPcmAndSourceMetadata() {
        for (rate in listOf(2_822_400, 5_644_800)) {
            val random = Random(91)
            val source = Array(2) { ByteArray(12345).also(random::nextBytes) }
            val dsf = Harness(DsdFixtures.dsf(source, rate)); dsf.finish()
            val msb = Harness(DsdFixtures.dsf(source, rate, lsb = false)); msb.finish()
            val dff = Harness(DsdFixtures.dff(source, rate)); dff.finish()
            assertArrayEquals(dsf.output.samples.toByteArray(), msb.output.samples.toByteArray())
            assertArrayEquals(dsf.output.samples.toByteArray(), dff.output.samples.toByteArray())
            for ((harness, name) in listOf(dsf to "DSF", dff to "DFF")) {
                val format = requireNotNull(harness.output.format)
                assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
                assertEquals(C.ENCODING_PCM_FLOAT, format.pcmEncoding)
                assertEquals(176400, format.sampleRate); assertEquals(2, format.channelCount)
                val info = requireNotNull(DsdSourceInfo.from(format))
                assertEquals(name, info.container); assertEquals(rate, info.bitRate)
                assertEquals(source[0].size * 8L, info.sampleCount)
                assertEquals(source[0].size * 8L * 1_000_000 / rate, requireNotNull(harness.output.seekMap).durationUs)
                val titles = (0 until requireNotNull(format.metadata).length()).map { format.metadata!![it] }.filterIsInstance<TextInformationFrame>()
                assertTrue(titles.any { it.id == "TIT2" && it.values == listOf("$name fixture") })
                assertEquals(((source[0].size * 8L + rate / 176400 - 1) / (rate / 176400) * 8).toInt(), harness.output.samples.size())
                assertEquals(0L, harness.output.timestamps.first())
                assertTrue(harness.output.timestamps.zipWithNext().all { (first, second) -> second > first })
            }
        }
    }

    @Test fun seekPrerollProducesExactContinuousSuffixIncludingFinalPartialDsfBits() {
        val random = Random(82)
        val source = Array(2) { ByteArray(44001).also(random::nextBytes) }
        for (bytes in listOf(DsdFixtures.dsf(source, sampleCount = source[0].size * 8L - 3), DsdFixtures.dff(source))) {
            val harness = Harness(bytes); harness.finish()
            val all = harness.output.samples.toByteArray()
            for (time in listOf(0L, 17_000L, 89_123L)) {
                harness.seek(time); harness.finish()
                val firstFrame = time * 176400 / 1_000_000
                assertArrayEquals(all.copyOfRange(firstFrame.toInt() * 8, all.size), harness.output.samples.toByteArray())
                assertEquals(firstFrame * 1_000_000 / 176400, harness.output.timestamps.first())
            }
        }
    }

    @Test fun monoDsfPaddingIsNotDecodedAndMalformedContainersFailBeforeOutput() {
        val source = arrayOf(ByteArray(321) { 0xa5.toByte() })
        val harness = Harness(DsdFixtures.dsf(source, sampleCount = 2561)); harness.finish()
        assertEquals(161 * 4, harness.output.samples.size())
        assertEquals(1, harness.output.format!!.channelCount)
        val dsf = DsdFixtures.dsf(source)
        val invalid = listOf(dsf.copyOf(dsf.size - 1), DsdFixtures.dff(source, compression = "DST "),
            dsf.copyOf().apply { this[73] = 0 })
        for (bytes in invalid) {
            val rejected = Harness(bytes)
            try { rejected.finish(); fail("Malformed DSD must fail") } catch (_: ParserException) { }
            assertEquals(0, rejected.output.samples.size())
        }
    }

    private class Harness(private val bytes: ByteArray) {
        val output = Output()
        private val extractor = DsdExtractor().apply { init(output) }
        private var input = inputAt(0)
        init { assertTrue(extractor.sniff(input)); assertEquals(0L, input.position) }
        fun finish() {
            val holder = PositionHolder()
            repeat(10000) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> return
                    Extractor.RESULT_SEEK -> input = inputAt(holder.position)
                }
            }
            fail("Extractor did not terminate")
        }
        fun seek(timeUs: Long) {
            val point = requireNotNull(output.seekMap).getSeekPoints(timeUs).first
            extractor.seek(point.position, timeUs); input = inputAt(point.position)
            output.samples.reset(); output.timestamps.clear()
        }
        private fun inputAt(position: Long): DefaultExtractorInput {
            var cursor = position.toInt()
            val reader = DataReader { target, offset, length ->
                if (cursor == bytes.size) -1 else minOf(length, 37, bytes.size - cursor).also { count ->
                    bytes.copyInto(target, offset, cursor, cursor + count); cursor += count
                }
            }
            return DefaultExtractorInput(reader, position, bytes.size.toLong())
        }
    }

    private class Output : ExtractorOutput, TrackOutput {
        var format: Format? = null
        var seekMap: SeekMap? = null
        val samples = ByteArrayOutputStream()
        val timestamps = ArrayList<Long>()
        override fun track(id: Int, type: Int): TrackOutput { assertEquals(C.TRACK_TYPE_AUDIO, type); return this }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = error("Unexpected reader output")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length); data.readBytes(buffer, 0, length); samples.write(buffer)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertEquals(C.BUFFER_FLAG_KEY_FRAME, flags); assertEquals(0, offset); assertNull(cryptoData); timestamps += timeUs
        }
    }
}
