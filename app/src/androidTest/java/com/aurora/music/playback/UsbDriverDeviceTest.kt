package com.aurora.music.playback

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.decent.usbaudio.NativeAudioEngine
import com.decent.usbaudio.UsbAudioStream
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

private object UsbDriverTestSupport {
    init { System.loadLibrary("decent_usb_audio") }
    external fun packetize(pcm: ByteArray, rate: Int, channels: Int, bits: Int, chunkFrames: Int, wireFormat: Int = 0): ByteArray?
    external fun completeOutOfOrder(errorMode: Int): LongArray
    external fun flushLifecycle(): LongArray
    external fun resetInterface(errorMode: Int): LongArray
    external fun convertFloat(samples: FloatArray, validBits: Int, containerBits: Int): ByteArray?
    external fun seekPublication(): Boolean
    external fun readSource(fd: Int, offset: Long, size: Int): Int
}

class UsbDriverDeviceTest {
    @Test fun experimentalDsdRatesPreservePayloadAndMarkersAcrossLargeUsbPackets() {
        for (wire in 1..2) for (rate in if (wire == 1) listOf(705600, 1411200, 2822400) else listOf(705600, 1411200)) {
            for (bits in if (wire == 1) listOf(24, 32) else listOf(32)) for (frames in listOf(1, 10003)) {
                val bytes = bits / 8
                val input = ByteArray(frames * 2 * bytes) { at ->
                    if (wire == 1 && at % bytes == bytes - 1) (if (at / (2 * bytes) % 2 == 0) 5 else 0xfa).toByte()
                    else if (wire == 1 && bytes == 4 && at % bytes == 0) 0 else (at * 73).toByte()
                }
                val whole = requireNotNull(UsbDriverTestSupport.packetize(input, rate, 2, bits, 65536, wire))
                for (chunk in listOf(1, 113, 997)) {
                    assertArrayEquals(whole, UsbDriverTestSupport.packetize(input, rate, 2, bits, chunk, wire))
                }
                assertArrayEquals(input, whole.copyOf(input.size))
                for (at in input.size until whole.size) {
                    val expected = if (wire == 1 && at % bytes == bytes - 1) {
                        if (at / (2 * bytes) % 2 == 0) 5 else 0xfa
                    } else if (wire == 1 && bytes == 4 && at % bytes == 0) 0 else 0x69
                    assertEquals(expected, whole[at].toInt() and 255)
                }
            }
        }
        assertNull(UsbDriverTestSupport.packetize(ByteArray(8), 705600, 2, 32, 1, 0))
        assertNull(UsbDriverTestSupport.packetize(ByteArray(8), 2822400, 2, 32, 1, 2))
    }

    @Test fun rawDsdPacketsPreserveMarkersAndUseFormatSpecificTailPadding() {
        for (wire in 1..2) for (bits in if (wire == 1) listOf(24, 32) else listOf(32)) for (frames in listOf(1, 31, 176, 177, 997)) {
            val bytes = bits / 8
            val input = ByteArray(frames * 2 * bytes) { at ->
                if (wire == 1 && at % bytes == bytes - 1) (if (at / (2 * bytes) % 2 == 0) 5 else 0xfa).toByte()
                else if (wire == 1 && bytes == 4 && at % bytes == 0) 0 else (at * 73).toByte()
            }
            for (chunk in listOf(1, 7, 177, 65536)) {
                val actual = requireNotNull(UsbDriverTestSupport.packetize(input, 176400, 2, bits, chunk, wire))
                assertArrayEquals(input, actual.copyOf(input.size))
                for (at in input.size until actual.size) {
                    val expected = if (wire == 1 && at % bytes == bytes - 1) {
                        if (at / (2 * bytes) % 2 == 0) 5 else 0xfa
                    } else if (wire == 1 && bytes == 4 && at % bytes == 0) 0 else 0x69
                    assertEquals("wire=$wire bits=$bits at=$at", expected, actual[at].toInt() and 255)
                }
            }
        }
    }

    @Test fun seekReopensOnlyTheVerifiedInterfaceAfterAllTransfersHaveReturned() {
        assertArrayEquals(longArrayOf(1, 0, 2, 0, 3, 2, 44100), UsbDriverTestSupport.resetInterface(0))
        assertArrayEquals(longArrayOf(0, 5, 1, 0, -1, 1, 0), UsbDriverTestSupport.resetInterface(1))
        assertArrayEquals(longArrayOf(0, 5, 2, 0, 3, 1, 0), UsbDriverTestSupport.resetInterface(2))
        assertArrayEquals(longArrayOf(0, 16, 0, -1, -1, 0, 0), UsbDriverTestSupport.resetInterface(3))
        assertArrayEquals(longArrayOf(0, 71, 2, 0, 3, 2, 0), UsbDriverTestSupport.resetInterface(4))
        assertArrayEquals(longArrayOf(0, 16, 0, -1, -1, 0, 0), UsbDriverTestSupport.resetInterface(5))
        assertArrayEquals(longArrayOf(0, 71, 0, -1, -1, 1, 0), UsbDriverTestSupport.resetInterface(6))
    }

