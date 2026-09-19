package com.aurora.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class ProfileImages(private val context: Context) {
    suspend fun import(uri: Uri, banner: Boolean): String = withContext(Dispatchers.IO) {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open this image." }.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 20 * 1024 * 1024) { "Choose an image smaller than 20 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) {
            "Choose a supported image under 100 megapixels."
        }
        val edge = if (banner) 1280 else 512
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge * 2) sample *= 2
        var bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })) { "Cannot read this image." }
        try {
            val orientation = runCatching { bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f); postScale(-1f, 1f) }
                    8 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            var target = edge
            while (target >= 64) {
                val scale = target.toDouble() / maxOf(bitmap.width, bitmap.height)
                if (scale < 1) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                    if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
                }
                val output = ByteArrayOutputStream()
                check(bitmap.compress(if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, output))
                if (output.size() <= LocalProfileCodec.MAX_IMAGE_BYTES) return@withContext Base64.getEncoder().encodeToString(output.toByteArray())
                target = (target * .75).toInt()
            }
            error("Cannot resize this image.")
        } finally { bitmap.recycle() }
    }

    fun appearance(profile: LocalProfile): ProfileAppearance = ProfileAppearance(
        profile.name.orEmpty().ifBlank { "Local Library" }, imageUrl(profile.avatar), imageUrl(profile.banner),
    )

    fun imageUrl(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size <= LocalProfileCodec.MAX_IMAGE_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..1280 && bounds.outHeight in 1..1280)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val directory = File(context.cacheDir, "local-profile").apply { mkdirs() }
            val image = File(directory, "$hash.img")
            if (!image.isFile) persistBackupFileAtomically(image, bytes)
            image.toURI().toString()
        }.getOrDefault("")
    }
}
