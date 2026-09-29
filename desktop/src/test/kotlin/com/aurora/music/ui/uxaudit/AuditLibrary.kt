package com.aurora.music.ui.uxaudit

import com.aurora.music.desktop.audio.decode.TestAssets
import com.aurora.music.desktop.audio.decode.taggedFlac
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

internal data class AlbumSpec(val artist: String, val title: String, val year: Int, val genre: String, val tracks: Int, val style: Int, val hue: Float)

internal object AuditLibrary {
    val artists = listOf(
        "Lunar Tide", "Mara Quinn", "Coastlines", "The Velvet Arcade", "Kofi Mensah", "Solène",
        "Nightjar", "Hollow Pines", "Juno Park", "Atlas Ensemble", "DJ Kasimir", "The Marlowe Sparks",
    )

    val albums = listOf(
        AlbumSpec("Lunar Tide", "Nocturne", 2024, "Dream Pop", 10, 0, 330f),
        AlbumSpec("Lunar Tide", "Low Orbit", 2021, "Dream Pop", 8, 1, 250f),
        AlbumSpec("Lunar Tide", "Northern Nights EP", 2022, "Dream Pop", 5, 4, 200f),
        AlbumSpec("Mara Quinn", "Neon Hours", 2024, "Synthpop", 12, 2, 300f),
        AlbumSpec("Mara Quinn", "Paper Satellites", 2020, "Synthpop", 9, 3, 20f),
        AlbumSpec("Coastlines", "Tidal", 2023, "Indie Rock", 11, 0, 190f),
        AlbumSpec("Coastlines", "Saltwater Sessions (Live at the Harbour Hall)", 2019, "Indie Rock", 7, 1, 175f),
        AlbumSpec("The Velvet Arcade", "Midnight Transit", 2023, "Alternative", 10, 4, 275f),
        AlbumSpec("The Velvet Arcade", "Glass Houses", 2018, "Alternative", 9, 2, 45f),
        AlbumSpec("Kofi Mensah", "Harmattan", 2024, "Afrobeat", 8, 3, 30f),
        AlbumSpec("Kofi Mensah", "Accra After Dark", 2021, "Jazz", 6, 0, 15f),
        AlbumSpec("Solène", "Lumière Noire", 2022, "French Pop", 11, 1, 350f),
        AlbumSpec("Solène", "Cinq Saisons", 2020, "French Pop", 5, 4, 90f),
        AlbumSpec("Nightjar", "Night Swimming", 2023, "Ambient", 7, 2, 220f),
        AlbumSpec("Nightjar", "Field Recordings, Vol. 2", 2021, "Ambient", 6, 3, 120f),
        AlbumSpec("Hollow Pines", "Winter Cartography", 2022, "Folk", 12, 0, 160f),
        AlbumSpec("Hollow Pines", "Embers", 2019, "Folk", 8, 1, 25f),
        AlbumSpec("Juno Park", "Static Bloom", 2024, "K-Indie", 10, 4, 310f),
        AlbumSpec("Juno Park", "Signal / Noise", 2023, "K-Indie", 6, 2, 60f),
        AlbumSpec("Atlas Ensemble", "Movements for a Quiet City", 2021, "Modern Classical", 9, 3, 210f),
        AlbumSpec("Atlas Ensemble", "Études in Blue", 2023, "Modern Classical", 7, 0, 230f),
        AlbumSpec("DJ Kasimir", "Warehouse Tapes", 2024, "House", 11, 1, 285f),
        AlbumSpec("DJ Kasimir", "4AM Frequencies", 2022, "Techno", 8, 4, 265f),
        AlbumSpec("The Marlowe Sparks", "Heartland Radio", 2020, "Americana", 12, 2, 35f),
        AlbumSpec("The Marlowe Sparks", "Gasoline Hymns", 2023, "Americana", 9, 3, 5f),
        AlbumSpec("Various Artists", "Late Night Essentials", 2024, "Compilation", 12, 0, 260f),
    )

