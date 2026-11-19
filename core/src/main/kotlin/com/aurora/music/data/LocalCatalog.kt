package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Song

interface LocalCatalog {
    val songs: List<Song>
    val albums: List<Album>
    val artists: List<Artist>
    val folderRoot: String
    suspend fun ensureLoaded()
    fun song(id: String): Song?
    fun findMatch(artist: String, title: String, durationSec: Int): Song?
    fun browse(path: String): Pair<List<String>, List<Song>>
    fun songsByAlbumId(albumId: String): List<Song>
    fun songsByArtistId(artistId: String): List<Song>
    fun albumsByArtistId(artistId: String): List<Album>
    fun artist(id: String): Artist?
}

// aurora's own playlists without the device's files, for a merge that leaves the device out
object NoDeviceFiles : LocalCatalog {
    override val songs: List<Song> = emptyList()
    override val albums: List<Album> = emptyList()
    override val artists: List<Artist> = emptyList()
    override val folderRoot: String = ""
    override suspend fun ensureLoaded() = Unit
    override fun song(id: String): Song? = null
    override fun findMatch(artist: String, title: String, durationSec: Int): Song? = null
    override fun browse(path: String): Pair<List<String>, List<Song>> = emptyList<String>() to emptyList()
    override fun songsByAlbumId(albumId: String): List<Song> = emptyList()
    override fun songsByArtistId(artistId: String): List<Song> = emptyList()
    override fun albumsByArtistId(artistId: String): List<Album> = emptyList()
    override fun artist(id: String): Artist? = null
}
