package com.aurora.music.ui.profile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.data.LocalProfile
import com.aurora.music.data.LocalProfileCodec
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.ui.auth.AccountScene
import com.aurora.music.ui.screens.profile.LocalProfileDialog
import com.aurora.music.ui.screens.profile.ProfileImages
import com.aurora.music.ui.screens.profile.ProfileScreen
import com.aurora.music.ui.screens.profile.rememberProfileAppearance
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.viewmodel.LocalProfileViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.Base64
import kotlin.io.path.createTempDirectory

class ProfileScreenTest {
    private val padding = PaddingValues(bottom = 24.dp)
    private val temp = createTempDirectory("aurora-profile").toFile()
    private val playlists = listOf(
        Playlist("p1", "Late Night Drive", "Synthwave after dark", "", 42, 0xFFFF7A59),
        Playlist("p2", "Focus Flow", "Instrumental concentration", "", 80, 0xFFFB7185),
        Playlist("p3", "Morning Coffee", "Easy acoustic mornings", "", 36, 0xFFF7B733),
    )
    private val artists = listOf(Artist("ar1", "Lunar Tide", "", 2_480_000), Artist("ar2", "Mara Quinn", "", 5_120_000))
    private val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)

    @After fun tearDown() { temp.deleteRecursively() }

    private fun picture(name: String, width: Int, height: Int, opaque: Boolean = true): File = Surface.makeRasterN32Premul(width, height).use { surface ->
        if (!opaque) surface.canvas.clear(Color.TRANSPARENT)
        val paint = Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, width.toFloat(), height.toFloat(), intArrayOf(Color.makeRGB(255, 46, 126), Color.makeRGB(90, 40, 200)), null)
        }
        surface.canvas.drawRect(if (opaque) Rect.makeWH(width.toFloat(), height.toFloat()) else Rect.makeXYWH(width / 4f, height / 4f, width / 2f, height / 2f), paint)
        File(temp, name).apply { writeBytes(surface.makeImageSnapshot().encodeToData(if (opaque) EncodedImageFormat.JPEG else EncodedImageFormat.PNG, 92)!!.bytes) }
    }

    private fun decoded(encoded: String): Image = Image.makeFromEncoded(Base64.getDecoder().decode(encoded))

    @Test fun profileShowsTheLibraryAndOpensDestinations() {
        val opened = mutableListOf<String>()
        val page = AccountScene("profile") {
            ProfileScreen(padding, "Mara", "https://music.example.com", "Navidrome", "", onEditProfile = { opened += "edit" },
                playlists = playlists, artists = artists, onBack = { opened += "back" }, onOpenSettings = { opened += "settings" },
                onOpenDetail = { kind, id -> opened += "$kind:$id" })
        }.use { scene ->
            val image = scene.shot()
            scene.click(880f, 286f)
            scene.click(44f, 44f)
            scene.click(916f, 44f)
            scene.click(108f, 470f)
            scene.click(108f, 760f)
            image
        }
        assertTrue(page.distinctColors() > 20)
        assertEquals(listOf("edit", "back", "settings", "artist:ar1", "playlist:p1"), opened)

        opened.clear()
        val server = AccountScene("profile-empty") {
            ProfileScreen(padding, "", "https://music.example.com", "", "", playlists = emptyList(), artists = emptyList(),
                onBack = {}, onOpenSettings = { opened += "settings" }, onOpenDetail = { _, _ -> })
        }.use { scene -> scene.shot().also { scene.click(880f, 286f) } }
        assertEquals(listOf("settings"), opened)
        assertTrue(server.differsFrom(page))
    }

    @Test fun profileImagesAreResizedAndCached() = runBlocking {
        val images = ProfileImages(File(temp, "cache"))
        val avatar = images.import(picture("avatar.jpg", 2400, 1600), banner = false)
        decoded(avatar).use { assertEquals(512, maxOf(it.width, it.height)) }
        val bytes = Base64.getDecoder().decode(avatar)
        assertTrue(bytes.size <= LocalProfileCodec.MAX_IMAGE_BYTES)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])

        val banner = images.import(picture("banner.png", 3000, 900, opaque = false), banner = true)
        decoded(banner).use { assertEquals(1280, it.width) }
        assertEquals(0x89.toByte(), Base64.getDecoder().decode(banner)[0])

        val small = images.import(picture("small.jpg", 80, 60), banner = false)
        decoded(small).use { assertEquals(80, it.width) }

        val url = images.imageUrl(avatar)
        assertTrue(url.startsWith("file:"))
        assertTrue(File(URI(url)).isFile)
        assertEquals(url, images.imageUrl(avatar))
        assertEquals("", images.imageUrl("not base64"))
        assertEquals("", images.imageUrl(null))
        val appearance = images.appearance(LocalProfile("", avatar, ""))
        assertEquals("Local Library", appearance.name)
        assertEquals(url, appearance.avatarUrl)

        try {
            images.import(File(temp, "notes.png").apply { writeText("not an image") }, banner = false)
            fail("text should not import")
        } catch (expected: IllegalArgumentException) {}
    }

    @Test fun localProfileDialogImportsImagesAndSaves() {
        var vm: LocalProfileViewModel? = null
        var dismissed = false
        val avatar = picture("avatar.jpg", 900, 900)
        val banner = picture("banner.jpg", 1600, 600)
        AccountScene("profile-dialog") {
            val container = LocalDesktopContainer.current
            val model: LocalProfileViewModel = viewModel { LocalProfileViewModel(container) }
            SideEffect { vm = model }
            LocalProfileDialog(onDismiss = { dismissed = true })
        }.use { scene ->
            scene.await(read = { vm!!.state.value }) { !it.busy }
            val empty = scene.shot()
            vm!!.name("Mara")
            vm!!.image(avatar, false)
            scene.await(read = { vm!!.state.value }) { !it.busy && it.preview.avatarUrl.isNotEmpty() }
            vm!!.image(banner, true)
            scene.await(read = { vm!!.state.value }) { !it.busy && it.preview.bannerUrl.isNotEmpty() }
            assertNull(vm!!.state.value.error)
            val filled = scene.shot("-filled")
            assertTrue(filled.differsFrom(empty))
            scene.click(706f, 681f)
            val saved = scene.await(read = { settingsStore.localProfile.first() }) { it.name == "Mara" }
            assertTrue(saved.avatar.orEmpty().isNotEmpty() && saved.banner.orEmpty().isNotEmpty())
            scene.await(read = { dismissed }) { it }
        }
    }

    @Test fun localSessionShowsTheSavedProfileAppearance() {
        val images = ProfileImages(File(temp, "cache"))
        val avatar = runBlocking { images.import(picture("avatar.jpg", 600, 600), banner = false) }
        val banner = runBlocking { images.import(picture("banner.jpg", 1600, 500), banner = true) }
        val plain = AccountScene("profile-local-plain", seed = { settingsStore.setLocalProfile(LocalProfile("Mara")) }) {
            val appearance = rememberProfileAppearance(local)
            ProfileScreen(padding, appearance.name, local.server, "Local", appearance.avatarUrl, appearance.bannerUrl,
                onEditProfile = {}, playlists = playlists, artists = artists, onBack = {}, onOpenSettings = {}, onOpenDetail = { _, _ -> })
        }.use { it.shot() }
        val styled = AccountScene("profile-local", seed = { settingsStore.setLocalProfile(LocalProfile("Mara", avatar, banner)) }) {
            val appearance = rememberProfileAppearance(local)
            ProfileScreen(padding, appearance.name, local.server, "Local", appearance.avatarUrl, appearance.bannerUrl,
                onEditProfile = {}, playlists = playlists, artists = artists, onBack = {}, onOpenSettings = {}, onOpenDetail = { _, _ -> })
        }.use { it.shot() }
        assertTrue(styled.differsFrom(plain))
    }
}
