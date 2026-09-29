package com.aurora.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.aurora.music.data.DetailData
import com.aurora.music.data.DownloadRow
import com.aurora.music.data.DuplicateGroup
import com.aurora.music.data.FolderContent
import com.aurora.music.data.FolderNode
import com.aurora.music.data.Pin
import com.aurora.music.data.SmartPlaylist
import com.aurora.music.data.SmartRule
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.UiPrefs
import com.aurora.music.data.remote.ArtistInfo
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.theme.AuroraTheme
import com.aurora.music.viewmodel.LibraryUiState
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal object Samples {
    private val accents = listOf(0xFFFF2E7E, 0xFFFF7A59, 0xFFC24CE0, 0xFFFB7185, 0xFFF7B733, 0xFFA855F7, 0xFF38BDF8, 0xFF34D399)
    private val artDir = File(System.getProperty("java.io.tmpdir"), "aurora-browse-art").apply { mkdirs() }

    fun art(i: Int): String {
        val file = File(artDir, "art$i.png")
        if (!file.exists()) {
            val a = java.awt.Color(accents[i % accents.size].toInt())
            val b = java.awt.Color(accents[(i + 3) % accents.size].toInt()).darker()
            val img = BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply {
                paint = GradientPaint(0f, 0f, a, 240f, 240f, b)
                fillRect(0, 0, 240, 240)
                color = java.awt.Color(255, 255, 255, 60)
                fillOval(40 + i * 7 % 60, 50, 120, 120)
                dispose()
            }
            ImageIO.write(img, "png", file)
        }
        return file.absolutePath
    }

    private val titles = listOf(
        "Midnight Bloom", "Velvet Skyline", "Paper Planes", "Gravity", "Saltwater", "Ember", "Cassette Dreams", "Northern Lights",
        "Honeyglow", "Static Heart", "Driftwood", "After Hours", "Aurora Road", "Blue Hour", "Copper Sun", "Daybreak",
        "Echo Park", "Firefly", "Golden Gate", "Harbor Lights", "Ivory", "Jade Coast", "Kaleidoscope", "Lantern",
        "Moonrise", "Neon Rain", "Orbit", "Polaris", "Quiet Storm", "Riverbed", "Silver Lining", "Tidal Wave",
        "Undertow", "Violet", "Wildfire", "Xanadu", "Yellow Brick", "Zephyr", "1999", "Afterglow",
    )
    private val artists = listOf("Lunar Tide", "Mara Quinn", "The Foxgloves", "Aerial", "Coastlines", "Wren Holloway")
    private val genres = listOf("Synthpop", "Indie", "Dream Pop", "Electronic")

    val songs: List<Song> = titles.mapIndexed { i, t ->
        Song(
            "s$i", t, artists[i % artists.size], "Album ${i % 6}", art(i % 8), 170 + i * 3,
            explicit = i % 7 == 3, accentArgb = accents[i % accents.size], albumId = "a${i % 6}", artistId = "ar${i % 6}",
            suffix = if (i % 2 == 0) "flac" else "mp3", bitrateKbps = if (i % 2 == 0) 1411 else 320, genre = genres[i % genres.size],
            dateAddedSec = 1_700_000_000L + i * 1000, playCount = (i * 7) % 13,
        )
    }

    val albums: List<Album> = listOf(
        Album("a0", "Nocturne", "Lunar Tide", art(0), 2024, 11),
        Album("a1", "Neon Hours", "Mara Quinn", art(1), 2023, 12),
        Album("a2", "Wildflower", "The Foxgloves", art(2), 2022, 10),
        Album("a3", "Lightyears", "Aerial", art(3), 2024, 4, durationSec = 900),
        Album("a4", "Tidal", "Coastlines", art(4), 2021, 13),
        Album("a5", "Slow Burn", "Wren Holloway", art(5), 2023, 1, durationSec = 200),
        Album("a6", "Analog", "Polaroid Kids", art(6), 2020, 8),
        Album("a7", "Frequency", "Neon Vows", art(7), 2019, 9),
    )

    val artistList: List<Artist> = artists.mapIndexed { i, n -> Artist("ar$i", n, art(i + 2), 0) }

    val playlists: List<Playlist> = listOf(
        Playlist("p1", "Daily Mix 1", "Lunar Tide, Aerial and more", art(3), 50, accents[0]),
        Playlist("p2", "Late Night Drive", "Synthwave after dark", art(5), 42, accents[1]),
        Playlist("p3", "Focus Flow", "Instrumental concentration", art(6), 80, accents[3]),
        Playlist("p4", "Morning Coffee", "Easy acoustic mornings", art(7), 36, accents[4]),
    )

    val smart = SmartPlaylist("smart-1", "Heavy rotation", rules = listOf(SmartRule("playCount", "gt", "5"), SmartRule("liked", "isTrue", "")))

    val pins = listOf(Pin("a1", "album", "Neon Hours", "Mara Quinn", art(1)), Pin("ar0", "artist", "Lunar Tide", "", art(2)))

    fun library(filter: com.aurora.music.model.LibraryFilter, layout: com.aurora.music.model.LibraryLayout = com.aurora.music.model.LibraryLayout.LIST, sort: com.aurora.music.model.LibrarySort = com.aurora.music.model.LibrarySort.RECENT) = LibraryUiState(
        filter = filter, sort = sort, layout = layout, loading = false,
        albums = albums, artists = artistList, playlists = playlists, songs = songs,
        downloadedRows = listOf(DownloadRow("a0", "album", "Nocturne", "Lunar Tide", art(0), accents[0]), DownloadRow("p2", "playlist", "Late Night Drive", "", art(5), accents[1])),
        likedSongCount = 128, likedCover = art(4), supportsFolders = true, smartPlaylists = listOf(smart),
    )

    val albumDetail = DetailData(
        DetailInfo("Nocturne", "Lunar Tide • 2024 • 11 tracks", art(0), accents[0], isArtist = false, songCount = 11, typeLabel = "Album"),
        songs.take(11).map { it.copy(album = "Nocturne", artist = "Lunar Tide") },
    )

    val artistDetail = DetailData(
        DetailInfo("Lunar Tide", "Artist", "", accents[2], isArtist = true, songCount = 8, typeLabel = "Artist"),
        songs.filter { it.artistId == "ar0" },
        albums,
    )

    val artistInfo = ArtistInfo(
        name = "Lunar Tide", bio = "Lunar Tide is a dream pop duo from Reykjavik blending analog synths with hushed vocals. Their records drift between ambient nocturnes and bright festival anthems.",
        imageUrl = art(2), tags = listOf("dream pop", "synthpop", "icelandic"), country = "Iceland", yearsActive = "2014 – present", found = true,
    )

    val playlistDetail = DetailData(
        DetailInfo("Late Night Drive", "Synthwave after dark", art(5), accents[1], isArtist = false, songCount = 24, typeLabel = "Playlist", editableDescription = "Synthwave after dark"),
        songs.take(24),
    )

    val folder = FolderContent(
        "root/music", "Music",
        folders = listOf(FolderNode("f1", "Albums"), FolderNode("f2", "Live recordings"), FolderNode("f3", "Singles")),
        songs = songs.take(8),
    )

    val duplicates = listOf(
        DuplicateGroup("Midnight Bloom", "Lunar Tide", listOf(songs[0], songs[0].copy(id = "d0", suffix = "mp3", bitrateKbps = 320, album = "Nocturne (Deluxe)"))),
        DuplicateGroup("Gravity", "Aerial", listOf(songs[3], songs[3].copy(id = "d3", suffix = "m4a", bitrateKbps = 256), songs[3].copy(id = "d33", suffix = "ogg", bitrateKbps = 192, album = ""))),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
internal class Harness(private val width: Int, private val height: Int, dark: Boolean = true, content: @Composable () -> Unit) : AutoCloseable {
    private var clock = 0L
    val scene = ImageComposeScene(width, height, Density(1f)) {
        AuroraTheme(UiPrefs(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout(1440, 900)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AmbientBackground()
                    content()
                }
            }
        }
    }

    fun settle(millis: Long = 900): Image {
        var image = frame()
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            Thread.sleep(40)
            image = frame()
        }
        return image
    }

    private fun frame(): Image {
        Snapshot.sendApplyNotifications()
        clock += 50_000_000
        return scene.render(clock)
    }

    fun rightClick(x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), buttons = PointerButtons(), button = PointerButton.Secondary)
    }

    override fun close() = scene.close()
}

internal fun Image.save(label: String, name: String): Image {
    System.getenv("AURORA_SHOTS")?.let { File(it, label).apply { mkdirs() } }?.let { dir ->
        File(dir, "$name.png").writeBytes(encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
    return this
}

internal fun Image.pixels(): IntArray = Bitmap.makeFromImage(this).let { b -> IntArray(width * height) { b.getColor(it % width, it / width) } }

internal fun Image.distinctColors(step: Int = 6): Int {
    val b = Bitmap.makeFromImage(this)
    val seen = HashSet<Int>()
    for (y in 0 until height step step) for (x in 0 until width step step) seen += b.getColor(x, y)
    return seen.size
}

internal fun Image.region(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
    val b = Bitmap.makeFromImage(this)
    return IntArray((x1 - x0) * (y1 - y0)) { b.getColor(x0 + it % (x1 - x0), y0 + it / (x1 - x0)) }
}
