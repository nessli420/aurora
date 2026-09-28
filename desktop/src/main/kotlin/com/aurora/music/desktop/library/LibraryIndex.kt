package com.aurora.music.desktop.library

import com.aurora.music.data.persistBackupFileAtomically
import com.google.gson.Gson
import java.io.File

internal data class IndexedTrack(
    val path: String = "",
    val size: Long = 0,
    val modifiedMs: Long = 0,
    val addedSec: Long = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int = 0,
    val disc: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val durationMs: Long = 0,
    val codec: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitDepth: Int = 0,
    val bitrate: Long = 0,
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val cover: String = "",
)

internal data class IndexSnapshot(
    val version: Int = 0,
    val folders: List<String> = emptyList(),
    val tracks: List<IndexedTrack> = emptyList(),
)

internal class LibraryIndex(private val file: File) {
    private val gson = Gson()

    fun read(): IndexSnapshot? = runCatching { gson.fromJson(file.readText(), IndexSnapshot::class.java) }.getOrNull()
        ?.takeIf { it.version == VERSION }

    fun write(folders: List<String>, tracks: List<IndexedTrack>) {
        file.parentFile?.mkdirs()
        persistBackupFileAtomically(file, gson.toJson(IndexSnapshot(VERSION, folders, tracks)).toByteArray())
    }

    companion object {
        const val VERSION = 1
    }
}
