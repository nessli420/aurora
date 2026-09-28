package com.aurora.music.desktop.audio

import com.aurora.music.desktop.audio.decode.AudioTags
import com.aurora.music.model.Song
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class ReplayGainTest {
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
