package com.aurora.extension.example

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.PI
import kotlin.math.sin

class ExampleAudioProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "audio/wav"
    @Synchronized override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = requireNotNull(context)
        if (mode != "r" || uri.path != "/tone.wav" ||
            app.packageManager.getPackagesForUid(Binder.getCallingUid())?.contains("com.aurora.music") != true)
            throw FileNotFoundException("Access denied.")
        val file = File(app.filesDir, "tone.wav")
        if (!file.exists()) file.outputStream().buffered().use { output ->
            val frames = 48_000 * 12
            fun le(value: Int, bytes: Int) { repeat(bytes) { output.write(value ushr (it * 8) and 255) } }
            fun ascii(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
            ascii("RIFF"); le(36 + frames * 4, 4); ascii("WAVEfmt "); le(16, 4); le(1, 2); le(2, 2)
            le(48_000, 4); le(192_000, 4); le(4, 2); le(16, 2); ascii("data"); le(frames * 4, 4)
            repeat(frames) { frame -> val sample = (sin(2 * PI * 440 * frame / 48_000) * 2048).toInt(); le(sample, 2); le(sample, 2) }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