    private val titles = listOf(
        "Golden Hour", "Paper Moon", "Northern Nights", "Slow Motion", "Afterglow", "Blue Static", "Satellite Heart",
        "Undertow", "Wildflower", "City of Glass", "Neon Rain", "Half Light", "Lighthouse", "Silver Lining", "Parallel Lines",
        "Night Drive", "Echo Park", "Tangerine", "Hologram", "Dust & Diamonds", "Low Tide", "Firefly", "Starling",
        "Overpass", "The Long Way Home", "Cold Water", "Velvet", "Moonlit Avenue", "Aurora Borealis", "Driftwood",
        "Soft Focus", "Midnight Oil", "Chrome Hearts", "Summer Ghosts", "Cassette", "Horizon Line", "Ember Glow",
        "Postcards", "Radio Silence", "Kaleidoscope", "Weightless", "Monsoon", "Harbour Lights", "Glasshouse",
        "Night Swimming", "Open Road", "Polaroid", "Sugar Pine", "Terminal", "Wavelength", "Constellations",
        "Fever Dream", "First Light", "Glow in the Dark", "Heatwave", "Island Time", "Jetlag", "Kingfisher",
        "Last Train Out", "Magnolia", "Nightingale", "Orbit", "Palisades", "Quiet Storm", "Riverbend",
        "Sleepwalker", "Tidal Wave", "Umbrella", "Vanishing Point", "Winter Sun", "Xylophone Dreams", "Yesterday's News",
        "Zero Gravity", "Amber", "Balcony", "Cathedral", "Daybreak", "Evergreen", "Foxglove", "Gravity Well",
        "Honey", "Ivory Tower", "Juniper", "Kerosene", "Lullaby for a Late Night Train", "Mirrorball", "Nomad",
        "Ocean Drive", "Pale Blue", "Quartz", "Rosewater", "Shoreline", "Twilight Zone", "Uptown",
        "Violet Hour", "Waterfall", "Youth", "Zephyr", "Across the Night Sky", "Borrowed Time", "Coastal Highway",
        "Dreamcatcher", "Electric Feel", "Faultline", "Ghost Town", "Hideaway", "Interlude", "Jupiter",
        "Kite String", "Lost in the Night", "Meridian", "Nightfall (Extended Mix)", "Outro", "Prism",
        "Reverie", "Snowglobe", "Telescope", "Undercurrent", "Vagabond", "Wanderlust", "Aftermath",
        "Blackout", "Cloudbusting", "Downpour", "Eclipse", "Fragments", "Gemini", "Hourglass",
        "Illuminate", "Jasmine", "Kinetic", "Lanterns", "Midnight Garden", "Nebula", "Overture",
        "Pathways", "Resonance", "Solstice", "Transit", "Unison", "Voyager", "Whisper", "Afterimage",
        "Bonfire", "Crosswalk", "Daydream", "Everglow", "Fjord", "Greyhound", "Hummingbird",
        "Indigo", "Jubilee", "Keepsake", "Longitude", "Mosaic", "Northern Lights", "Origami",
        "Paperweight", "Riptide", "Sundial", "Tailwind", "Uncharted", "Vista", "Windchime",
        "Yearling", "Zenith", "Arcadia", "Brightside", "Carousel", "Dune", "Estuary", "Fable",
    )

    val playlists = listOf(
        "Late Night Drive" to 28, "Focus Flow" to 40, "Sunday Morning" to 18, "Gym Rotation" to 24,
        "Rainy Day Jazz & Folk" to 14, "Road Trip 2024" to 36,
    )

    fun bio(artist: String) = "$artist is an independent act whose records blend warm analogue textures with modern " +
        "production. Formed in a shared rehearsal space, the project grew from late night home recordings into a touring " +
        "band known for immersive live sets, patient song structures and a devoted following across Europe and Asia."

    val root: File by lazy { Files.createTempDirectory("aurora-ux-audit").toFile().also { build(it) } }
    val music: File get() = File(root, "Music")
    val portraits: File get() = File(root, "Portraits")

    private fun build(root: File) {
        val base = TestAssets.file("lowbits-24.flac").readBytes()
        val random = Random(7)
        var titleIndex = 0
        val music = File(root, "Music")
        albums.forEachIndexed { albumIndex, album ->
            val cover = cover(album.style, album.hue, albumIndex)
            val dir = File(music, "${album.artist}/${album.year} - ${album.title.replace('/', '-')}").apply { mkdirs() }
            repeat(album.tracks) { i ->
                val title = titles[titleIndex++ % titles.size]
                val trackArtist = when {
                    album.artist == "Various Artists" -> artists[(i * 5 + 3) % artists.size]
                    albumIndex == 3 && i == 4 -> "Mara Quinn feat. Juno Park"
                    else -> album.artist
                }
                val seconds = if (album.genre == "Modern Classical" && i == 0) 684 else 150 + random.nextInt(190)
                val comments = listOf(
                    "TITLE=$title", "ARTIST=$trackArtist", "ALBUM=${album.title}", "ALBUMARTIST=${album.artist}",
                    "TRACKNUMBER=${i + 1}", "DISCNUMBER=1", "DATE=${album.year}-0${1 + i % 9}-1${i % 9}", "GENRE=${album.genre}",
                )
                val bytes = withDuration(taggedFlac(base, comments, cover), seconds)
                File(dir, "%02d %s.flac".format(i + 1, title.replace(Regex("[\\\\/:*?\"<>|]"), "_"))).writeBytes(bytes)
            }
        }
        val portraits = File(root, "Portraits").apply { mkdirs() }
        artists.forEachIndexed { index, artist -> File(portraits, "$index.png").writeBytes(portrait(index * 29f + 11f)) }
    }

