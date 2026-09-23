package com.aurora.music.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.artwork.ArtworkUrls
import com.aurora.music.model.Song
import com.google.common.collect.ImmutableList
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
internal class PlaybackLibraryBrowser(
    private val context: Context,
    private val repository: MusicRepository,
) {
    private val browseCache = ConcurrentHashMap<String, MediaItem>()
    private val searchCache = ConcurrentHashMap<String, List<MediaItem>>()

    fun root(): MediaItem = browseItem(
        LIBRARY_ROOT, "Aurora", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, styleExtras(styleList, styleList),
    )

    suspend fun children(parentId: String): ImmutableList<MediaItem> {
        val items: List<MediaItem> = runCatching {
            when {
                parentId == LIBRARY_ROOT -> listOf(
                    browseItem("cat_liked", "Liked Songs", MediaMetadata.MEDIA_TYPE_PLAYLIST, styleExtras(styleList, styleList)),
                    browseItem("cat_playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_downloads", "Downloads", MediaMetadata.MEDIA_TYPE_PLAYLIST, styleExtras(styleList, styleList)),
                )
                parentId == "cat_liked" -> repository.starredSongs().map { songItem(it) }
                parentId == "cat_downloads" -> repository.downloadedSongs().map { songItem(it) }
                parentId == "cat_playlists" -> repository.allPlaylists().map { collectionItem("pl_${it.id}", it.title, it.subtitle, it.coverUrl, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
                parentId == "cat_albums" -> repository.allAlbums().map { collectionItem("alb_${it.id}", it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM) }
                parentId == "cat_artists" -> repository.allArtists().map { collectionItem("art_${it.id}", it.name, "Artist", it.imageUrl, MediaMetadata.MEDIA_TYPE_ARTIST) }
                parentId.startsWith("alb_") -> repository.detail("album", parentId.removePrefix("alb_"))?.tracks?.map { songItem(it) }.orEmpty()
                parentId.startsWith("pl_") -> repository.detail("playlist", parentId.removePrefix("pl_"))?.tracks?.map { songItem(it) }.orEmpty()
                parentId.startsWith("art_") -> repository.detail("artist", parentId.removePrefix("art_"))?.tracks?.map { songItem(it) }.orEmpty()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        return ImmutableList.copyOf(items)
    }

    suspend fun resolvePlayable(mediaId: String): MediaItem? {
        browseCache[mediaId]?.let { return it }
        val id = mediaId.removePrefix("song_")
        return runCatching { repository.songFor(id)?.let { songItem(it) } }.getOrNull()
    }

    suspend fun search(query: String): List<MediaItem> =
        runCatching { searchItems(query) }.getOrDefault(emptyList()).also { searchCache[query] = it }

    suspend fun searchResult(query: String): ImmutableList<MediaItem> = ImmutableList.copyOf(
        searchCache[query] ?: search(query),
    )

    fun grantArtwork(browser: MediaSession.ControllerInfo, items: List<MediaItem>) {
        items.mapNotNull { it.mediaMetadata.artworkUri }.distinct().forEach { uri ->
            if (ArtworkUrls.isArtwork(uri.toString())) runCatching {
                context.grantUriPermission(browser.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun songItem(song: Song): MediaItem {
        val id = "song_${song.id}"
        val item = MediaItem.Builder().setMediaId(id).setUri(song.streamUrl).setMediaMetadata(
            MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                .setExtras(PresetContextPublisher.extras(song, repository.playbackSourceIdentity(song)))
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (song.artworkUrl.isNotBlank()) setArtworkUri(Uri.parse(song.artworkUrl)) }.build()
        ).build()
        browseCache[id] = item
        return item
    }

    private suspend fun searchItems(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val results = repository.search(query)
        val songs = results.songs.map { songItem(it) }
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
        ).build().also { browseCache[id] = it }

    private fun styleExtras(browsable: Int, playable: Int) = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
    }

    private val styleGrid get() = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    private val styleList get() = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

    private companion object {
        const val LIBRARY_ROOT = "root"
    }
}
