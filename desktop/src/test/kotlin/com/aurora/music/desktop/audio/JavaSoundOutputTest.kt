package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.OutputEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine

class JavaSoundOutputTest {
    private fun floats(vararg samples: Float): ByteArray =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach(::putFloat) }.array()

    private fun shorts(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buffer.getShort() }
    }

    @Test fun floatSamplesAreQuantizedAndOnlyWhatFitsIsAccepted() {
        val line = FakeLine(AudioFormat(48_000f, 16, 2, true, false), bufferFrames = 2)
        val output = JavaSoundOutput(line, "default", 48_000)
        val accepted = output.write(floats(1f, -1f, 0.5f, -2f, 0.25f, 0.25f), 0, 24, 0)
        assertEquals(16, accepted)
        assertArrayEquals(shortArrayOf(32767, -32767, 16384, -32767), shorts(line.heard()))
        assertEquals(OutputEncoding.F32, output.encoding)
        assertFalse(output.exclusive)
    }

    @Test fun thirtyTwoBitLinesKeepFloatPrecision() {
        val line = FakeLine(AudioFormat(48_000f, 32, 2, true, false), bufferFrames = 8)
        JavaSoundOutput(line, null, 48_000).write(floats(0.5f, -0.5f), 0, 8, 0)
        val ints = ByteBuffer.wrap(line.heard()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1_073_741_824, ints.getInt())
        assertEquals(-1_073_741_823, ints.getInt())
    }

    @Test fun statusFollowsTheLineAndFlushStartsOver() {
        val line = FakeLine(AudioFormat(44_100f, 16, 2, true, false), bufferFrames = 16)
        val output = JavaSoundOutput(line, null, 44_100)
        output.write(floats(*FloatArray(8)), 0, 32, 0)
        output.resume()
        line.play(3)
        output.status().let {
            assertEquals(3, it.framesPlayed)
            assertEquals(1, it.framesBuffered)
            assertTrue(it.playing)
        }
        output.flush()
        output.status().let {
            assertEquals(0, it.framesPlayed)
            assertEquals(0, it.framesBuffered)
            assertFalse(it.playing)
        }
        output.write(floats(*FloatArray(4)), 0, 16, 0)
        output.resume()
        line.play(2)
        assertEquals(2, output.status().framesPlayed)
        output.close()
        assertFalse(line.isOpen)
        assertThrows(IllegalStateException::class.java) { output.status() }
    }

    @Test fun theBackendOnlyOffersSharedFloatOutput() {
        assertFalse(JavaSoundBackend.supportsExclusive(null, 48_000, OutputEncoding.S24_IN_32))
        assertThrows(IllegalArgumentException::class.java) { JavaSoundBackend.open(null, true, 48_000, OutputEncoding.F32, 200) }
        assertThrows(IllegalArgumentException::class.java) { JavaSoundBackend.open(null, false, 48_000, OutputEncoding.S16, 200) }
    }

    private class FakeLine(private val format: AudioFormat, bufferFrames: Int) : SourceDataLine {
        private val capacity = bufferFrames * format.frameSize
        private val heard = ByteArrayOutputStream()
        private var written = 0L
        private var buffered = 0
        private var running = false
        private var open = true

        fun heard(): ByteArray = heard.toByteArray()

        fun play(frames: Int) {
            buffered -= minOf(buffered, frames * format.frameSize)
        }

        override fun write(b: ByteArray, off: Int, len: Int): Int {
            check(len <= available())
            heard.write(b, off, len)
            written += len
            buffered += len
            return len
        }

        override fun available() = capacity - buffered
        override fun getLongFramePosition() = (written - buffered) / format.frameSize
        override fun getFramePosition() = longFramePosition.toInt()
        override fun getMicrosecondPosition() = longFramePosition * 1_000_000 / format.sampleRate.toLong()
        override fun getBufferSize() = capacity
        override fun getFormat() = format
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun flush() { buffered = 0 }
        override fun drain() { buffered = 0 }
        override fun isRunning() = running
        override fun isActive() = running
        override fun getLevel() = -1f
        override fun open(format: AudioFormat, bufferSize: Int) { open = true }
        override fun open(format: AudioFormat) { open = true }
        override fun open() { open = true }
        override fun close() { open = false }
        override fun isOpen() = open
        override fun getLineInfo() = DataLine.Info(SourceDataLine::class.java, format)
        override fun getControls() = emptyArray<Control>()
        override fun isControlSupported(control: Control.Type) = false
        override fun getControl(control: Control.Type): Control = throw IllegalArgumentException()
        override fun addLineListener(listener: LineListener) {}
        override fun removeLineListener(listener: LineListener) {}
    }
}
