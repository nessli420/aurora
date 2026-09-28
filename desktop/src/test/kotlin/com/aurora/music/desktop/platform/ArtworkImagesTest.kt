package com.aurora.music.desktop.platform

import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.aurora.music.data.ArtistSeparators
import com.aurora.music.data.artwork.ArtworkRepository
import com.aurora.music.data.artwork.ArtworkRequest
import com.aurora.music.data.artwork.ArtworkUrls
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class ArtworkImagesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun png(width: Int = 3, height: Int = 2): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0x3366ff) }, "png", it)
    }.toByteArray()

    private fun artworkUri(request: ArtworkRequest): String =
        "content://${ArtworkUrls.AUTHORITY}/v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(request).toByteArray())

    @Test fun sizesComeFromTheEncodedHeader() {
        assertEquals(3 to 2, SkiaArtworkImages.size(png()))
        assertNull(SkiaArtworkImages.size(byteArrayOf(1, 2, 3, 4)))
        assertFalse(SkiaArtworkImages.isPlaceholder(png(64, 64)))
        assertFalse(SkiaArtworkImages.isPlaceholder(byteArrayOf(1, 2, 3)))
    }

    @Test fun navidromePlaceholderSurvivesResizing() {
        val fixture = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "_local/navidrome-album-placeholder.webp") }.firstOrNull { it.isFile }
        assumeTrue("Optional upstream image fixture is not installed", fixture != null)
        val original = fixture!!.readBytes()
        assertTrue(SkiaArtworkImages.isPlaceholder(original))
        Image.makeFromEncoded(original).use { image ->
            for (size in listOf(64, 150, 300, 600)) {
                for (format in listOf(EncodedImageFormat.JPEG, EncodedImageFormat.PNG)) {
                    val bytes = Surface.makeRasterN32Premul(size, size).use { surface ->
                        surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                            Rect.makeWH(size.toFloat(), size.toFloat()), FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
                        surface.makeImageSnapshot().use { requireNotNull(it.encodeToData(format, 85)).bytes }
                    }
                    assertTrue("Placeholder $size $format", SkiaArtworkImages.isPlaceholder(bytes))
                }
            }
        }
    }

    @Test fun desktopFileUrisRoundTrip() {
        val file = File(temp.newFolder("Música ü 音"), "cover art #1.png").apply { writeBytes(png()) }
        val uri = desktopFileUri(file.path)
        assertTrue(uri, uri.startsWith("file:///"))
        assertArrayEquals(file.readBytes(), openDesktopUri(uri)!!.use { it.readBytes() })
        assertNull(openDesktopUri(desktopFileUri(File(temp.root, "missing.png").path)))
        assertNull(openDesktopUri("https://example.com/cover.png"))
    }

    @Test fun imageLoaderServesArtworkAndLocalFiles() = runBlocking {
        val cover = png()
        val downloads = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            downloads.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(cover.toResponseBody()).build()
        }.build()
        val repository = ArtworkRepository(temp.newFolder("artwork"), ::openDesktopUri, SkiaArtworkImages, offline = { false },
            enabled = { false }, separators = { ArtistSeparators() }, http = http)
        val loader = desktopImageLoader(temp.newFolder("images"), http, repository)
        try {
            val server = artworkUri(ArtworkRequest("Artist", "Album", original = "https://server/rest/getCoverArt.view?id=7"))
            val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(server).build())
            assertTrue("$result", result is SuccessResult)
            assertEquals(3, (result as SuccessResult).image.width)
            assertEquals(1, downloads.get())
            val broken = "content://${ArtworkUrls.AUTHORITY}/v1/not-json"
            assertTrue(loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(broken).build()) is ErrorResult)
            val local = File(temp.newFolder("Música ü 音"), "cover #1.png").apply { writeBytes(png(5, 4)) }
            val file = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(desktopFileUri(local.path)).build())
            assertTrue("$file", file is SuccessResult)
            assertEquals(5, (file as SuccessResult).image.width)
        } finally {
            loader.shutdown()
        }
    }
}
