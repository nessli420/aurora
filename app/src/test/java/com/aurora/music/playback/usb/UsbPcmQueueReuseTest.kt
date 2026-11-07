package com.aurora.music.playback.usb

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class UsbPcmQueueReuseTest {
    @Test fun writtenChunksAreReusedWithoutCorruptingLaterWrites() {
        val copies = mutableListOf<ByteArray>()
        val arrays = mutableListOf<ByteArray>()
        val transport = object : UsbPcmTransport {
            override fun capabilities(source: Format) = UsbPcmCapabilities(intArrayOf(48_000), 24, 24)
            override fun start(format: Format) = Unit
            override fun write(bytes: ByteArray, encoding: Int) {
                synchronized(copies) { copies.add(bytes.copyOf()); arrays.add(bytes) }
            }
            override fun finish() = Unit
            override fun status() = UsbPcmStatus()
            override fun interrupt() = Unit
            override fun close() = Unit
        }
        val queue = UsbPcmQueue(transport, C.ENCODING_PCM_24BIT, 6)
        try {
            queue.play()
            val inputs = List(3) { round -> ByteArray(600) { (round * 31 + it).toByte() } }
            for (input in inputs) {
                assertTrue(queue.offer(ByteBuffer.wrap(input)))
                val until = System.nanoTime() + 2_000_000_000L
                while (queue.pending && System.nanoTime() < until) Thread.sleep(1)
                assertFalse(queue.pending)
            }
            synchronized(copies) {
                inputs.forEachIndexed { i, input -> assertArrayEquals(input, copies[i]) }
                assertSame(arrays[0], arrays[1])
                assertSame(arrays[1], arrays[2])
            }
        } finally {
            queue.close()
        }
    }
}
