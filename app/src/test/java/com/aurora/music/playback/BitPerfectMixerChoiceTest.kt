package com.aurora.music.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitPerfectMixerChoiceTest {
    private val stereo = 12
    private val mono = 4
    private val dac = listOf(
        MixerFormat(44_100, C.ENCODING_PCM_16BIT, stereo),
        MixerFormat(44_100, C.ENCODING_PCM_24BIT, stereo),
        MixerFormat(44_100, C.ENCODING_PCM_32BIT, stereo),
        MixerFormat(96_000, C.ENCODING_PCM_16BIT, stereo),
        MixerFormat(96_000, C.ENCODING_PCM_24BIT, stereo),
        MixerFormat(48_000, C.ENCODING_PCM_24BIT, mono),
    )

    @Test fun anExactFormatIsAlwaysPreferred() {
        val track = MixerFormat(44_100, C.ENCODING_PCM_16BIT, stereo)
        assertEquals(track, BitPerfectMixerChoice.choose(dac, track, exactOnly = true))
        assertEquals(track, BitPerfectMixerChoice.choose(dac, track, exactOnly = false))
    }

    @Test fun opportunisticRequestsNeedTheExactFormat() {
        assertNull(BitPerfectMixerChoice.choose(dac, MixerFormat(96_000, C.ENCODING_PCM_FLOAT, stereo), exactOnly = true))
    }

    @Test fun exclusiveFallbackFollowsTheTrackRateAtTheDevicesBestDepth() {
        assertEquals(MixerFormat(96_000, C.ENCODING_PCM_24BIT, stereo),
            BitPerfectMixerChoice.choose(dac, MixerFormat(96_000, C.ENCODING_PCM_FLOAT, stereo), exactOnly = false))
        assertEquals(MixerFormat(44_100, C.ENCODING_PCM_32BIT, stereo),
            BitPerfectMixerChoice.choose(dac, MixerFormat(44_100, C.ENCODING_PCM_FLOAT, stereo), exactOnly = false))
    }

    @Test fun aRateOrLayoutTheDeviceLacksIsNeverForced() {
        assertNull(BitPerfectMixerChoice.choose(dac, MixerFormat(192_000, C.ENCODING_PCM_FLOAT, stereo), exactOnly = false))
        assertNull(BitPerfectMixerChoice.choose(dac, MixerFormat(48_000, C.ENCODING_PCM_FLOAT, stereo), exactOnly = false))
        assertNull(BitPerfectMixerChoice.choose(emptyList(), MixerFormat(44_100, C.ENCODING_PCM_16BIT, stereo), exactOnly = false))
    }
}