    @Test fun flushRestartsACleanlyDrainedStreamButNeverAnExplicitlyStoppedOrFailedOne() {
        assertArrayEquals(longArrayOf(1, 1, 1, 1, 0, 0, 0, 1, 1), UsbDriverTestSupport.flushLifecycle())
    }

    @Test fun nativeSeekPublishesAConsistentClockBeforeTheDecodeThreadConsumesItsTarget() {
        assertTrue(UsbDriverTestSupport.seekPublication())
    }

    @Test fun nativeFileReadFailureIsDistinctFromEndOfStream() {
        assertEquals(-1, UsbDriverTestSupport.readSource(-1, 0, 16))
        val file = File.createTempFile("usb-source-", ".bin", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(ByteArray(17) { it.toByte() })
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                assertEquals(17, UsbDriverTestSupport.readSource(source.fd, 0, 64))
                assertEquals(0, UsbDriverTestSupport.readSource(source.fd, 17, 64))
                assertEquals(-1, UsbDriverTestSupport.readSource(source.fd, -1, 16))
            }
        } finally { file.delete() }
    }

    @Test fun nativeConversionRespectsValidBitsWithinTheDacContainer() {
        val bytes = requireNotNull(UsbDriverTestSupport.convertFloat(floatArrayOf(0f, -1f, 1f, .5f, Float.NaN, Float.POSITIVE_INFINITY), 24, 32))
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        assertArrayEquals(intArrayOf(0, Int.MIN_VALUE, 0x7fffff00, 0x40000000, 0, 0), IntArray(samples.remaining()).also(samples::get))
        for (offset in bytes.indices step 4) assertEquals(0, bytes[offset].toInt())
    }
    @Test fun nativeCompletionCountsOnlyConfirmedFramesAndChecksPacketErrors() {
        assertArrayEquals(longArrayOf(11, 0, 0, 0), UsbDriverTestSupport.completeOutOfOrder(0))
        assertArrayEquals(longArrayOf(8, 1, 5, 0), UsbDriverTestSupport.completeOutOfOrder(1))
        assertArrayEquals(longArrayOf(8, 1, 19, 0), UsbDriverTestSupport.completeOutOfOrder(2))
        assertArrayEquals(longArrayOf(8, 1, 5, 0), UsbDriverTestSupport.completeOutOfOrder(3))
    }
    @Test fun nativePacketizationPreservesEveryFrameAcrossArbitraryWriteBoundariesAndEos() {
        for (rate in listOf(44100, 48000, 96000, 192000, 384000)) for (bits in listOf(16, 24, 32)) {
            val bytesPerFrame = 2 * bits / 8
            val input = ByteArray(997 * bytesPerFrame) { (it * 73 + 19).toByte() }
            val whole = requireNotNull(UsbDriverTestSupport.packetize(input, rate, 2, bits, 65536))
            for (chunk in listOf(1, 7, 23, 127, 253)) {
                val actual = requireNotNull(UsbDriverTestSupport.packetize(input, rate, 2, bits, chunk))
                assertArrayEquals("$rate/$bits/$chunk", whole, actual)
                assertArrayEquals(input, actual.copyOf(input.size))
                assertTrue(actual.drop(input.size).all { it == 0.toByte() })
                assertTrue(actual.size - input.size < rate / 1000 * bytesPerFrame + bytesPerFrame)
            }
        }
    }

    @Test fun invalidUsbHandleAndUncreatedEngineAreSafeAndNeverClaimSuccess() {
        assertTrue(UsbAudioStream.nativeGetUsbSpeed(-1) < 0)
        val stream = UsbAudioStream(-1, 1, 1, -1, 48000, 2, 32, 512, 24, alternateSetting = 1)
        assertFalse(stream.isReady); assertFalse(stream.isAlive); assertFalse(stream.start()); assertFalse(stream.finish())
        stream.flush(); stream.stop(); stream.release(); stream.release()
        assertEquals(0L, stream.telemetry.completedFrames)
        val engine = NativeAudioEngine()
        assertFalse(engine.completed); assertNull(engine.error); assertFalse(engine.isRunning)
        engine.pause(); engine.resume(); engine.stop(); engine.destroy(); engine.destroy()
        assertFalse(engine.completed)
    }
}
