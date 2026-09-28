package com.aurora.music.desktop.audio.decode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FfmpegProbeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun mp3Id3TagsReplayGainAndCoverAreRead() {
        val cover = png()
        val file = File(temp.newFolder("Música del ü 音"), "tagged track #1.mp3")
        file.writeBytes(taggedMp3(TestAssets.file("gapless.mp3").readBytes(), cover))
        val probe = FfmpegDecoder.probe(file)
        assertEquals("mp3", probe.info.codec)
        assertEquals(48_000, probe.info.sampleRate)
        assertEquals("Gapless ü 音", probe.tags.title)
        assertEquals("Aurora Artist", probe.tags.artist)
        assertEquals("Aurora Album", probe.tags.album)
        assertEquals("Various Artists", probe.tags.albumArtist)
        assertEquals(3, probe.tags.track)
        assertEquals(2, probe.tags.disc)
        assertEquals("2024", probe.tags.date)
        assertEquals("Ambient", probe.tags.genre)
        assertEquals(-6.2f, probe.tags.trackGainDb)
        assertEquals(1.5f, probe.tags.albumGainDb)
        assertEquals(0.988831f, probe.tags.trackPeak)
        assertArrayEquals(cover, probe.cover)
        FfmpegDecoder.open(file.toURI().toString()).use { decoder ->
            assertEquals(10_001 * 2, decodeAll(decoder).size)
            assertEquals("Gapless ü 音", decoder.tags.title)
        }
    }

    @Test fun flacVorbisCommentsR128AndPictureAreRead() {
        val cover = png()
        val comments = listOf(
            "TITLE=Low bits", "ARTIST=Aurora", "ALBUMARTIST=Aurora Ensemble", "ALBUM=Precision", "TRACKNUMBER=07",
            "DISCNUMBER=1/3", "DATE=2024-05-01", "GENRE=Test", "REPLAYGAIN_TRACK_GAIN=-3.50 dB", "REPLAYGAIN_ALBUM_GAIN=-4.25 dB",
            "REPLAYGAIN_TRACK_PEAK=0.5", "REPLAYGAIN_ALBUM_PEAK=0.75", "R128_TRACK_GAIN=-1280", "R128_ALBUM_GAIN=384",
        )
        val file = File(temp.newFolder("low bits"), "lowbits ü.flac")
        file.writeBytes(taggedFlac(TestAssets.file("lowbits-24.flac").readBytes(), comments, cover))
        val probe = FfmpegDecoder.probe(file)
        assertEquals(SourceSampleFormat(SampleKind.INTEGER, 24), probe.info.sampleFormat)
        assertEquals(85L, probe.info.durationMs)
        assertEquals(
            AudioTags(
                title = "Low bits", artist = "Aurora", album = "Precision", albumArtist = "Aurora Ensemble", track = 7, disc = 1,
                date = "2024-05-01", genre = "Test", trackGainDb = -3.5f, albumGainDb = -4.25f, trackPeak = 0.5f, albumPeak = 0.75f,
                r128TrackGainDb = -5f, r128AlbumGainDb = 1.5f, all = probe.tags.all,
            ),
            probe.tags,
        )
        assertArrayEquals(cover, probe.cover)
        FfmpegDecoder.open(file.toURI().toString()).use { decoder ->
            assertEquals(2, decoder.info.channels)
            val samples = decodeAll(decoder)
            assertEquals(4096 * 2, samples.size)
            for (frame in 0 until 4096) for (c in 0..1) assertEquals(((frame + c) % 17 - 8) * 8 / 8388608.0, samples[frame * 2 + c], 0.0)
        }
    }

    @Test fun untaggedFilesHaveNoCover() {
        val probe = FfmpegDecoder.probe(TestAssets.file("gapless.opus"))
        assertNull(probe.cover)
        assertNull(probe.tags.title)
        assertEquals("opus", probe.info.codec)
    }

    @Test fun tagTextIsParsedLeniently() {
        val tags = parseTags(
            mapOf(
                "track" to " 12/14 ", "disc" to "", "discnumber" to "2", "replaygain_track_gain" to "+2,75 dB",
                "replaygain_album_gain" to "not a gain", "r128_track_gain" to "256", "year" to "1999", "album artist" to "Band",
            ),
        )
        assertEquals(12, tags.track)
        assertEquals(2, tags.disc)
        assertEquals(2.75f, tags.trackGainDb)
        assertNull(tags.albumGainDb)
        assertEquals(1f, tags.r128TrackGainDb)
        assertEquals("1999", tags.date)
        assertEquals("Band", tags.albumArtist)
    }

    @Test fun fileUrisBecomeNativePaths() {
        assertEquals("C:\\Music\\My Album\\01 ü 音.flac", localPath("file:///C:/Music/My%20Album/01%20%C3%BC%20%E9%9F%B3.flac"))
        assertEquals("\\\\server\\share\\Music\\a b.flac", localPath("file://server/share/Music/a%20b.flac"))
        assertEquals("file:C:\\Music\\a b.flac", ffmpegUrl("file:///C:/Music/a%20b.flac"))
        assertEquals("https://host/a.mp3?x=1", ffmpegUrl("https://host/a.mp3?x=1"))
        assertEquals("https://host/rest/stream.view", describe("https://user:pw@host/rest/stream.view?t=secret"))
    }
}
