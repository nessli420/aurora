package com.aurora.music.data.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.aurora.music.AuroraApplication
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/*"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val request = ArtworkUrls.decode(uri.toString()) ?: throw FileNotFoundException("Invalid artwork")
        val app = context!!.applicationContext as AuroraApplication
        return runBlocking { app.container.artworkRepository.open(request) { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) } }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

internal object AndroidArtworkImages : ArtworkImages {
    override fun size(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    override fun isPlaceholder(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!NavidromePlaceholder.sizeEligible(bounds.outWidth, bounds.outHeight)) return false
        val options = BitmapFactory.Options().apply { inSampleSize = NavidromePlaceholder.sampleSize(bounds.outWidth) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        return try { NavidromePlaceholder.matches(bitmap.width, bitmap.height, bitmap::getPixel) } finally { bitmap.recycle() }
    }
}
