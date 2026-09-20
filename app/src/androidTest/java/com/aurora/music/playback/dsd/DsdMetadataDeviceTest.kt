package com.aurora.music.playback.dsd

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

@UnstableApi
class DsdMetadataDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun readsTitlesSourceRateAndDurationWithoutDecodingAtEveryRate() {
        val channels = Array(2) { ByteArray(8193) { 0x69 } }
        for (rate in DsdFormat.supportedBitRates) {
            for ((extension, bytes) in listOf("dsf" to DsdFixtures.dsf(channels, rate), "dff" to DsdFixtures.dff(channels, rate))) {
                val file = File(context.cacheDir, "metadata-${UUID.randomUUID()}.$extension")
                try {
                    file.writeBytes(bytes)
                    val result = requireNotNull(DsdMetadataReader.read(context, Uri.fromFile(file)))
                    assertEquals(rate, result.source.bitRate)
                    assertEquals(extension.uppercase(), result.source.container)
                    assertEquals(2, result.source.channels)
                    assertEquals(channels[0].size * 8L * 1_000_000L / rate, result.durationUs)
                    assertEquals("${extension.uppercase()} fixture", result.metadata.title)
                    if (extension == "dff") assertEquals("Aurora tests", result.metadata.artist)
                } finally { file.delete() }
            }
        }
    }

    @Test fun rejectsNetworkUrisTruncatedFilesAndCompressedDff() {
        assertNull(DsdMetadataReader.read(context, Uri.parse("https://example.com/file.dsf")))
        val channels = arrayOf(ByteArray(1024) { 0x69 })
        for (bytes in listOf(DsdFixtures.dsf(channels).copyOf(100), DsdFixtures.dff(channels, compression = "DST "))) {
            val file = File(context.cacheDir, "invalid-${UUID.randomUUID()}.dsf")
            try {
                file.writeBytes(bytes)
                assertNull(DsdMetadataReader.read(context, Uri.fromFile(file)))
            } finally { file.delete() }
        }
    }
}
