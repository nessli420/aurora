package com.aurora.music.desktop.audio

import com.aurora.music.desktop.audio.decode.AudioTags
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.model.Song
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class OutputNegotiatorTest {
    private val device = FakeBackend(mixRate = 44_100, exclusive = setOf(
        44_100 to OutputEncoding.S16, 44_100 to OutputEncoding.S24_IN_32,
        48_000 to OutputEncoding.S16, 48_000 to OutputEncoding.S24_IN_32, 48_000 to OutputEncoding.S32,
        96_000 to OutputEncoding.S24,
    ))

    @Test fun sharedModeUsesTheMixRateInFloat() {
        val shared = OutputNegotiator(device).negotiate(null, false, 96_000, OutputRatePolicy())
        assertEquals(NegotiatedOutput(null, false, 44_100, OutputEncoding.F32), shared)
    }

    @Test fun exclusiveFollowsTheSourceWithThePreferredIntegerEncoding() {
        val negotiator = OutputNegotiator(device)
        assertEquals(NegotiatedOutput(null, true, 48_000, OutputEncoding.S24_IN_32), negotiator.negotiate(null, true, 48_000, OutputRatePolicy()))
        assertEquals(OutputEncoding.S24, negotiator.negotiate(null, true, 96_000, OutputRatePolicy()).encoding)
    }

    @Test fun unsupportedRatesFallBackWithinTheFamily() {
        val chosen = OutputNegotiator(device).negotiate(null, true, 88_200, OutputRatePolicy())
        assertEquals(44_100, chosen.sampleRate)
        assertEquals("88200 Hz is unavailable; using 44100 Hz.", chosen.rateFallbackReason)
        val maximum = OutputNegotiator(device).negotiate(null, true, 48_000,
            OutputRatePolicy(OutputRateMode.COMPATIBLE_MAXIMUM, maximumRate = 192_000))
        assertEquals(96_000, maximum.sampleRate)
    }

    @Test fun devicesWithoutExclusiveSupportStayShared() {
        val chosen = OutputNegotiator(FakeBackend()).negotiate("speakers", true, 44_100, OutputRatePolicy())
        assertFalse(chosen.exclusive)
        assertEquals(48_000, chosen.sampleRate)
        assertNotNull(chosen.fallbackReason)
    }

    @Test fun replayGainPrefersSongValuesThenTagsAndOnlyAttenuates() {
        val song = Song("a", "a", "b", "c", "", 1, replayGainTrack = -3f)
        val tags = AudioTags(trackGainDb = -8f, albumGainDb = -5f, r128AlbumGainDb = -10f)
        assertEquals(-3f, ReplayGain.gainDb(song, tags, ReplayGain.TRACK))
        assertEquals(-5f, ReplayGain.gainDb(song, tags, ReplayGain.ALBUM))
        assertEquals(-5f, ReplayGain.gainDb(song.copy(replayGainTrack = 0f), AudioTags(r128TrackGainDb = -10f), ReplayGain.TRACK))
        assertNull(ReplayGain.gainDb(song, AudioTags(trackGainDb = -8f), ReplayGain.ALBUM))
        assertNull(ReplayGain.gainDb(song, tags, ReplayGain.OFF))
        assertEquals(10.0.pow(-3.0 / 20.0), ReplayGain.multiplier(-3f), 1e-12)
        assertEquals(1.0, ReplayGain.multiplier(4f), 0.0)
        assertEquals(0.1, ReplayGain.multiplier(-40f), 0.0)
        assertEquals(1.0, ReplayGain.multiplier(null), 0.0)
    }
}
