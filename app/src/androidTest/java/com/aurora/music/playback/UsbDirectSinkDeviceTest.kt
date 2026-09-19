package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.platform.app.InstrumentationRegistry
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.media3.UsbAudioSink
import com.decent.usbaudio.media3.UsbAudioSinkConfig
import com.decent.usbaudio.media3.UsbStreamingThread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class UsbDirectSinkDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val format = Format.Builder().setSampleMimeType("audio/raw").setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(48000).setChannelCount(2).build()

    private class Delegate {
        var buffers = 0
        var volume = 1f
        var configurations = 0
        val sink: AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, arguments ->
            when (method.name) {
                "configure" -> { configurations++; null }
                "handleBuffer" -> { buffers++; (arguments!![0] as ByteBuffer).apply { position(limit()) }; true }
                "setVolume" -> { volume = arguments!![0] as Float; null }
                "supportsFormat", "isEnded" -> true
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "getCurrentPositionUs" -> AudioSink.CURRENT_POSITION_NOT_SET
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as AudioSink
    }

    @Test fun unavailableUsbPausesWithoutConsumingOrSendingPcmToAndroid() {
        assumeTrue(UsbAudioDevice.getInstance(context).findUsbAudioDevice() == null)
        val delegate = Delegate()
        val reported = CountDownLatch(1)
        val sink = UsbAudioSink(delegate.sink, context, UsbAudioSinkConfig(onUsbFailure = { reported.countDown() }))
        try {
            sink.configure(format, 0, null)
            sink.setVolume(0.3f)
            val buffer = ByteBuffer.allocateDirect(16)
            assertFalse(sink.handleBuffer(buffer, 0, 1))
            assertEquals(0, buffer.position())
            assertEquals(0, delegate.buffers)
            assertEquals(0f, delegate.volume, 0f)
            assertTrue(reported.await(2, TimeUnit.SECONDS))
            assertNotNull(sink.playbackTelemetry.failure)
            sink.playToEndOfStream()
            assertFalse(sink.isEnded())
        } finally { sink.release() }
    }

    @Test fun explicitAndroidFallbackUsesDelegateAndItsSoftwareGain() {
        assumeTrue(UsbAudioDevice.getInstance(context).findUsbAudioDevice() == null)
        val delegate = Delegate()
        val sink = UsbAudioSink(delegate.sink, context, UsbAudioSinkConfig(allowAndroidFallback = true))
        try {
            sink.configure(format, 0, null)
            sink.setVolume(0.25f)
            val buffer = ByteBuffer.allocateDirect(16)
            assertTrue(sink.handleBuffer(buffer, 0, 1))
            assertEquals(16, buffer.position())
            assertEquals(1, delegate.buffers)
            assertEquals(0.25f, delegate.volume, 0f)
            assertFalse(sink.playbackTelemetry.usbActive)
            assertNotNull(sink.playbackTelemetry.failure)
        } finally { sink.release() }
    }

    @Test fun pausedFailureRetriesOnceOnPlayWithoutConsumingTheRetainedBuffer() {
        assumeTrue(UsbAudioDevice.getInstance(context).findUsbAudioDevice() == null)
        val delegate = Delegate()
        val failures = AtomicInteger()
        val sink = UsbAudioSink(delegate.sink, context, UsbAudioSinkConfig(onUsbFailure = { failures.incrementAndGet() }))
        try {
            sink.configure(format, 4096, intArrayOf(0, 1))
            await { failures.get() == 1 }
            val buffer = ByteBuffer.allocateDirect(16)
            assertFalse(sink.handleBuffer(buffer, 0, 1))
            assertEquals(1, delegate.configurations)
            sink.play()
            await { failures.get() == 2 }
            repeat(8) { assertFalse(sink.handleBuffer(buffer, 0, 1)) }
            sink.play()
            assertEquals(2, delegate.configurations)
            assertEquals(0, buffer.position())
            assertEquals(0, delegate.buffers)
            sink.pause()
            sink.play()
            await { failures.get() == 3 }
            assertEquals(3, delegate.configurations)
            assertEquals(0, buffer.position())
        } finally { sink.release() }
    }

    private open class Output : UsbStreamingThread.Output {
        val events = CopyOnWriteArrayList<String>()
        val finishing = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        override fun write(data: FloatArray): Boolean { events += "${data[0].toInt()}"; return true }
        override fun writeRaw(data: ByteArray, encoding: Int): Boolean { events += "${data[0]}"; return true }
        override fun finish(): Boolean { finishing.countDown(); finishRelease.await(2, TimeUnit.SECONDS); events += "end"; return true }
        override fun flush() { events += "flush" }
        override fun stop() { finishRelease.countDown() }
    }

    @Test fun queueBackpressureKeepsEveryAcceptedBufferAndWaitsForTransportDrain() {
        val output = Output()
        val worker = UsbStreamingThread(output, capacity = 2)
        worker.start()
        try {
            assertTrue(worker.enqueueRaw(byteArrayOf(1), C.ENCODING_PCM_16BIT))
            assertTrue(worker.enqueueRaw(byteArrayOf(2), C.ENCODING_PCM_16BIT))
            assertFalse(worker.enqueueRaw(byteArrayOf(3), C.ENCODING_PCM_16BIT))
            worker.endOfStream()
            worker.resumeStreaming()
            assertTrue(output.finishing.await(2, TimeUnit.SECONDS))
            assertTrue(worker.hasPendingData())
            assertFalse(worker.isDrained())
            assertEquals(listOf("1", "2"), output.events.toList())
            output.finishRelease.countDown()
            await { worker.isDrained() }
            assertEquals(listOf("1", "2", "end"), output.events.toList())
            assertFalse(worker.hasPendingData())
        } finally { assertTrue(worker.stop()) }
    }

    @Test fun flushDiscardsOnlyOldGenerationAndSerializesBeforeNewPcm() {
        val entered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val flushing = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val output = object : Output() {
            override fun writeRaw(data: ByteArray, encoding: Int): Boolean {
                if (data[0] == 1.toByte()) { entered.countDown(); releaseWrite.await(2, TimeUnit.SECONDS) }
                return super.writeRaw(data, encoding)
            }
            override fun flush() { super.flush(); flushing.countDown(); releaseFlush.await(2, TimeUnit.SECONDS) }
            override fun stop() { releaseWrite.countDown(); releaseFlush.countDown(); super.stop() }
        }
        val worker = UsbStreamingThread(output)
        worker.start()
        worker.enqueueRaw(byteArrayOf(1), C.ENCODING_PCM_16BIT)
        worker.enqueueRaw(byteArrayOf(2), C.ENCODING_PCM_16BIT)
        worker.resumeStreaming()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val flush = Thread { worker.flush() }.apply { start() }
        try {
            await { worker.queueSize() == 0 }
            releaseWrite.countDown()
            assertTrue(flushing.await(2, TimeUnit.SECONDS))
            assertTrue(worker.enqueueRaw(byteArrayOf(3), C.ENCODING_PCM_16BIT))
            worker.endOfStream()
            releaseFlush.countDown()
            assertTrue(output.finishing.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("1", "flush", "3"), output.events.toList())
            output.finishRelease.countDown()
            await { worker.isDrained() }
        } finally { output.stop(); flush.join(2000); assertTrue(worker.stop()) }
    }

    @Test fun writeFailureIsReportedAndNeverBecomesCleanEndOfStream() {
        val failed = CountDownLatch(1)
        val output = object : Output() { override fun write(data: FloatArray) = false }
        val worker = UsbStreamingThread(output, { failed.countDown() })
        worker.start()
        try {
            worker.enqueue(floatArrayOf(1f))
            worker.endOfStream()
            worker.resumeStreaming()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertNotNull(worker.failure)
            assertFalse(worker.isDrained())
            assertFalse(worker.enqueue(floatArrayOf(2f)))
            assertTrue(output.events.isEmpty())
        } finally { assertTrue(worker.stop()) }
    }

    @Test fun workerPreservesTheNativeFailureReason() {
        val failed = CountDownLatch(1)
        val output = object : Output() {
            override fun write(data: FloatArray) = false
            override fun failureReason() = "USB transfer failed (error 5)."
        }
        val worker = UsbStreamingThread(output, { failed.countDown() })
        worker.start()
        try {
            assertTrue(worker.enqueue(floatArrayOf(1f)))
            worker.resumeStreaming()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertEquals("USB transfer failed (error 5).", worker.failure)
        } finally { assertTrue(worker.stop()) }
    }

    @Test fun flushAfterDrainAllowsAnotherGenerationToWriteAndFinish() {
        val output = object : Output() {
            private var active = true
            override fun writeRaw(data: ByteArray, encoding: Int): Boolean = active && super.writeRaw(data, encoding)
            override fun finish(): Boolean { active = false; events += "end"; return true }
            override fun flush() { active = true; super.flush() }
        }
        val worker = UsbStreamingThread(output)
        worker.start()
        try {
            worker.enqueueRaw(byteArrayOf(1), C.ENCODING_PCM_16BIT)
            worker.endOfStream(); worker.resumeStreaming()
            await { worker.isDrained() }
            worker.flush()
            assertFalse(worker.isDrained())
            assertTrue(worker.enqueueRaw(byteArrayOf(2), C.ENCODING_PCM_16BIT))
            worker.endOfStream()
            await { worker.isDrained() }
            assertNull(worker.failure)
            assertEquals(listOf("1", "end", "flush", "2", "end"), output.events.toList())
        } finally { assertTrue(worker.stop()) }
    }

    @Test fun timedOutStopRetainsWorkerUntilItsWriteExits() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val output = object : Output() {
            override fun write(data: FloatArray): Boolean {
                entered.countDown()
                unblock.await(5, TimeUnit.SECONDS)
                exited.countDown()
                return true
            }
            override fun stop() = Unit
        }
        val worker = UsbStreamingThread(output)
        worker.start()
        try {
            worker.enqueue(floatArrayOf(1f))
            worker.resumeStreaming()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.stop(20))
            assertFalse(worker.stop(20))
            assertEquals(1L, exited.count)
            unblock.countDown()
            worker.awaitStopped()
            assertEquals(0L, exited.count)
            assertTrue(worker.stop())
        } finally { unblock.countDown(); worker.awaitStopped(); worker.stop() }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate())
    }
}
