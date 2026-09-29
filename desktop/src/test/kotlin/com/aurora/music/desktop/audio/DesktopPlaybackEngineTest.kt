package com.aurora.music.desktop.audio

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceEvent
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.natives.WasapiException
import com.aurora.music.model.Song
import com.aurora.music.playback.VisualizerController
import com.aurora.music.playback.chain.DspChainSettings
import com.aurora.music.playback.engine.BandlimitedResampler
import com.aurora.music.playback.engine.OutputRatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class DesktopPlaybackEngineTest {
    private val tracks = Tracks()
    private val engines = mutableListOf<PlaybackEngine>()
    private val hiRes = setOf(44_100 to OutputEncoding.S16, 44_100 to OutputEncoding.S24_IN_32,
        48_000 to OutputEncoding.S16, 48_000 to OutputEncoding.S24_IN_32)

    @After fun tearDown() {
        engines.forEach { it.close() }
        tracks.close()
    }

    @Test fun consecutiveTracksAreGaplessAndSampleExact() {
        val (a, left) = tone("a", 48_000, 16, 3_001) { frame, channel -> (frame * 7 + channel * 11) % 20_000 - 10_000 }
        val (b, right) = tone("b", 48_000, 16, 2_503) { frame, channel -> 12_000 - (frame * 13 + channel * 5) % 24_000 }
        val backend = FakeBackend()
        val engine = engine(backend)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(a), tracks.song(b)))
            log.await { EngineEvent.Ended in it }
            assertArrayEquals(floats(left + right, 16), backend.last.heardFloats(), 0f)
            assertEquals(listOf(TransitionReason.QUEUE_CHANGED, TransitionReason.AUTO), log.transitions().map { it.reason })
            assertEquals("b", log.transitions().last().to?.song?.id)
        }
        val state = engine.await { it.phase == EnginePhase.ENDED }
        assertEquals(1, state.index)
        assertEquals(OutputEncoding.F32, state.output?.encoding)
        assertFalse(state.output!!.exclusive)
        assertFalse(state.processing.bitPerfect)
        assertEquals(1, backend.opened.size)
    }

    @Test fun twentyFourBitSourceIsBitPerfectIntoExclusiveTwentyFourInThirtyTwo() {
        val (file, samples) = hiResTone()
        val backend = FakeBackend(exclusive = hiRes)
        val engine = engine(backend, EngineConfig(outputRatePolicy = OutputRatePolicy(tpdfDither = true)))
        engine.setOutput(null, exclusive = true)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)))
            log.await { EngineEvent.Ended in it }
        }
        val output = backend.last
        assertTrue(output.exclusive)
        assertEquals(44_100, output.sampleRate)
        assertEquals(OutputEncoding.S24_IN_32, output.encoding)
        assertArrayEquals(samples.map { it shl 8 }.toIntArray(), output.heardInts())
        val state = engine.await { it.phase == EnginePhase.ENDED }
        assertTrue(state.processing.bitPerfect)
        assertNull(state.processing.ditherLabel)
        assertFalse(state.processing.dspActive)
    }

    @Test fun activeDspChangesSamplesAndDithersTheIntegerOutput() {
        val (file, samples) = hiResTone()
        val backend = FakeBackend(exclusive = hiRes)
        val engine = engine(backend, EngineConfig(outputRatePolicy = OutputRatePolicy(tpdfDither = true)))
        engine.applyDsp(DspChainSettings(AudioPrefs(dspMode = DspMode.CUSTOM, dspPreampDb = -6f, dspLimiterEnabled = false)))
        engine.setOutput(null, exclusive = true)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)))
            log.await { EngineEvent.Ended in it }
        }
        val heard = backend.last.heardInts()
        assertEquals(samples.size, heard.size)
        assertTrue(heard.all { (it and 0xff) == 0 })
        val gain = 10.0.pow(-6.0 / 20.0)
        for (i in 6_000 until samples.size) assertEquals(samples[i] * gain, (heard[i] shr 8).toDouble(), 3.0)
        val state = engine.await { it.phase == EnginePhase.ENDED }
        assertTrue(state.processing.dspActive)
        assertFalse(state.processing.bitPerfect)
        assertEquals("TPDF dither", state.processing.ditherLabel)
    }

    @Test fun replayGainAttenuatesPerTrackAtTheExactBoundary() {
        val (file, samples) = tone("rg", 48_000, 16, 2_000) { frame, channel -> (frame * 31 + channel) % 30_000 - 15_000 }
        val backend = FakeBackend()
        val engine = engine(backend, EngineConfig(replayGain = ReplayGain.TRACK))
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file, "quiet", rgTrack = -6f), tracks.song(file, "loud", rgTrack = 3f)))
            log.await { EngineEvent.Ended in it }
        }
        val gain = ReplayGain.multiplier(-6f)
        val expected = FloatArray(samples.size * 2) { i ->
            val value = samples[i % samples.size] / 32_768.0
            (if (i < samples.size) value * gain else value).toFloat()
        }
        assertArrayEquals(expected, backend.last.heardFloats(), 0f)
    }

    @Test fun crossfadeOverlapsTheTracksWithTheConfiguredEnvelope() {
        val (a, _) = tone("loud", 48_000, 16, 96_000) { _, _ -> 16_384 }
        val (b, _) = tone("soft", 48_000, 16, 96_000) { _, _ -> 8_192 }
        val backend = FakeBackend(speed = 8.0)
        val engine = engine(backend, EngineConfig(crossfadeMs = 1_000, crossfadeCurve = "LINEAR", crossfadeHeadroom = false))
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(a), tracks.song(b)))
            engine.await { it.crossfading }
            log.await(20_000) { EngineEvent.Ended in it }
            assertEquals(TransitionReason.AUTO, log.transitions().last().reason)
        }
        val heard = backend.last.heardFloats()
        val left = FloatArray(heard.size / 2) { heard[it * 2] }
        val start = left.indexOfFirst { it < 0.5f } - 1
        val end = left.indexOfFirst { it <= 0.25f }
        val fade = end - start
        assertTrue("fade $fade", fade in 46_000..48_000)
        assertEquals(start + 96_000, left.size)
        for (k in 0..fade step 101) assertEquals(0.5f - 0.25f * k / fade, left[start + k], 1e-4f)
        assertTrue(left.copyOfRange(0, start + 1).all { it == 0.5f })
        assertTrue(left.copyOfRange(end, left.size).all { it == 0.25f })
    }

    @Test fun seeksLandOnTheExactFrameAndReportTheirPosition() {
        val (file, _) = tone("ramp", 48_000, 24, 144_000) { frame, channel -> if (channel == 0) frame else -frame }
        val backend = FakeBackend()
        val engine = engine(backend)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)), startPositionMs = 1_500, play = false)
            assertEquals(1_500L, engine.await { it.phase == EnginePhase.READY }.positionMs)
            engine.seekTo(2_000)
            engine.await { it.phase == EnginePhase.READY && it.positionMs == 2_000L }
            engine.play()
            log.await { EngineEvent.Ended in it }
            assertEquals(listOf(EngineEvent.Discontinuity(log.transitions().single().to!!, 1_500, 2_000)), log.all<EngineEvent.Discontinuity>())
        }
        val heard = backend.last.heardFloats()
        assertEquals(48_000 * 2, heard.size)
        assertEquals(pcm24(96_000), heard[0])
        assertEquals(pcm24(-96_000), heard[1])
        assertEquals(pcm24(143_999), heard[heard.size - 2])
        assertEquals(3_000L, engine.state.value.positionMs)
    }

    @Test fun positionFollowsTheHeardFrames() {
        val (file, _) = tone("long", 48_000, 16, 240_000) { frame, _ -> frame % 1_000 }
        val backend = FakeBackend(speed = 1.0, ringFrames = 12_000)
        val engine = engine(backend)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val visualizer = VisualizerController(scope).apply { start() }
        engine.visualizer = visualizer
        engine.setQueue(listOf(tracks.song(file)))
        engine.await { it.positionMs >= 400 }
        Thread.sleep(150)
        val heardMs = backend.last.heardFrames() * 1_000 / 48_000
        val reported = engine.state.value.positionMs
        assertTrue("reported $reported heard $heardMs", abs(reported - heardMs) <= 120)
        assertTrue(backend.awake.contains(true))
        eventually { visualizer.frame.level > 0f }
        assertEquals(48_000, engine.beforeMeter.snapshot()?.sampleRate)
        assertTrue(engine.afterMeter.snapshot()!!.leftPeak > 0.0)
        visualizer.stop()
        scope.cancel()
        engine.pause()
        engine.await { !it.playWhenReady }
        val paused = engine.state.value.positionMs
        Thread.sleep(200)
        assertTrue(abs(engine.state.value.positionMs - paused) <= 30)
    }

    @Test fun repeatOneLoopsOnAutoAdvanceAndNextLeavesTheLoop() {
        val (a, _) = tone("one", 48_000, 16, 1_000) { frame, _ -> frame }
        val (b, _) = tone("two", 48_000, 16, 1_000) { frame, _ -> -frame }
        val engine = engine(FakeBackend(speed = 4.0))
        EventLog(engine).use { log ->
            engine.setRepeat(RepeatMode.ONE)
            engine.setQueue(listOf(tracks.song(a), tracks.song(b)))
            log.await { events -> events.count { it is EngineEvent.Transition && it.reason == TransitionReason.REPEAT } >= 2 }
            assertTrue(log.transitions().filter { it.reason == TransitionReason.REPEAT }.all { it.from?.song?.id == "one" && it.to?.song?.id == "one" })
            engine.next()
            log.await { events -> events.any { it is EngineEvent.Transition && it.reason == TransitionReason.SEEK && it.to?.song?.id == "two" } }
            engine.pause()
        }
    }

    @Test fun repeatAllWrapsToTheFirstTrack() {
        val (a, _) = tone("first", 48_000, 16, 800) { frame, _ -> frame }
        val (b, _) = tone("second", 48_000, 16, 800) { frame, _ -> -frame }
        val engine = engine(FakeBackend(speed = 4.0))
        EventLog(engine).use { log ->
            engine.setRepeat(RepeatMode.ALL)
            engine.setQueue(listOf(tracks.song(a), tracks.song(b)))
            log.await { it.filterIsInstance<EngineEvent.Transition>().size >= 3 }
            engine.pause()
            val auto = log.transitions().drop(1).take(2)
            assertEquals(listOf("first" to "second", "second" to "first"), auto.map { it.from?.song?.id to it.to?.song?.id })
            assertTrue(auto.all { it.reason == TransitionReason.AUTO })
        }
    }

    @Test fun shuffleReordersPhysicallyAndRestores() {
        val songs = (1..5).map { tracks.song(tone("s$it", 48_000, 16, 500) { frame, _ -> frame }.first) }
        val engine = engine(FakeBackend())
        engine.setQueue(songs, startIndex = 2, play = false)
        engine.await { it.phase == EnginePhase.READY }
        engine.setShuffle(ShuffleTarget.ON)
        val shuffled = engine.await { it.shuffle }
        assertEquals(0, shuffled.index)
        assertEquals("s3", shuffled.current?.song?.id)
        assertEquals(songs.map { it.id }, shuffled.shuffleRestoreIds)
        assertEquals(songs.map { it.id }.toSet(), shuffled.entries.map { it.song.id }.toSet())
        engine.setShuffle(ShuffleTarget.OFF)
        val restored = engine.await { !it.shuffle }
        assertEquals(songs.map { it.id }, restored.entries.map { it.song.id })
        assertEquals(2, restored.index)
    }

    @Test fun endOfQueueEndsAndPlayRestartsTheLastTrack() {
        val (file, samples) = tone("end", 48_000, 16, 1_200) { frame, channel -> frame * (1 - 2 * channel) }
        val backend = FakeBackend()
        val engine = engine(backend)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)))
            log.await { EngineEvent.Ended in it }
            assertEquals(EnginePhase.ENDED, engine.await { it.phase == EnginePhase.ENDED }.phase)
            assertTrue(engine.state.value.playWhenReady)
            engine.play()
            log.await { events -> events.count { it == EngineEvent.Ended } == 2 }
            assertEquals(1, log.all<EngineEvent.Discontinuity>().size)
        }
        assertArrayEquals(floats(samples + samples, 16), backend.last.heardFloats(), 0f)
        eventually { backend.awake.last() == false }
    }

    @Test fun failingItemsAreSkippedAndConsecutiveFailuresStop() {
        val (a, first) = tone("ok1", 48_000, 16, 900) { frame, _ -> frame }
        val (c, last) = tone("ok2", 48_000, 16, 700) { frame, _ -> -frame }
        val broken = Song("yt", "Video", "Artist", "Album", "", 30, streamUrl = "aurora-yt://video/abcdefghijk")
        val backend = FakeBackend()
        val engine = engine(backend)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(a), broken, tracks.song(c)))
            log.await { EngineEvent.Ended in it }
            val failure = log.all<EngineEvent.Failed>().single()
            assertEquals("yt", failure.entry?.song?.id)
            assertEquals(PlaybackFailure.Kind.UNSUPPORTED_SOURCE, failure.failure.kind)
            assertEquals("ok1" to "ok2", log.transitions().last().let { it.from?.song?.id to it.to?.song?.id })
        }
        assertArrayEquals(floats(first + last, 16), backend.last.heardFloats(), 0f)
        EventLog(engine).use { log ->
            engine.setQueue((1..7).map { broken.copy(id = "yt$it") } + tracks.song(a))
            val stopped = engine.await { it.error != null }
            assertEquals(EnginePhase.IDLE, stopped.phase)
            assertFalse(stopped.playWhenReady)
            assertEquals(5, log.all<EngineEvent.Failed>().size)
        }
    }

    @Test fun loadFailuresWhilePausedStayOnTheItemAtItsPosition() {
        val (file, samples) = tone("after", 48_000, 16, 600) { frame, _ -> frame }
        val broken = Song("yt", "Video", "Artist", "Album", "", 30, streamUrl = "aurora-yt://video/abcdefghijk")
        val backend = FakeBackend()
        val engine = engine(backend)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(broken, tracks.song(file)), 0, 5_000, play = false)
            val stopped = engine.await { it.error != null }
            log.await { events -> events.any { it is EngineEvent.Failed } }
            assertEquals(0, stopped.index)
            assertEquals(5_000L, stopped.positionMs)
            assertEquals(EnginePhase.IDLE, stopped.phase)
            assertFalse(stopped.playWhenReady)
            assertEquals(1, log.all<EngineEvent.Failed>().size)
            engine.play()
            log.await { EngineEvent.Ended in it }
            assertEquals(2, log.all<EngineEvent.Failed>().size)
            assertEquals("yt" to "after", log.transitions().last().let { it.from?.song?.id to it.to?.song?.id })
        }
        assertArrayEquals(floats(samples, 16), backend.last.heardFloats(), 0f)
    }

    @Test fun ratesTheChainCannotPlayAreSkippedAsUnsupported() {
        val (low, _) = tone("phone", 8_000, 16, 800) { frame, _ -> frame }
        val (a, _) = tone("ok1", 48_000, 16, 45_000) { frame, _ -> frame % 1_000 }
        val (c, _) = tone("ok2", 48_000, 16, 2_400) { frame, _ -> -frame }
        val backend = FakeBackend(speed = 8.0)
        val engine = engine(backend, EngineConfig(crossfadeMs = 1_000))
        engine.setSpeed(0.5f)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(low), tracks.song(a), tracks.song(low, "phone2"), tracks.song(c)))
            log.await { EngineEvent.Ended in it }
            val failures = log.all<EngineEvent.Failed>()
            assertEquals(listOf("phone", "phone2"), failures.map { it.entry?.song?.id })
            assertTrue(failures.all { it.failure.kind == PlaybackFailure.Kind.UNSUPPORTED_SOURCE })
            assertEquals(listOf(TransitionReason.QUEUE_CHANGED, TransitionReason.SEEK, TransitionReason.AUTO), log.transitions().map { it.reason })
            assertEquals("ok1" to "ok2", log.transitions().last().let { it.from?.song?.id to it.to?.song?.id })
        }
        assertNull(engine.await { it.phase == EnginePhase.ENDED }.error)
    }

    @Test fun sampleRateChangesAreResampledInSharedMode() {
        val (a, low) = tone("cd", 44_100, 16, 4_410) { frame, channel -> (sin(frame * 0.05 + channel) * 12_000).roundToInt() }
        val (b, high) = tone("dat", 48_000, 16, 4_800) { frame, channel -> (frame * 3 + channel) % 9_000 - 4_500 }
        val backend = FakeBackend()
        val engine = engine(backend)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(a), tracks.song(b)))
            log.await { EngineEvent.Ended in it }
            assertEquals(TransitionReason.AUTO, log.transitions().last().reason)
        }
        val heard = backend.last.heardFloats()
        assertEquals((4_800 + 4_800) * 2, heard.size)
        val resampled = BandlimitedResampler.resample(DoubleArray(4_410) { low[it * 2] / 32_768.0 }, 44_100, 48_000)
        for (i in 0 until 4_800) assertEquals(resampled[i].toFloat(), heard[i * 2])
        assertArrayEquals(floats(high, 16), heard.copyOfRange(9_600, heard.size), 0f)
        assertEquals(1, backend.opened.size)
    }

    @Test fun exclusiveOutputReopensAtRateChanges() {
        val (a, low) = tone("x44", 44_100, 16, 2_205) { frame, channel -> frame - channel }
        val (b, high) = tone("x48", 48_000, 16, 2_400) { frame, channel -> channel - frame }
        val backend = FakeBackend(exclusive = setOf(44_100 to OutputEncoding.S16, 48_000 to OutputEncoding.S16))
        val engine = engine(backend)
        engine.setOutput(null, exclusive = true)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(a), tracks.song(b)))
            log.await { EngineEvent.Ended in it }
            assertEquals(TransitionReason.AUTO, log.transitions().last().reason)
        }
        assertEquals(listOf(44_100, 48_000), backend.opened.map { it.sampleRate })
        assertTrue(backend.opened.first().closed)
        assertArrayEquals(shorts(low), backend.opened[0].heardBytes())
        assertArrayEquals(shorts(high), backend.opened[1].heardBytes())
    }

    @Test fun exclusiveFailureFallsBackToSharedWithAReason() {
        val (file, _) = tone("fallback", 48_000, 16, 600) { frame, _ -> frame }
        val backend = FakeBackend(exclusive = setOf(48_000 to OutputEncoding.S16), refuseExclusive = true)
        val engine = engine(backend)
        engine.setOutput(null, exclusive = true)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)), play = false)
            val state = engine.await { it.phase == EnginePhase.READY && it.output != null }
            assertFalse(state.output!!.exclusive)
            assertTrue(state.output!!.fallbackReason!!.startsWith("Exclusive mode is unavailable"))
            log.await { events -> events.any { it is EngineEvent.OutputFallback } }
        }
    }

    @Test fun bufferChangesApplyWhenAStoppedOutputIsReused() {
        val (file, _) = tone("buffer", 48_000, 16, 2_400) { frame, _ -> frame }
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.setQueue(listOf(tracks.song(file)), play = false)
        engine.await { it.phase == EnginePhase.READY }
        engine.stop()
        engine.await { it.phase == EnginePhase.IDLE }
        engine.configure(EngineConfig(bufferMs = 1_000))
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)))
            log.await { EngineEvent.Ended in it }
        }
        assertEquals(listOf(250, 1_000), backend.opened.map { it.bufferMs })
        assertTrue(backend.opened.first().closed)
    }

    @Test fun invalidatedStreamsReopenAtTheHeardPosition() {
        val (file, _) = tone("device", 48_000, 16, 240_000) { frame, _ -> frame % 2_000 }
        val backend = FakeBackend(speed = 1.0, ringFrames = 9_600)
        val engine = engine(backend)
        engine.setQueue(listOf(tracks.song(file)))
        engine.await { it.positionMs >= 300 }
        val first = backend.last
        backend.fire(DeviceEvent.StreamInvalidated(first.id, first.deviceId))
        val resumed = engine.await { backend.opened.size == 2 && it.isPlaying && it.positionMs >= 350 }
        assertTrue(first.closed)
        assertTrue(resumed.positionMs < 1_500)
    }

    @Test fun streamsInvalidatedWhilePausedReopenWhenPlayResumes() {
        val (file, _) = tone("asleep", 48_000, 16, 240_000) { frame, _ -> frame % 2_000 }
        val backend = FakeBackend(speed = 1.0, ringFrames = 9_600)
        val engine = engine(backend)
        EventLog(engine).use { log ->
            engine.setQueue(listOf(tracks.song(file)))
            engine.await { it.isPlaying && it.positionMs >= 300 }
            engine.pause()
            val paused = engine.await { !it.playWhenReady }
            val first = backend.last
            first.resumeFailure = WasapiException.DEVICE_INVALIDATED
            engine.play()
            val resumed = engine.await { backend.opened.size == 2 && it.isPlaying && it.positionMs >= paused.positionMs + 50 }
            assertTrue(first.closed)
            assertNull(resumed.error)
            assertTrue(resumed.positionMs < paused.positionMs + 1_500)
            assertTrue(log.all<EngineEvent.Failed>().isEmpty())
        }
    }

    @Test fun removingTheSelectedDevicePausesOnTheDefaultUntilItReturns() {
        val (file, _) = tone("usb", 48_000, 16, 240_000) { frame, _ -> frame % 3_000 }
        val backend = FakeBackend(speed = 1.0, devices = listOf(
            AudioDevice("speakers", "Speakers", DeviceKind.SPEAKERS, true),
            AudioDevice("dac", "USB DAC", DeviceKind.SPEAKERS, false),
        ))
        val engine = engine(backend)
        EventLog(engine).use { log ->
            engine.setOutput("dac", exclusive = false)
            engine.setQueue(listOf(tracks.song(file)))
            engine.await { it.isPlaying && it.output?.deviceId == "dac" }
            backend.fire(DeviceEvent.Removed("dac"))
            val state = engine.await { !it.playWhenReady && it.phase == EnginePhase.READY && it.output?.deviceId == "speakers" }
            assertNotNull(state.output?.fallbackReason)
            log.await { events -> events.any { it is EngineEvent.OutputFallback } }
            eventually { backend.awake.last() == false }
            backend.fire(DeviceEvent.Added("dac"))
            val back = engine.await { it.phase == EnginePhase.READY && it.output?.deviceId == "dac" }
            assertNull(back.output?.fallbackReason)
            assertFalse(back.playWhenReady)
            assertEquals(state.positionMs, back.positionMs)
        }
    }

    @Test fun sleepFadeRampsToSilenceThenPausesAndPlayRestoresTheLevel() {
        val (file, _) = tone("sleep", 48_000, 16, 480_000) { _, _ -> 16_384 }
        val backend = FakeBackend(speed = 4.0)
        val engine = engine(backend)
        engine.setQueue(listOf(tracks.song(file)))
        engine.await { it.isPlaying && it.positionMs >= 300 }
        engine.sleepFade(400)
        val paused = engine.await { !it.playWhenReady && it.phase == EnginePhase.READY }
        assertEquals(1f, paused.volume)
        val faded = backend.last.heardFloats().filterIndexed { i, _ -> i % 2 == 0 }
        val start = faded.indexOfFirst { it < 0.5f }
        val quiet = faded.indexOfFirst { it < 0.001f }
        assertTrue("fade $start..$quiet", start > 0 && quiet - start in 19_000..19_500)
        for (i in start + 1..quiet) assertTrue(faded[i] <= faded[i - 1])
        val heardBefore = backend.last.heardFrames()
        engine.play()
        engine.await { it.isPlaying }
        eventually { backend.last.heardFrames() > heardBefore + 4_800 }
        val resumed = backend.last.heardFloats()
        assertEquals(0.5f, resumed[resumed.size - 2])
        engine.pause()
    }

    @Test fun sleepFadeWhilePausedIsDroppedAndPausingCancelsARunningFade() {
        val (file, _) = tone("drowsy", 48_000, 16, 480_000) { _, _ -> 16_384 }
        val backend = FakeBackend(speed = 4.0)
        val engine = engine(backend)
        engine.setQueue(listOf(tracks.song(file)))
        engine.await { it.isPlaying && it.positionMs >= 300 }
        engine.pause()
        engine.await { !it.playWhenReady }
        engine.sleepFade(400)
        engine.play()
        engine.await { it.isPlaying }
        val resumedAt = backend.last.heardFrames()
        eventually { backend.last.heardFrames() > resumedAt + 48_000 }
        assertTrue(engine.state.value.playWhenReady)
        assertTrue(backend.last.heardFloats().all { it == 0.5f })
        engine.sleepFade(2_000)
        val fadingAt = backend.last.heardFrames()
        eventually { backend.last.heardFrames() > fadingAt + 24_000 }
        engine.pause()
        engine.await { !it.playWhenReady }
        assertTrue(backend.last.heardFloats().any { it < 0.5f })
        engine.play()
        engine.await { it.isPlaying }
        val playingAt = backend.last.heardFrames()
        eventually { backend.last.heardFrames() > playingAt + 120_000 }
        assertTrue(engine.state.value.playWhenReady)
        val heard = backend.last.heardFloats()
        assertEquals(0.5f, heard[heard.size - 2])
        engine.pause()
    }

    private fun engine(backend: FakeBackend, config: EngineConfig = EngineConfig()) =
        DesktopPlaybackEngine(backend, config = config, random = Random(11)).also { engines += it }

    private fun tone(name: String, rate: Int, bits: Int, frames: Int, sample: (Int, Int) -> Int): Pair<File, IntArray> {
        val samples = IntArray(frames * 2) { sample(it / 2, it % 2) }
        return tracks.wav(name, rate, bits, frames) { frame, channel -> samples[frame * 2 + channel] } to samples
    }

    private fun hiResTone() = tone("hires", 44_100, 24, 8_000) { frame, channel ->
        when (frame) {
            0 -> -8_388_608
            1 -> 8_388_607
            else -> ((frame * 2_654_435_761L + channel * 97) % 16_777_216 - 8_388_608).toInt()
        }
    }

    private fun floats(samples: IntArray, bits: Int) = FloatArray(samples.size) { (samples[it] / 2.0.pow(bits - 1)).toFloat() }

    private fun shorts(samples: IntArray) = ByteArray(samples.size * 2) { (samples[it / 2] shr (8 * (it % 2))).toByte() }
}
