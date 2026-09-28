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
    fun browse(path: String): Pair<List<String>, List<Song>>
    fun songsByAlbumId(albumId: String): List<Song>
    fun songsByArtistId(artistId: String): List<Song>
    fun albumsByArtistId(artistId: String): List<Album>
    fun artist(id: String): Artist?
}
