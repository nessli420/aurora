package com.aurora.music.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.Session
import com.aurora.music.data.artwork.ArtworkUrls
import com.aurora.music.model.Song
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val MAX_PAGE = 500

// pages are capped at MAX_PAGE so a bigger page size can't skip the rest of a page
internal fun <T> List<T>.browsePage(page: Int, pageSize: Int): List<T> {
    val size = pageSize.coerceIn(0, MAX_PAGE)
    return drop((page.toLong() * size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(size)
}

@UnstableApi
internal class PlaybackLibraryBrowser(
    private val context: Context,
    private val repository: MusicRepository,
) {
    private val browseCache = ConcurrentHashMap<String, MediaItem>()
    private val searchCache = ConcurrentHashMap<String, List<MediaItem>>()
    // newest last, so a tapped song can queue the list it was picked from
    private val listings = ArrayList<Pair<String, List<Song>>>()
    @Volatile private var lastSession: Session? = null

    fun root(): MediaItem = browseItem(
        LIBRARY_ROOT, "Aurora", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, styleExtras(styleList, styleList),
    )

    suspend fun children(parentId: String, page: Int, pageSize: Int): ImmutableList<MediaItem> = withContext(Dispatchers.Default) {
        forgetIfSwitched()
        val items: List<MediaItem> = runCatching {
            when {
                parentId == LIBRARY_ROOT -> listOf(
                    browseItem("cat_liked", "Liked Songs", MediaMetadata.MEDIA_TYPE_PLAYLIST, styleExtras(styleList, styleList)),
                    browseItem("cat_playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_downloads", "Downloads", MediaMetadata.MEDIA_TYPE_PLAYLIST, styleExtras(styleList, styleList)),
                ).browsePage(page, pageSize)
                parentId == "cat_liked" -> listed(parentId, repository.starredSongs().browsePage(page, pageSize))
                parentId == "cat_downloads" -> listed(parentId, repository.downloadedSongs().browsePage(page, pageSize))
                parentId == "cat_playlists" -> repository.allPlaylists().browsePage(page, pageSize).map { collectionItem("pl_${it.id}", it.title, it.subtitle, it.coverUrl, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
                parentId == "cat_albums" -> repository.allAlbums().browsePage(page, pageSize).map { collectionItem("alb_${it.id}", it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM) }
                parentId == "cat_artists" -> repository.allArtists().browsePage(page, pageSize).map { collectionItem("art_${it.id}", it.name, "Artist", it.imageUrl, MediaMetadata.MEDIA_TYPE_ARTIST) }
                parentId.startsWith("alb_") -> listed(parentId, repository.detail("album", parentId.removePrefix("alb_"))?.tracks.orEmpty().browsePage(page, pageSize))
                parentId.startsWith("pl_") -> listed(parentId, repository.detail("playlist", parentId.removePrefix("pl_"))?.tracks.orEmpty().browsePage(page, pageSize))
                parentId.startsWith("art_") -> listed(parentId, repository.detail("artist", parentId.removePrefix("art_"))?.tracks.orEmpty().browsePage(page, pageSize))
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        ImmutableList.copyOf(items)
    }

    suspend fun resolvePlayable(mediaId: String): MediaItem? {
        forgetIfSwitched()
        browseCache[mediaId]?.let { return it }
        val id = mediaId.removePrefix(SONG_PREFIX)
        listingWith(mediaId)?.firstOrNull { it.id == id }?.let { return songItem(it) }
        return withContext(Dispatchers.Default) { runCatching { repository.songFor(id)?.let { songItem(it) } }.getOrNull() }
    }

    fun listingWith(mediaId: String): List<Song>? {
        if (!mediaId.startsWith(SONG_PREFIX)) return null
        forgetIfSwitched()
        val id = mediaId.removePrefix(SONG_PREFIX)
        return synchronized(listings) { listings.lastOrNull { (_, songs) -> songs.any { it.id == id } }?.second }
    }

    suspend fun queue(songs: List<Song>, mediaId: String): MediaSession.MediaItemsWithStartPosition = withContext(Dispatchers.Default) {
        val id = mediaId.removePrefix(SONG_PREFIX)
        MediaSession.MediaItemsWithStartPosition(songs.map { songItem(it) }, songs.indexOfFirst { it.id == id }.coerceAtLeast(0), C.TIME_UNSET)
    }

    suspend fun searchQueue(query: String): List<MediaItem> = withContext(Dispatchers.Default) {
        runCatching { repository.search(query).songs.take(MAX_PAGE).map { songItem(it) } }.getOrDefault(emptyList())
    }

    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.Default) {
        forgetIfSwitched()
        runCatching { searchItems(query) }.getOrDefault(emptyList()).also { searchCache[query] = it }
    }

    suspend fun searchResult(query: String, page: Int, pageSize: Int): ImmutableList<MediaItem> = ImmutableList.copyOf(
        (searchCache[query] ?: search(query)).browsePage(page, pageSize),
    )

    suspend fun grantArtwork(browser: MediaSession.ControllerInfo, items: List<MediaItem>) = withContext(Dispatchers.IO) {
        items.mapNotNull { it.mediaMetadata.artworkUri }.distinct().forEach { uri ->
            if (ArtworkUrls.isArtwork(uri.toString())) runCatching {
                context.grantUriPermission(browser.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun songItem(song: Song): MediaItem = MediaItem.Builder().setMediaId(SONG_PREFIX + song.id).setUri(song.streamUrl)
        .setMediaMetadata(songMetadata(song).setExtras(PresetContextPublisher.extras(song, repository.playbackSourceIdentity(song))).build())
        .build()

    // rows carry no uri or rule extras; resolvePlayable builds the full item
    private fun listItem(song: Song): MediaItem =
        MediaItem.Builder().setMediaId(SONG_PREFIX + song.id).setMediaMetadata(songMetadata(song).build()).build()

    private fun songMetadata(song: Song) = MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
        .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .apply { if (song.artworkUrl.isNotBlank()) setArtworkUri(Uri.parse(song.artworkUrl)) }

    private fun listed(key: String, songs: List<Song>): List<MediaItem> {
        synchronized(listings) {
            listings.removeAll { it.first == key }
            listings += key to songs
            if (listings.size > MAX_LISTINGS) listings.removeAt(0)
        }
        return songs.map { listItem(it) }
    }

    // a stale entry would play the previous account's url
    private fun forgetIfSwitched() {
        val session = repository.activeSession
        if (session === lastSession) return
        lastSession = session
        browseCache.clear()
        searchCache.clear()
        synchronized(listings) { listings.clear() }
    }

    private suspend fun searchItems(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val results = repository.search(query)
        val songs = listed("search:$query", results.songs)
        val albums = results.albums.map { collectionItem("alb_${it.id}", it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM) }
        val artists = results.artists.map { collectionItem("art_${it.id}", it.name, "Artist", it.imageUrl, MediaMetadata.MEDIA_TYPE_ARTIST) }
        return songs + albums + artists
    }

    private fun browseItem(id: String, title: String, mediaType: Int, childExtras: Bundle? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(mediaType)
                .apply { if (childExtras != null) setExtras(childExtras) }.build()
        ).build()

    private fun collectionItem(id: String, title: String, subtitle: String, art: String, mediaType: Int): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtist(subtitle)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(mediaType)
                .setExtras(styleExtras(styleList, styleList))
                .apply { if (art.isNotBlank()) setArtworkUri(Uri.parse(art)) }.build()
        ).build().also {
            if (browseCache.size >= MAX_CACHED) browseCache.clear()
            browseCache[id] = it
        }

    private fun styleExtras(browsable: Int, playable: Int) = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
    }

    private val styleGrid get() = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    private val styleList get() = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

    private companion object {
        const val LIBRARY_ROOT = "root"
        const val SONG_PREFIX = "song_"
        const val MAX_LISTINGS = 8
        const val MAX_CACHED = 2_000
    }
}