    fun portraitFor(artist: String): File? = artists.indexOf(artist).takeIf { it >= 0 }?.let { File(portraits, "$it.png") }

    private fun withDuration(flac: ByteArray, seconds: Int): ByteArray {
        val total = seconds.toLong() * 48_000
        flac[21] = ((flac[21].toInt() and 0xF0) or ((total shr 32).toInt() and 0x0F)).toByte()
        for (i in 0 until 4) flac[22 + i] = (total shr (24 - 8 * i)).toByte()
        return flac
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val hue = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((hue / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color.makeRGB(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    private fun cover(style: Int, hue: Float, seed: Int): ByteArray {
        val size = 480f
        val surface = Surface.makeRasterN32Premul(size.toInt(), size.toInt())
        val canvas = surface.canvas
        val a = hsv(hue, 0.75f, 0.95f)
        val b = hsv(hue + 50f, 0.85f, 0.35f)
        val c = hsv(hue + 180f, 0.6f, 0.98f)
        val random = Random(seed)
        canvas.drawRect(Rect.makeWH(size, size), Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, size, size, intArrayOf(a, b), null, GradientStyle.DEFAULT)
        })
        when (style) {
            0 -> {
                canvas.drawCircle(size * 0.62f, size * 0.42f, size * 0.26f, Paint().apply { color = c })
                canvas.drawRect(Rect.makeXYWH(0f, size * 0.68f, size, size * 0.32f), Paint().apply { color = b; alpha = 220 })
            }
            1 -> repeat(7) { i ->
                canvas.drawCircle(size / 2, size / 2, size * (0.46f - i * 0.06f), Paint().apply {
                    mode = PaintMode.STROKE; strokeWidth = 10f; color = if (i % 2 == 0) c else a; alpha = 200
                })
            }
            2 -> repeat(9) { i ->
                canvas.drawRect(Rect.makeXYWH(i * size / 9, 0f, size / 18, size), Paint().apply { color = hsv(hue + i * 14f, 0.7f, 0.9f); alpha = 170 })
            }
            3 -> repeat(4) { row -> repeat(4) { col ->
                if (random.nextFloat() > 0.35f) canvas.drawRRect(RRect.makeXYWH(24f + col * 112f, 24f + row * 112f, 96f, 96f, 18f),
                    Paint().apply { color = hsv(hue + random.nextInt(90), 0.65f, 0.95f); alpha = 230 })
            } }
            else -> {
                canvas.drawCircle(size * 0.38f, size * 0.5f, size * 0.28f, Paint().apply { color = c; alpha = 200 })
                canvas.drawCircle(size * 0.62f, size * 0.5f, size * 0.28f, Paint().apply { color = a; alpha = 170 })
            }
        }
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun portrait(hue: Float): ByteArray {
        val size = 400f
        val surface = Surface.makeRasterN32Premul(size.toInt(), size.toInt())
        val canvas = surface.canvas
        canvas.drawRect(Rect.makeWH(size, size), Paint().apply {
            shader = Shader.makeRadialGradient(size * 0.5f, size * 0.35f, size * 0.8f,
                intArrayOf(hsv(hue, 0.5f, 0.9f), hsv(hue + 40f, 0.8f, 0.25f)), null, GradientStyle.DEFAULT)
        })
        val figure = Paint().apply { color = hsv(hue + 200f, 0.35f, 0.15f); alpha = 230 }
        canvas.drawCircle(size * 0.5f, size * 0.4f, size * 0.16f, figure)
        canvas.drawOval(Rect.makeXYWH(size * 0.22f, size * 0.6f, size * 0.56f, size * 0.6f), figure)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}
