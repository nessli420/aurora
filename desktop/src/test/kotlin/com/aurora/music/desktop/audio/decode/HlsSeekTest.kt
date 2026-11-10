package com.aurora.music.desktop.audio.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// soundcloud streams are hls over http: the playlist's byte reader can't seek, but the demuxer seeks by time,
// and calling it unseekable made the engine drop every seek and snap the waveform back
class HlsSeekTest {
    @Test fun anHlsStreamIsSeekableAndASeekLandsInALaterSegment() {
        val segment = TestAssets.file("gapless.mp3").readBytes()
        val segmentMs = FfmpegDecoder.open(TestAssets.file("gapless.mp3").path).use { it.info.durationMs }
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:${(segmentMs + 999) / 1000}")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            repeat(3) { i -> appendLine("#EXTINF:${segmentMs / 1000.0},"); appendLine("segment$i.mp3") }
            appendLine("#EXT-X-ENDLIST")
        }.toByteArray()
        TestServer { exchange ->
            val body = if (exchange.requestURI.path.endsWith(".m3u8")) playlist else segment
            // whole responses only, as a cdn serving a playlist does: no ranges for the byte reader to seek with
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { server ->
            FfmpegDecoder.open("${server.base}/playlist.m3u8").use { decoder ->
                assertTrue(decoder.info.container, "hls" in decoder.info.container)
                assertTrue("hls should count as seekable", decoder.info.seekable)
                val rate = decoder.info.sampleRate
                val targetMs = segmentMs * 3 / 2
                decoder.seekToFrame(targetMs * rate / 1000)
                assertTrue(decoder.read(DoubleArray(2048)) > 0)
                assertEquals(targetMs.toDouble(), decoder.positionFrames * 1000.0 / rate, 100.0)
            }
        }
    }
}
