package com.aurora.music.ui.screens.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.aurora.music.R
import com.aurora.music.data.LocalProfile
import com.aurora.music.data.LocalProfileCodec
import com.aurora.music.data.ProfileAppearance
import com.aurora.music.data.Session
import com.aurora.music.data.persistBackupFileAtomically
import com.aurora.music.data.profileAppearance
import com.aurora.music.desktop.platform.decodeImage
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.appString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class ProfileImages(private val directory: File) {
    suspend fun import(file: File, banner: Boolean): String = withContext(Dispatchers.IO) {
        require(file.length() <= 20L * 1024 * 1024) { appString(R.string.text_choose_an_image_smaller_than_20_mb_cc7669) }
        val image = requireNotNull(decodeImage(file.readBytes())) { appString(R.string.text_cannot_read_this_image_9a45ce) }
        image.use { source ->
            require(source.width.toLong() * source.height <= 100_000_000) { appString(R.string.text_choose_a_supported_image_under_100_megapixels_af6c16) }
            val format = if (source.isOpaque) EncodedImageFormat.JPEG else EncodedImageFormat.PNG
            var target = if (banner) 1280 else 512
            while (target >= 64) {
                val scale = minOf(1.0, target.toDouble() / maxOf(source.width, source.height))
                val width = (source.width * scale).toInt().coerceAtLeast(1)
                val height = (source.height * scale).toInt().coerceAtLeast(1)
                val bytes = Surface.makeRasterN32Premul(width, height).use { surface ->
                    surface.canvas.drawImageRect(source, Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                        Rect.makeWH(width.toFloat(), height.toFloat()), FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true)
                    surface.makeImageSnapshot().use { it.encodeToData(format, 88)?.bytes }
                }
                if (bytes != null && bytes.size <= LocalProfileCodec.MAX_IMAGE_BYTES) return@withContext Base64.getEncoder().encodeToString(bytes)
                target = (target * .75).toInt()
            }
            error(appString(R.string.text_cannot_resize_this_image_be7b26))
        }
    }

    fun appearance(profile: LocalProfile): ProfileAppearance = ProfileAppearance(
        profile.name.orEmpty().ifBlank { appString(R.string.text_local_library_1c67cd) }, imageUrl(profile.avatar), imageUrl(profile.banner),
    )

    fun imageUrl(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size <= LocalProfileCodec.MAX_IMAGE_BYTES)
            requireNotNull(decodeImage(bytes)).use { require(it.width in 1..1280 && it.height in 1..1280) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val image = File(directory.apply { mkdirs() }, "$hash.img")
            if (!image.isFile) persistBackupFileAtomically(image, bytes)
            desktopFileUri(image.path)
        }.getOrDefault("")
    }
}

@Composable
fun rememberProfileAppearance(session: Session?): ProfileAppearance {
    val container = LocalDesktopContainer.current
    val images = remember(container) { ProfileImages(File(container.paths.cache, "local-profile")) }
    val local by produceState(ProfileAppearance(), container) {
        container.settingsStore.localProfile.collect { profile -> value = withContext(Dispatchers.IO) { images.appearance(profile) } }
    }
    return profileAppearance(session, local)
}
