package com.aurora.music.data

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

data class AudioTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: String = "",
)

class TagEditor(private val context: Context) {
    private val resolver get() = context.contentResolver

    init {
        runCatching { Logger.getLogger("org.jaudiotagger").level = Level.OFF }
    }

    fun contentUriFor(songId: String): Uri? =
        songId.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }

    suspend fun read(path: String): AudioTags? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(path)
            if (!f.exists()) return@runCatching null
            val tag = AudioFileIO.read(f).tag ?: return@runCatching AudioTags()
            AudioTags(
                title = tag.firstOrEmpty(FieldKey.TITLE),
                artist = tag.firstOrEmpty(FieldKey.ARTIST),
                album = tag.firstOrEmpty(FieldKey.ALBUM),
                albumArtist = tag.firstOrEmpty(FieldKey.ALBUM_ARTIST),
                genre = tag.firstOrEmpty(FieldKey.GENRE),
                year = tag.firstOrEmpty(FieldKey.YEAR),
                trackNumber = tag.firstOrEmpty(FieldKey.TRACK),
            )
        }.getOrNull()
    }

    // android 11+ needs one-time user consent to write a media file the app doesnt own
    fun writeConsentIntent(uri: Uri): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            MediaStore.createWriteRequest(resolver, listOf(uri)).intentSender
        else null

    // jaudiotagger needs a real file so edit a cache copy then stream back through the resolver
    suspend fun write(uri: Uri, path: String, tags: AudioTags, artwork: ByteArray? = null): Boolean =
        withContext(Dispatchers.IO) {
            val ext = path.substringAfterLast('.', "tmp").ifBlank { "tmp" }
            val tmp = File(context.cacheDir, "tagedit_in.$ext")
            try {
                resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: return@withContext false
                val af = AudioFileIO.read(tmp)
                val tag = af.tagOrCreateAndSetDefault
                tag.put(FieldKey.TITLE, tags.title)
                tag.put(FieldKey.ARTIST, tags.artist)
                tag.put(FieldKey.ALBUM, tags.album)
                tag.put(FieldKey.ALBUM_ARTIST, tags.albumArtist)
                tag.put(FieldKey.GENRE, tags.genre)
                tag.put(FieldKey.YEAR, tags.year)
                tag.put(FieldKey.TRACK, tags.trackNumber)
                if (artwork != null && artwork.isNotEmpty()) {
                    runCatching {
                        tag.deleteArtworkField()
                        tag.setField(AndroidArtwork().apply {
                            binaryData = artwork
                            mimeType = "image/jpeg"
                            pictureType = PictureTypes.DEFAULT_ID
                        })
                    }
                }
                af.commit()
                resolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    ?: return@withContext false
                runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
                true
            } catch (t: Throwable) {
                android.util.Log.e("TagEditor", "write($path) failed", t)
                false
            } finally {
                runCatching { tmp.delete() }
            }
        }

    private fun Tag.firstOrEmpty(key: FieldKey): String = runCatching { getFirst(key) ?: "" }.getOrDefault("")

    // blank value deletes the field so clearing actually clears the tag
    private fun Tag.put(key: FieldKey, value: String) {
        runCatching {
            if (value.isBlank()) deleteField(key) else setField(key, value)
        }
    }
}
