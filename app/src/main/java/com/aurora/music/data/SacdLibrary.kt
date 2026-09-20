package com.aurora.music.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aurora.music.model.Song
import com.aurora.music.playback.sacd.AndroidSacdInput
import com.aurora.music.playback.sacd.SacdDataSource
import com.aurora.music.playback.sacd.SacdImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.UUID

data class SacdLibraryEntry(val uri: String, val name: String, val error: String? = null)

class SacdLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("sacd_images", Context.MODE_PRIVATE)

    suspend fun add(uri: Uri): Int = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Select a local SACD image." }
        val disc = AndroidSacdInput(context, uri).use { SacdImage(it).disc }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            val images = saved().toMutableSet()
            require(images.size < 256 || uri.toString() in images) { "The SACD image limit is 256." }
            images += uri.toString()
            check(prefs.edit().putStringSet("images", images).commit()) { "SACD image could not be saved." }
        }
        disc.tracks.size
    }

    suspend fun remove(uri: String) = withContext(Dispatchers.IO) {
        synchronized(lock) { check(prefs.edit().putStringSet("images", saved() - uri).commit()) }
    }

    suspend fun entries(): List<SacdLibraryEntry> = withContext(Dispatchers.IO) {
        saved().sorted().map { value ->
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(value)
            val name = name(uri)
            try { AndroidSacdInput(context, uri).use { SacdImage(it) }; SacdLibraryEntry(value, name) }
            catch (failure: Exception) { SacdLibraryEntry(value, name, failure.message ?: "Image unavailable. Import it again.") }
        }
    }

    suspend fun songs(): List<Song> = withContext(Dispatchers.IO) {
        saved().sorted().flatMap { value ->
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(value)
            val disc = try { AndroidSacdInput(context, uri).use { SacdImage(it).disc } } catch (_: Exception) { null }
            if (disc == null) emptyList() else {
                val id = "local-sacd-" + UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))
                val album = disc.album.ifBlank { name(uri).substringBeforeLast('.') }
                disc.tracks.map { track -> Song(id = "$id-${track.number}", title = track.title,
                    artist = track.artist.ifBlank { "Unknown artist" }, album = album, albumId = id,
                    artworkUrl = "", durationSec = (track.frames + 74) / 75,
                    streamUrl = SacdDataSource.trackUri(uri, track.number).toString(),
                    suffix = "iso", sampleRateHz = 2822400, bitDepth = 1, bitrateKbps = 5644) }
            }
        }
    }

    private fun saved() = prefs.getStringSet("images", emptySet()).orEmpty().toSet()
    private fun name(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "SACD image"

    companion object { private val lock = Any() }
}
