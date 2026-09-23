package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class VideoFingerprintMatchTest {
    private val itemMs = 125

    @Test fun matchesAnIntroUsingMultipleAudioWindows() {
        val song = IntArray(90 * 8) { Random(it + 3).nextInt() }
        val video = IntArray(120 * 8) { Random(it + 9000).nextInt() }
        song.forEachIndexed { i, value -> video[i + 8 * 8] = value xor 0x100100 }
        val plan = VideoFingerprintMatch.plan(VideoFingerprintMatch.anchors(song, video, itemMs))
        assertEquals(8_000L, plan?.offsetAt(26_000))
    }

    @Test fun skipsAnExtraSectionInsertedIntoTheVideo() {
        val song = IntArray(90 * 8) { Random(it + 13).nextInt() }
        val video = IntArray(120 * 8) { Random(it + 9000).nextInt() }
        val cut = 30 * 8
        for (i in song.indices) video[i + if (i < cut) 0 else 5 * 8] = song[i] xor 0x100100
        val plan = VideoFingerprintMatch.plan(VideoFingerprintMatch.anchors(song, video, itemMs))
        assertEquals(0L, plan?.offsetAt(10_000))
        assertEquals(5_000L, plan?.offsetAt(50_000))
    }

    @Test fun isolatedWrongIntroMatchDoesNotOverrideLaterEvidence() {
        val anchors = listOf(
            VideoFingerprintMatch.Anchor(0, 6_642, 0.8, 0.7),
            VideoFingerprintMatch.Anchor(5_043, 6_642, 0.9, 0.8),
            VideoFingerprintMatch.Anchor(10_086, -6_519, 0.6, 1.1),
            VideoFingerprintMatch.Anchor(15_129, -6_519, 1.0, 0.8),
            VideoFingerprintMatch.Anchor(20_172, 0, 1.8, 5.8),
            VideoFingerprintMatch.Anchor(25_215, 0, 2.0, 9.5),
            VideoFingerprintMatch.Anchor(30_258, 0, 2.0, 9.5),
            VideoFingerprintMatch.Anchor(35_301, 0, 1.7, 5.5),
        )
        assertEquals(0L, VideoFingerprintMatch.plan(anchors)?.offsetAt(0))
        assertEquals(0L, VideoFingerprintMatch.plan(anchors)?.offsetAt(26_000))
    }

    @Test fun unrelatedAudioHasNoPlan() {
        val song = IntArray(90 * 8) { Random(it + 3).nextInt() }
        val video = IntArray(120 * 8) { Random(it + 9000).nextInt() }
        assertNull(VideoFingerprintMatch.plan(VideoFingerprintMatch.anchors(song, video, itemMs)))
    }
}
