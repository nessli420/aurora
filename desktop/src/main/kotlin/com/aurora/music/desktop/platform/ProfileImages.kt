package com.aurora.music.desktop.platform

import com.aurora.music.data.LocalProfile
import com.aurora.music.data.LocalProfileCodec
import com.aurora.music.data.ProfileAppearance
import com.aurora.music.data.persistBackupFileAtomically
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class ProfileImages(private val cacheDir: File) {
    fun appearance(profile: LocalProfile): ProfileAppearance = ProfileAppearance(
        profile.name.orEmpty().ifBlank { "Local Library" }, imageUrl(profile.avatar), imageUrl(profile.banner),
    )

    fun imageUrl(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size <= LocalProfileCodec.MAX_IMAGE_BYTES)
            val (width, height) = requireNotNull(SkiaArtworkImages.size(bytes))
            require(width in 1..1280 && height in 1..1280)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val image = File(File(cacheDir, "local-profile").apply { mkdirs() }, "$hash.img")
            if (!image.isFile) persistBackupFileAtomically(image, bytes)
            desktopFileUri(image.path)
        }.getOrDefault("")
    }
}
