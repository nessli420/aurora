package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class VideoAudioOffsetTest {
    private fun song(): FloatArray {
        val random = Random(9)
        return FloatArray(15 * VideoAudioOffset.BINS_PER_SECOND) { i ->
            0.08f + random.nextFloat() * 0.15f + if (i % 19 < 3) 0.55f else 0f
        }
    }

    @Test fun findsVideoIntroBeforeTheSong() {
        val source = song()
        val random = Random(17)
        val video = FloatArray(45 * VideoAudioOffset.BINS_PER_SECOND) { 0.02f + random.nextFloat() * 0.02f }
        source.forEachIndexed { i, value -> video[8 * VideoAudioOffset.BINS_PER_SECOND + i] = value * 0.7f + random.nextFloat() * 0.01f }
        assertEquals(8_000L, VideoAudioOffset.find(source, video))
    }

    @Test fun findsVideoThatOmitsTheSongsIntro() {
        val source = song()
        val video = FloatArray(45 * VideoAudioOffset.BINS_PER_SECOND) { 0.03f }
        for (i in 5 * VideoAudioOffset.BINS_PER_SECOND until source.size) {
            video[i - 5 * VideoAudioOffset.BINS_PER_SECOND] = source[i] * 0.8f
        }
        assertEquals(-5_000L, VideoAudioOffset.find(source, video))
    }

    @Test fun unrelatedAudioDoesNotMoveTheVideo() {
        val source = song()
        val random = Random(99)
        val video = FloatArray(45 * VideoAudioOffset.BINS_PER_SECOND) { random.nextFloat() }
        assertNull(VideoAudioOffset.find(source, video))
    }
}
