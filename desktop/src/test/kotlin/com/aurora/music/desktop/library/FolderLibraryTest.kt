package com.aurora.music.desktop.library

import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalStore
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.TestAssets
import com.aurora.music.desktop.audio.decode.taggedFlac
import com.aurora.music.desktop.audio.decode.taggedMp3
import com.aurora.music.desktop.audio.decode.wavBytes
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.desktop.platform.openDesktopUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class FolderLibraryTest {
    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val probes = AtomicInteger()
    private val mp3Cover = image(4, 4)
    private val flacCover = image(6, 6)
    private lateinit var music: File
    private lateinit var index: File
    private lateinit var mp3: File

    @Before fun setUp() {
        music = temp.newFolder("Música ü")
        index = temp.newFolder("index")
        mp3 = write("Aurora Artist/Aurora Album/01 gapless ü.mp3", taggedMp3(TestAssets.file("gapless.mp3").readBytes(), mp3Cover))
        write("Low Bits/02 a.flac", flac("TITLE=Second", "TRACKNUMBER=2", "REPLAYGAIN_TRACK_GAIN=-3.50 dB", "REPLAYGAIN_ALBUM_GAIN=-4.25 dB"))
        write("Low Bits/01 b.flac", flac("TITLE=First", "TRACKNUMBER=1", "R128_TRACK_GAIN=-2560"))
        write("Loose/untagged.wav", wav(seconds = 2))
        write("Loose/cover.png", image(8, 8))
        write("notes.txt", "not audio".toByteArray())
    }

    @After fun tearDown() = scope.cancel()

    private fun write(path: String, bytes: ByteArray) = File(music, path).apply { parentFile.mkdirs(); writeBytes(bytes) }

    private fun image(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, 0x20c060 + width) }, "png", it)
    }.toByteArray()

    private fun flac(vararg comments: String) = taggedFlac(TestAssets.file("lowbits-24.flac").readBytes(),
        listOf("ARTIST=Aurora", "ALBUMARTIST=Aurora Ensemble", "ALBUM=Precision", "DATE=2024-05-01", "GENRE=Test") + comments, flacCover)

    private fun wav(seconds: Int) = wavBytes(44_100, 2, 16, 44_100 * seconds) { frame, _ -> frame % 64 }

    private fun library(vararg roots: File, scope: CoroutineScope = this.scope) = FolderLibrary(index, folders = { roots.map { it.path } },
        fileUri = ::desktopFileUri, scope = scope, probe = { probes.incrementAndGet(); FfmpegDecoder.probe(it) })

    @Test fun scanReadsTagsFormatsAndCovers() = runBlocking {
        write("Loose/broken.mp3", ByteArray(512) { 7 })
        val library = library(music)
        library.ensureLoaded()
        assertEquals(5, probes.get())
        assertEquals(4, library.songs.size)
        assertEquals(1, library.revision.value)

        val song = library.songs.single { it.suffix == "mp3" }
        assertTrue(song.id, song.id.matches(Regex("local-[0-9a-f]{40}")))
        assertEquals("Gapless ü 音", song.title)
        assertEquals("Aurora Artist", song.artist)
        assertEquals("Aurora Album", song.album)
        assertEquals(desktopFileUri(mp3.path), song.streamUrl)
        assertEquals(mp3.path, song.path)
        assertEquals(48_000, song.sampleRateHz)
        assertEquals(0, song.bitDepth)
        assertTrue(song.bitrateKbps > 0)
        assertEquals(-6.2f, song.replayGainTrack)
        assertEquals(1.5f, song.replayGainAlbum)
        assertEquals("Ambient", song.genre)
        assertArrayEquals(mp3Cover, openDesktopUri(song.artworkUrl)!!.use { it.readBytes() })
        assertEquals(song, library.song(song.id))

        val loose = library.songs.single { it.suffix == "wav" }
        assertEquals("untagged", loose.title)
        assertEquals("Unknown artist", loose.artist)
        assertEquals("Loose", loose.album)
        assertEquals(2, loose.durationSec)
        assertEquals(44_100, loose.sampleRateHz)
        assertEquals(16, loose.bitDepth)
        assertEquals(1411, loose.bitrateKbps)
        assertEquals(desktopFileUri(File(music, "Loose/cover.png").path), loose.artworkUrl)

        val first = library.songs.single { it.title == "First" }
        assertEquals(24, first.bitDepth)
        assertEquals(-5f, first.replayGainTrack)
        assertEquals(-3.5f, library.songs.single { it.title == "Second" }.replayGainTrack)
        assertEquals(2, File(index, "covers").list()!!.size)

        val precision = library.albums.single { it.title == "Precision" }
        assertEquals("Aurora Ensemble", precision.artist)
        assertEquals(2024, precision.year)
        assertEquals(listOf("First", "Second"), library.songsByAlbumId(precision.id).map { it.title })
        assertEquals("Various Artists", library.albums.single { it.title == "Aurora Album" }.artist)
        assertEquals("Unknown artist", library.albums.single { it.title == "Loose" }.artist)

        assertEquals(setOf("Aurora", "Aurora Artist", "Unknown artist"), library.artists.map { it.name }.toSet())
        val aurora = library.artists.single { it.name == "Aurora" }
        assertEquals(aurora, library.artist(aurora.id))
        assertEquals(setOf("First", "Second"), library.songsByArtistId(aurora.id).map { it.title }.toSet())
        assertEquals(listOf(precision.id), library.albumsByArtistId(aurora.id).map { it.id })

        val root = "Computer/" + music.path.replace('\\', '/').trim('/')
        assertEquals(root, library.folderRoot)
        assertEquals(listOf("Aurora Artist", "Loose", "Low Bits") to emptyList<Any>(), library.browse(""))
        assertEquals(listOf("untagged"), library.browse("$root/Loose").second.map { it.title })

        assertEquals(song.streamUrl, library.findMatch("Aurora Artist", "Gapless ü 音", 0)?.streamUrl)
        assertEquals(loose.streamUrl, library.findMatch("Unknown Artist", "untagged", 5)?.streamUrl)
        assertNull(library.findMatch("Someone else", "untagged", 2))

        val backend = LocalBackend(library, LocalStore(temp.newFolder("store")), Session("On this device", "Local Library", "", "local", ServerType.LOCAL))
        val folders = backend.browseFolder("")!!
        assertEquals(listOf("$root/Aurora Artist", "$root/Loose", "$root/Low Bits"), folders.folders.map { it.id })
        assertEquals(listOf("First", "Second"), backend.detail("album", precision.id)!!.tracks.map { it.title })
    }

    @Test fun indexIsReusedAndInvalidatedByFileChanges() = runBlocking {
        val ids = library(music).run { ensureLoaded(); songs.map { it.id }.toSet() }
        probes.set(0)
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = library(music, scope = own)
            library.ensureLoaded()
            own.coroutineContext.job.children.toList().joinAll()
            assertEquals(ids, library.songs.map { it.id }.toSet())
            assertEquals(0, probes.get())
            assertEquals(0, library.revision.value)

            write("Loose/untagged.wav", wav(seconds = 1))
            library.refresh()
            assertEquals(1, probes.get())
            assertEquals(1, library.revision.value)
            assertEquals(1, library.songs.single { it.suffix == "wav" }.durationSec)

            File(music, "Low Bits/01 b.flac").delete()
            library.refresh()
            assertEquals(3, library.songs.size)
            assertEquals(2, File(index, "covers").list()!!.size)
            File(music, "Low Bits/02 a.flac").delete()
            library.refresh()
            assertEquals(listOf(mp3Cover.toList()), File(index, "covers").listFiles()!!.map { it.readBytes().toList() })
            assertEquals(1, probes.get())
        } finally {
            own.cancel()
        }
    }

    @Test fun unavailableFoldersKeepTheirTracksUntilRemovedFromSettings() = runBlocking {
        val extra = temp.newFolder("Extra")
        File(extra, "extra.wav").writeBytes(wav(seconds = 1))
        val library = library(music, extra)
        library.ensureLoaded()
        assertEquals(5, library.songs.size)
        val away = File(temp.root, "Extra-away")
        assertTrue(extra.renameTo(away))
        library.refresh()
        assertEquals(5, library.songs.size)
        val narrowed = library(music)
        narrowed.ensureLoaded()
        assertEquals(4, narrowed.songs.size)
    }
}
