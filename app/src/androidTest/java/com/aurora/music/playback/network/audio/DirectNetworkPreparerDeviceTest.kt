package com.aurora.music.playback.network.audio

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(UnstableApi::class)
class DirectNetworkPreparerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun directWaveRetainsEverySourceByteAndSniffsTheActualContainer() = runBlocking {
        temporary { directory ->
            val source = wave(8192, 41)
            val item = MediaItem.Builder().setUri("https://source.invalid/song.mp3?token=private")
                .setMimeType("audio/mpeg").build()
            val result = DirectNetworkPreparer.prepare(context, item, DataSource.Factory { ByteArrayDataSource(source) }, directory)
            assertArrayEquals(source, result.file.readBytes())
            assertEquals("audio/wav", result.mimeType)
            assertEquals(8192 * 1000L / 48_000, result.durationMs)
            assertEquals(listOf(result.file.name), directory.listFiles()!!.map { it.name })
            assertFalse(result.toString().contains("token=private"))
        }
    }

    @Test fun directAacKeepsTheCompressedFileAndReportsItsContainer() = runBlocking {
        temporary { directory ->
            val sourceFile = File(directory, "fixture.m4a")
            val fixture = ProcessedNetworkRendererDeviceTest().encodeAac(sourceFile)
            val source = sourceFile.readBytes()
            sourceFile.delete()
            val result = DirectNetworkPreparer.prepare(context, MediaItem.fromUri("memory://source"),
                DataSource.Factory { ByteArrayDataSource(source) }, directory)
            assertEquals("audio/mp4", result.mimeType)
            assertEquals(fixture.description, fixture.durationMs, result.durationMs)
            assertArrayEquals(source, result.file.readBytes())
        }
    }

    @Test fun directFlacRetainsAllCompressedBytesAndReadsPrecisionMetadata() = runBlocking {
        temporary { directory ->
            val source = InstrumentationRegistry.getInstrumentation().context.assets.open("network/lowbits-24.flac").use { it.readBytes() }
            val result = DirectNetworkPreparer.prepare(context, MediaItem.fromUri("memory://flac"),
                DataSource.Factory { ByteArrayDataSource(source) }, directory)
            assertEquals("audio/flac", result.mimeType)
            assertEquals(4096 * 1000L / 48_000, result.durationMs)
            assertArrayEquals(source, result.file.readBytes())
        }
    }

    @Test fun directOpusRetainsTheSupportedContainerAndEverySourceByte() = runBlocking {
        temporary { directory ->
            val source = InstrumentationRegistry.getInstrumentation().context.assets.open("network/gapless.opus").use { it.readBytes() }
            val result = DirectNetworkPreparer.prepare(context, MediaItem.fromUri("memory://opus"),
                DataSource.Factory { ByteArrayDataSource(source) }, directory)
            assertEquals("audio/ogg", result.mimeType)
            assertArrayEquals(source, result.file.readBytes())
        }
    }

    @Test fun cancelledCopyAndChangedSourceDoNotLeaveStaleBytes() = runBlocking {
        temporary { directory ->
            val opened = mutableListOf<String>()
            fun factory(bytes: ByteArray) = DataSource.Factory {
                val delegate = ByteArrayDataSource(bytes)
                object : DataSource by delegate {
                    override fun open(dataSpec: DataSpec): Long {
                        opened += dataSpec.uri.toString()
                        return delegate.open(dataSpec)
                    }
                }
            }
            val first = MediaItem.fromUri("https://first.invalid?secret=first")
            var cancelled = false
            try {
                DirectNetworkPreparer.prepare(context, first, factory(wave(48_000, 3)), directory,
                    onProgress = { throw CancellationException("cancelled") })
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertTrue(directory.listFiles()!!.isEmpty())
            val second = MediaItem.fromUri("https://second.invalid?secret=second")
            val source = wave(96, 19)
            val result = DirectNetworkPreparer.prepare(context, second, factory(source), directory)
            assertArrayEquals(source, result.file.readBytes())
            assertEquals(listOf(first.localConfiguration!!.uri.toString(), second.localConfiguration!!.uri.toString()), opened)
            assertEquals(listOf(result.file.name), directory.listFiles()!!.map { it.name })
        }
    }

    @Test fun directSourceErrorsAreSanitizedAndManifestsAreRejected() = runBlocking {
        temporary { directory ->
            val failure = runCatching {
                DirectNetworkPreparer.prepare(context, MediaItem.fromUri("https://private.invalid?password=secret"),
                    DataSource.Factory { throw IOException("https://private.invalid?password=secret") }, directory)
            }.exceptionOrNull()
            assertTrue(failure is NetworkRenderingException)
            assertNull(failure!!.cause)
            assertFalse(failure.toString().contains("secret"))
            val manifest = "#EXTM3U\n#EXTINF:12,\nhttps://private.invalid?password=secret".toByteArray()
            val unsupported = runCatching {
                DirectNetworkPreparer.prepare(context, MediaItem.fromUri("memory://stream"),
                    DataSource.Factory { ByteArrayDataSource(manifest) }, directory)
            }.exceptionOrNull()
            assertTrue(unsupported is NetworkRenderingException)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    private fun wave(frames: Int, marker: Int) = NetworkWaveWriter.header(frames * 4L) + ByteArray(frames * 4) { (it * marker).toByte() }

    private suspend fun temporary(block: suspend (File) -> Unit) {
        val directory = File(context.cacheDir, "direct-network-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
