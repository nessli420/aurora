package com.aurora.music.playback.usb

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.aurora.music.playback.PrecisionBlockProcessor
import com.aurora.music.playback.engine.OutputRatePolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList

@UnstableApi
class ProcessedUsbAudioSinkDeviceTest {
    @Test fun encodedPassthroughIsNeverAdvertisedAsProcessedUsb() {
        val sink = sink(AndroidSink(), { Transport() })
        try {
            val encoded = Format.Builder().setSampleMimeType("audio/ac3").setChannelCount(2).setSampleRate(48000).build()
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded))
            assertFalse(sink.supportsFormat(encoded))
            assertTrue(sink.supportsFormat(format()))
        } finally { sink.reset() }
    }

    @Test fun identityMonoChannelMapProducesBothUsbChannelsWithoutLosingPrecision() {
        val fake = Transport(); val sink = sink(AndroidSink(), { fake })
        try {
            sink.configure(UsbGraphProcessorDeviceTest.format(channels = 1), 0, intArrayOf(0)); sink.play()
            val samples = IntArray(513) { it * 173 - 35003 }
            send(sink, UsbGraphProcessorDeviceTest.pcm24(samples), 0); end(sink)
            assertArrayEquals(UsbGraphProcessorDeviceTest.pcm24(IntArray(samples.size * 2) { samples[it / 2] }).array(), fake.bytes())
        } finally { sink.reset() }
    }

    @Test fun pausedQueueIsBoundedAndResumingPreservesEveryPcmFrame() {
        val fake = Transport(); val android = AndroidSink()
        val sink = sink(android, { fake })
        try {
            sink.configure(format(), 0, null)
            val expected = UsbGraphProcessorDeviceTest.pcm24(IntArray(20003 * 2) { it * 17 - 300000 }).array()
            val input = ByteBuffer.wrap(expected)
            repeat(24) { sink.handleBuffer(input, 1000000, 1) }
            assertTrue("Backpressure retains decoder input", input.hasRemaining())
            assertTrue("Paused queue stays bounded", input.position() < 65536)
            assertEquals(0, fake.bytes().size)
            assertTrue(sink.hasPendingData())
            sink.play(); send(sink, input, 1000000); end(sink)
            assertArrayEquals(expected, fake.bytes())
            assertEquals(20003L, fake.completed)
            assertFalse(sink.hasPendingData()); assertEquals(0, android.configureCalls)
        } finally { sink.reset() }
        assertTrue(fake.closed)
    }

    @Test fun playbackClockUsesCompletedFramesAndReconfigureDrainsBeforeClosing() {
        val first = Transport().apply { automaticCompletion = false }
        val second = Transport()
        val transports = ArrayDeque(listOf(first, second))
        val sink = sink(AndroidSink(), { transports.removeFirst() })
        try {
            sink.configure(format(), 0, null); sink.play()
            send(sink, pcm(1001, 123), 2000000)
            await { first.framesWritten == 1001L }
            assertEquals(2000000L, sink.getCurrentPositionUs(false))
            first.completed = 240
            assertEquals(2005000L, sink.getCurrentPositionUs(false))
            first.completed = 120
            assertEquals("Clock is monotonic", 2005000L, sink.getCurrentPositionUs(false))
            sink.configure(format(96000), 0, null)
            val next = pcm(513, -321); send(sink, next, 3000000); end(sink)
            assertTrue(first.finished); assertTrue(first.closed)
            assertEquals(1001 * 6, first.bytes().size)
            assertEquals(513 * 6, second.bytes().size)
            assertEquals(96000, second.started!!.sampleRate)
            assertEquals(3000000L + 513 * 1000000L / 96000, sink.getCurrentPositionUs(true))
        } finally { sink.reset() }
    }

    @Test fun flushDiscardsPausedPacketsAndResetsClockBeforeNewStream() {
        val first = Transport(); val second = Transport()
        val transports = ArrayDeque(listOf(first, second))
        val sink = sink(AndroidSink(), { transports.removeFirst() })
        try {
            sink.configure(format(), 0, null)
            sink.handleBuffer(pcm(500, 222), 1000000, 1)
            sink.flush()
            assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
            assertTrue(first.closed); assertEquals(0, first.bytes().size)
            sink.play(); send(sink, pcm(519, -777), 9000000); end(sink)
            assertArrayEquals(pcm(519, -777).array(), second.bytes())
        } finally { sink.reset() }
    }

    @Test fun seekTrimmingUsesMediaTimeWhilePlaybackClockRetainsTheStreamOffset() {
        val initial = Transport(); val atStart = Transport(); val later = Transport()
        val transports = ArrayDeque(listOf(initial, atStart, later))
        val android = AndroidSink()
        val sink = sink(android, { transports.removeFirst() })
        val offset = 1_000_000_000_000L
        try {
            sink.configure(format().buildUpon().setEncoderDelay(4).setEncoderPadding(3).build(), 0, null)
            sink.setOutputStreamOffsetUs(offset)
            assertEquals(offset, android.streamOffsetUs)
            sink.play(); send(sink, pcm(20, 111), offset)
            await { initial.framesWritten == 13L }
            sink.flush()
            send(sink, pcm(201, -333), offset); end(sink)
            assertArrayEquals(pcm(194, -333).array(), atStart.bytes())
            assertEquals(offset + 194 * 1000000L / 48000, sink.getCurrentPositionUs(true))
            sink.flush()
            send(sink, pcm(201, 777), offset + 9000000); end(sink)
            assertArrayEquals(pcm(198, 777).array(), later.bytes())
            assertEquals(offset + 9000000 + 198 * 1000000L / 48000, sink.getCurrentPositionUs(true))
        } finally { sink.reset() }
    }

    @Test fun initialFailureRequiresExplicitAndroidFallbackPolicy() {
        for (allowed in listOf(false, true)) {
            val android = AndroidSink()
            val fake = Transport().apply { startupFailure = true }
            val sink = sink(android, { fake }, allowed)
            try {
                if (allowed) {
                    sink.configure(format(), 0, null); send(sink, pcm(7, 231), 0); end(sink)
                    assertEquals(1, android.configureCalls); assertEquals(42, android.samples.size())
                    assertFalse(sink.telemetry.active); assertTrue(sink.telemetry.fallbackReason!!.contains("unavailable"))
                } else {
                    assertThrows(AudioSink.ConfigurationException::class.java) { sink.configure(format(), 0, null) }
                    assertEquals(0, android.configureCalls)
                }
                assertTrue(fake.closed)
            } finally { sink.reset() }
        }
    }

    @Test fun resetRetainsFailureWithoutActiveFormatUntilUsbSuccessfullyReopens() {
        val failed = Transport().apply { startupFailure = true }
        val recovered = Transport()
        val transports = ArrayDeque(listOf(failed, recovered))
        val sink = sink(AndroidSink(), { transports.removeFirst() })
        try {
            assertThrows(AudioSink.ConfigurationException::class.java) { sink.configure(format(), 0, null) }
            val reason = requireNotNull(sink.telemetry.fallbackReason)
            sink.reset()
            assertEquals(reason, sink.telemetry.fallbackReason)
            assertFalse(sink.telemetry.active); assertNull(sink.telemetry.source); assertNull(sink.telemetry.output)
            sink.configure(format(), 0, null)
            assertTrue(sink.telemetry.active); assertNull(sink.telemetry.fallbackReason)
        } finally { sink.reset() }
    }

    @Test fun transportFactoryExceptionsFollowTheSameFallbackPolicy() {
        for (allowed in listOf(false, true)) {
            val android = AndroidSink()
            val sink = sink(android, { error("USB factory unavailable") }, allowed)
            try {
                if (allowed) {
                    sink.configure(format(), 0, null); send(sink, pcm(11, 231), 0); end(sink)
                    assertEquals(1, android.configureCalls)
                    assertArrayEquals(pcm(11, 231).array(), android.samples.toByteArray())
                } else {
                    assertThrows(AudioSink.ConfigurationException::class.java) { sink.configure(format(), 0, null) }
                    assertEquals(0, android.configureCalls)
                }
                sink.reset()
                assertEquals("USB factory unavailable", sink.telemetry.fallbackReason)
            } finally { sink.reset() }
        }
    }

    @Test fun transferAndDrainFailuresNeverSilentlyCompleteOrLeakToAndroid() {
        for (atFinish in listOf(false, true)) {
            val android = AndroidSink(); val fake = Transport().apply { writeFailure = !atFinish; finishFailure = atFinish }
            val sink = sink(android, { fake })
            try {
                sink.configure(format(), 0, null); sink.play()
                var failure: AudioSink.WriteException? = null
                val input = pcm(17, 200)
                await {
                    try {
                        if (input.hasRemaining()) sink.handleBuffer(input, 0, 1)
                        else sink.playToEndOfStream()
                    } catch (error: AudioSink.WriteException) { failure = error }
                    failure != null
                }
                assertFalse(sink.isEnded()); assertEquals(0, android.configureCalls)
                assertFalse(sink.useAndroidAfterFailure()); assertNotNull(sink.telemetry.fallbackReason)
            } finally { sink.reset() }
        }
    }

    @Test fun explicitRecoveryAfterTransferFailureReopensAndroidFromNewInput() {
        val android = AndroidSink(); val failed = Transport().apply { writeFailure = true }
        val sink = sink(android, { failed }, allowed = true)
        try {
            sink.configure(format(), 0, null); sink.play()
            val first = pcm(17, 200)
            var failedWrite = false
            await {
                try { if (first.hasRemaining()) sink.handleBuffer(first, 0, 1) else sink.playToEndOfStream() }
                catch (_: AudioSink.WriteException) { failedWrite = true }
                failedWrite
            }
            assertEquals(0, android.configureCalls)
            val reason = sink.telemetry.fallbackReason
            sink.reset()
            assertEquals(reason, sink.telemetry.fallbackReason)
            assertTrue(sink.useAndroidAfterFailure()); assertFalse(sink.useAndroidAfterFailure())
            sink.configure(format(), 0, null); send(sink, pcm(31, -456), 4000000); end(sink)
            assertEquals(1, android.configureCalls)
            assertArrayEquals(pcm(31, -456).array(), android.samples.toByteArray())
        } finally { sink.reset() }
    }

    @Test fun pausingWhileWorkerPollsDoesNotReleaseANewPacket() {
        val fake = Transport()
        val queue = UsbPcmQueue(fake, C.ENCODING_PCM_24BIT, 6)
        try {
            val worker = UsbPcmQueue::class.java.getDeclaredField("thread").apply { isAccessible = true }.get(queue) as Thread
            queue.play()
            await { worker.state == Thread.State.TIMED_WAITING }
            queue.pause()
            await { worker.state == Thread.State.WAITING }
            assertTrue(queue.offer(pcm(4, 900)))
            queue.end()
            assertTrue(queue.pending)
            assertFalse(queue.finished)
            assertEquals(0, fake.bytes().size)
            queue.play(); await { queue.finished }
            assertArrayEquals(pcm(4, 900).array(), fake.bytes())
            assertEquals(4L, fake.completed)
            assertFalse(queue.pending)
            assertNull(queue.failure)
        } finally { queue.close() }
    }

    @Test fun timedOutCloseRetainsWorkerUntilASecondResetCanJoinAndCloseTransport() {
        val releaseWrite = CountDownLatch(1)
        val fake = Transport().apply { blockedWrite = releaseWrite }
        val sink = sink(AndroidSink(), { fake })
        try {
            sink.configure(format(), 0, null); sink.play(); send(sink, pcm(257, 123), 0)
            await { fake.writeEntered.count == 0L }
            val started = SystemClock.elapsedRealtime()
            val failure = assertThrows(IllegalStateException::class.java) { sink.reset() }
            assertTrue(failure.message!!.contains("did not stop"))
            assertTrue(SystemClock.elapsedRealtime() - started >= 2900)
            assertFalse(fake.closed); assertEquals(0, fake.closeCalls)
            assertTrue(requireNotNull(fake.writeThread).isAlive)
            releaseWrite.countDown()
            sink.reset()
            assertTrue(fake.closed); assertEquals(1, fake.closeCalls)
            assertFalse(requireNotNull(fake.writeThread).isAlive)
            assertFalse(sink.telemetry.active)
        } finally { releaseWrite.countDown(); sink.reset() }
    }

    private fun sink(android: AndroidSink, factory: () -> UsbPcmTransport, allowed: Boolean = false) =
        ProcessedUsbAudioSink(android.delegate, UsbGraphProcessor(PrecisionBlockProcessor(), { OutputRatePolicy() }), factory, allowed)
    private fun format(rate: Int = 48000) = UsbGraphProcessorDeviceTest.format(rate)
    private fun pcm(frames: Int, value: Int) = UsbGraphProcessorDeviceTest.pcm24(IntArray(frames * 2) { value })
    private fun send(sink: ProcessedUsbAudioSink, bytes: ByteBuffer, time: Long) = await { sink.handleBuffer(bytes, time, 1) }
    private fun end(sink: ProcessedUsbAudioSink) = await { sink.playToEndOfStream(); sink.isEnded() }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!check()) { assertTrue("USB condition must complete", SystemClock.elapsedRealtime() < deadline); SystemClock.sleep(1) }
    }

    private class Transport : UsbPcmTransport {
        private val packets = CopyOnWriteArrayList<ByteArray>()
        @Volatile var completed = 0L
        @Volatile var framesWritten = 0L
        @Volatile var automaticCompletion = true
        @Volatile var finished = false
        @Volatile var closed = false
        @Volatile var closeCalls = 0
        @Volatile var writeThread: Thread? = null
        var blockedWrite: CountDownLatch? = null
        val writeEntered = CountDownLatch(1)
        @Volatile private var error: String? = null
        var started: Format? = null
        var startupFailure = false; var writeFailure = false; var finishFailure = false
        override fun capabilities(source: Format): UsbPcmCapabilities {
            check(!startupFailure) { "USB unavailable" }
            return UsbPcmCapabilities(intArrayOf(48000, 96000), 24, 32)
        }
        override fun start(format: Format) { started = format }
        override fun write(bytes: ByteArray, encoding: Int) {
            writeThread = Thread.currentThread()
            writeEntered.countDown()
            blockedWrite?.let { latch ->
                while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
            }
            check(!writeFailure) { "USB transfer failed" }
            assertEquals(C.ENCODING_PCM_24BIT, encoding); packets += bytes.copyOf()
            framesWritten += bytes.size / 6
            if (automaticCompletion) completed = framesWritten
        }
        override fun finish() {
            if (finishFailure) error = "USB drain failed" else { completed = framesWritten; finished = true }
        }
        override fun status() = UsbPcmStatus(completed, framesWritten - completed, error = error)
        override fun interrupt() = Unit
        override fun close() { closeCalls++; closed = true }
        fun bytes(): ByteArray = ByteArrayOutputStream().apply { packets.forEach(::write) }.toByteArray()
    }

    private class AndroidSink {
        var configureCalls = 0
        var streamOffsetUs = 0L
        val samples = ByteArrayOutputStream()
        private var ended = false
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> { configureCalls++; ended = false; null }
                "setOutputStreamOffsetUs" -> { streamOffsetUs = args!![0] as Long; null }
                "handleBuffer" -> { val input = args!![0] as ByteBuffer; val bytes = ByteArray(input.remaining()); input.get(bytes); samples.write(bytes); true }
                "playToEndOfStream" -> { ended = true; null }
                "isEnded" -> ended
                "getCurrentPositionUs" -> AudioSink.CURRENT_POSITION_NOT_SET
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "supportsFormat" -> true
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink
    }
}
