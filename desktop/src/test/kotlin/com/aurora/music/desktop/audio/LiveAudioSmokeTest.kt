package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.OutputEncoding
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sin

class LiveAudioSmokeTest {
    @Test fun twoGeneratedTracksPlaySilentlyThroughTheDefaultDevice() {
        assumeTrue("set AURORA_LIVE_AUDIO=1 to use the real audio device", System.getenv("AURORA_LIVE_AUDIO") == "1")
        Tracks().use { tracks ->
            val first = tracks.wav("live-a", 44_100, 16, 22_050) { frame, _ -> (sin(frame * 0.06) * 8_000).roundToInt() }
            val second = tracks.wav("live-b", 48_000, 24, 24_000) { frame, _ -> (sin(frame * 0.05) * 2_000_000).roundToInt() }
            DesktopPlaybackEngine().use { engine ->
                assertTrue(engine.outputs.value.isNotEmpty())
                engine.setVolume(0f)
                EventLog(engine).use { log ->
                    engine.setQueue(listOf(tracks.song(first), tracks.song(second)))
                    val playing = engine.await { it.isPlaying && it.output != null }
                    assertEquals(OutputEncoding.F32, playing.output?.encoding)
                    assertFalse(playing.output!!.exclusive)
                    log.await(15_000) { EngineEvent.Ended in it }
                    assertEquals(listOf(TransitionReason.QUEUE_CHANGED, TransitionReason.AUTO), log.transitions().map { it.reason })
                    assertTrue(log.all<EngineEvent.Failed>().isEmpty())
                }
                val ended = engine.await { it.phase == EnginePhase.ENDED }
                assertEquals(1, ended.index)
                assertEquals(0f, ended.volume)
            }
        }
    }
}
