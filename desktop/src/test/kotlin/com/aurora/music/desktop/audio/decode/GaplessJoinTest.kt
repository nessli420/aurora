package com.aurora.music.desktop.audio.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

// the android gapless fixtures: one 441 Hz sine split at CUTS and encoded per part, decoded back to back here
class GaplessJoinTest {
    // a lossy part rebuilds its abrupt cut edge with codec noise for about one frame
    private val window = 1200
    private val cuts = listOf(0, 37_813, 79_050, 118_051)
    private val directory = File(TestAssets.directory.parentFile, "gapless")

    private fun join(extension: String, mid: Double, edge: Double) {
        val left = (1..3).flatMap { part ->
            FfmpegDecoder.open(File(directory, "part$part.$extension").path).use { decodeAll(it) }.filterIndexed { i, _ -> i % 2 == 0 }
        }
        assertEquals(extension, cuts.last(), left.size)
        fun error(n: Int, lag: Int = 0) = abs(left[n] - 0.5 * sin(2 * PI * 441 * (n + lag) / 48_000))
        val joins = cuts.drop(1).dropLast(1)
        var inner = 0.0
        for (n in 2048 until left.size - 2048) if (joins.none { abs(n - it) < window }) inner = max(inner, error(n))
        assertTrue("$extension inner $inner", inner < mid)
        joins.forEach { j -> (j - window until j + window).maxOf { error(it) }.let { assertTrue("$extension join $it", it < edge) } }
        cuts.zipWithNext().forEach { (a, b) ->
            val best = (-3..3).minBy { lag -> (a + 2048 until b - 2048).maxOf { error(it, lag) } }
            assertEquals("$extension alignment", 0, best)
        }
    }

    @Test fun flacJoinsBitExactly() = join("flac", 1e-4, 1e-4)
    @Test fun mp3WithLameTagJoinsGaplessly() = join("mp3", 0.003, 0.15)
    @Test fun aacM4aWithEditListJoinsGaplessly() = join("m4a", 0.05, 0.15)
    @Test fun oggOpusJoinsGaplessly() = join("opus", 0.02, 0.15)
    @Test fun webmOpusJoinsGaplessly() = join("webm", 0.02, 0.15)
}
