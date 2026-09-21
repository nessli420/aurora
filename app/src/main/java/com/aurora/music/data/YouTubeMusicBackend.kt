package com.aurora.music.data

import com.aurora.music.data.remote.*
import com.aurora.music.model.*
import com.aurora.music.util.accentFor
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class YouTubeMusicBackend(override val session: Session, private val api: YouTubeMusicTransport) : MediaBackend {
    private val songs = ConcurrentHashMap<String, Song>()
    private val pageCache = ConcurrentHashMap<String, Pair<Long, SearchResults>>()
    private val pageLock = Mutex()
    private fun remember(result: SearchResults): SearchResults = result.also { it.songs.forEach { song -> songs[song.id] = song } }
    private suspend fun browse(id: String) = api.request("browse", json("browseId" to id))

    override suspend fun ping(): Boolean = account(api).first.isNotBlank()
    override suspend fun profileImageUrl(): String = session.imageUrl

    private suspend fun pages(id: String): SearchResults = pageLock.withLock {
        pageCache[id]?.takeIf { System.nanoTime() - it.first < 60_000_000_000L }?.let { return@withLock it.second }
        loadPages(id).also { pageCache[id] = System.nanoTime() to it }
    }

    private suspend fun loadPages(id: String): SearchResults {
        var response = browse(id)
        val tracks = mutableListOf<Song>()
        val albums = mutableListOf<Album>()
        val artists = mutableListOf<Artist>()
        val playlists = mutableListOf<Playlist>()
        val seen = hashSetOf<String>()
        while (true) {
            val content = if (id.startsWith("VL")) YouTubeMusicParser.trackShelf(response) else response
            val result = remember(YouTubeMusicParser.results(content))
            tracks += result.songs; albums += result.albums; artists += result.artists; playlists += result.playlists
            val token = YouTubeMusicParser.continuation(content) ?: break
            if (!seen.add(token) || seen.size > 500) throw IOException("YouTube Music returned an incomplete library. Please retry.")
            response = api.request("browse", json("continuation" to token))
        }
        return SearchResults(tracks, albums.distinctBy { it.id }, artists.distinctBy { it.id }, playlists.distinctBy { it.id })
    }

    override suspend fun home(): HomeData = coroutineScope {
        homeData(browse("FEmusic_home"))
    }
    override suspend fun homePage(continuation: String): HomeData = homeData(api.request("browse", json("continuation" to continuation)))
    private fun homeData(response: JsonObject): HomeData {
        remember(YouTubeMusicParser.results(response))
        return YouTubeMusicParser.home(response)
    }
    override suspend fun allAlbums() = pages("FEmusic_liked_albums").albums
    override suspend fun allArtists() = pages("FEmusic_library_corpus_track_artists").artists
    override suspend fun allPlaylists() = pages("FEmusic_liked_playlists").playlists.filter { it.id != "LM" && it.id != "SE" }
    override suspend fun allSongs() = pages("FEmusic_liked_videos").songs.distinctBy { it.id }
    override suspend fun librarySongs(limit: Int) = allSongs().take(limit)
    override suspend fun songsPage(offset: Int, count: Int) = allSongs().drop(offset).take(count)
    override suspend fun starredSongs() = pages("VLLM").songs.distinctBy { it.id }.map { it.copy(liked = true) }
    override suspend fun starredCount() = starredSongs().size
    override suspend fun starredIds(): Set<String> = coroutineScope {
        val tracks = async { starredSongs().map { it.id } }
        val albums = async { allAlbums().map { it.id } }
        val artists = async { pages("FEmusic_library_corpus_artists").artists.map { it.id } }
        (tracks.await() + albums.await() + artists.await()).toSet()
    }
    override suspend fun likedSongIds(ids: List<String>) = starredSongs().map { it.id }.toSet().intersect(ids.toSet())
    override suspend fun search(query: String): SearchResults = if (query.isBlank()) SearchResults() else
        remember(YouTubeMusicParser.results(api.request("search", json("query" to query.trim()))))

    override suspend fun songFor(id: String): Song? {
        songs[id]?.takeIf { it.durationSec > 0 }?.let { return it }
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        return remember(YouTubeMusicParser.results(api.request("next", json("videoId" to id))))
            .songs.firstOrNull { it.id == id }
    }

    override suspend fun radio(seedId: String): List<Song> = remember(YouTubeMusicParser.results(
        api.request("next", json("videoId" to seedId, "playlistId" to "RDAMVM$seedId"))))
        .songs.distinctBy { it.id }.filter { it.id != seedId }

    override suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "liked") {
            val tracks = starredSongs()
            return DetailData(DetailInfo("Liked Songs", "YouTube Music", tracks.firstOrNull()?.artworkUrl.orEmpty(), accentFor("liked"), false, tracks.size, "Liked"), tracks)
        }
        if (kind !in setOf("album", "artist", "playlist")) return null
        val browseId = if (kind == "playlist") "VL${id.removePrefix("VL")}" else id
        val response = browse(browseId)
        val header = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer")
            .firstNotNullOfOrNull { response.objects(it).firstOrNull() }
        val content = if (kind == "playlist" || kind == "album") YouTubeMusicParser.trackShelf(response) else response
        // Album shelves can substitute one music video for several different recordings.
        val audioPlaylist = if (kind == "album") YouTubeMusicParser.albumPlaylistId(response) else null
        val initial = if (audioPlaylist != null) pages("VL$audioPlaylist") else remember(YouTubeMusicParser.results(content))
        val tracks = initial.songs.toMutableList()
        // Generated mixes are changing radio queues, not finite saved playlists.
        var token = if (audioPlaylist != null || (kind == "playlist" && id.removePrefix("VL").startsWith("RD"))) null
            else YouTubeMusicParser.continuation(content)
        val seen = hashSetOf<String>()
        while (token != null && kind != "artist") {
            if (!seen.add(token) || seen.size > 500) throw IOException("YouTube Music returned an incomplete tracklist. Please retry.")
            val page = api.request("browse", json("continuation" to token))
            tracks += remember(YouTubeMusicParser.results(page)).songs
            token = YouTubeMusicParser.continuation(page)
        }
        val title = header?.obj("title")?.label().orEmpty().ifBlank { kind.replaceFirstChar { it.uppercase() } }
        val art = header?.let { YouTubeMusicParser.artwork(it) }.orEmpty().ifBlank { tracks.firstOrNull()?.artworkUrl.orEmpty() }
        val enriched = tracks.map { song ->
            song.copy(album = if (kind == "album") title else song.album, albumId = if (kind == "album") id else song.albumId,
                artist = song.artist.ifBlank { header?.obj("straplineTextOne")?.label().orEmpty() },
                artistId = song.artistId.ifBlank { header?.obj("straplineTextOne")?.objects("browseEndpoint")?.firstOrNull()?.string("browseId").orEmpty() },
                artworkUrl = song.artworkUrl.ifBlank { art }).also { songs[it.id] = it }
        }
        return DetailData(DetailInfo(title, header?.obj("subtitle")?.label().orEmpty(), art, accentFor(id), kind == "artist", enriched.size,
            kind.replaceFirstChar { it.uppercase() }), enriched, initial.albums)
    }

    private suspend fun mutation(endpoint: String, body: JsonObject): Boolean {
        val response = api.request(endpoint, body)
        val status = response.string("status")
        if (status.isNotBlank() && status != "STATUS_SUCCEEDED" && status != "SUCCESS")
            throw IOException("YouTube Music did not accept this change.")
        pageCache.clear()
        return true
    }

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = when (kind) {
        "artist" -> mutation(if (starred) "subscription/subscribe" else "subscription/unsubscribe", JsonObject().apply {
            add("channelIds", JsonArray().apply { add(id) })
        })
        "album" -> {
            val album = browse(id)
            val playlistId = album.objects("watchPlaylistEndpoint").firstOrNull()?.string("playlistId")
                ?.takeIf { it.isNotBlank() } ?: album.objects("watchEndpoint").firstOrNull()?.string("playlistId")
            if (playlistId.isNullOrBlank()) false else rate(playlistId, starred, true)
        }
        "playlist" -> rate(id, starred, true)
        else -> rate(id, starred, false)
    }
    private suspend fun rate(id: String, starred: Boolean, playlist: Boolean) = mutation(
        if (starred) "like/like" else "like/removelike", JsonObject().apply { add("target", json((if (playlist) "playlistId" else "videoId") to id)) })

    override suspend fun createPlaylist(name: String) = createPlaylistWithId(name) != null
    override suspend fun createPlaylistWithId(name: String): String? = api.request("playlist/create",
        json("title" to name, "description" to "", "privacyStatus" to "PRIVATE")).string("playlistId").takeIf { it.isNotBlank() }?.also { pageCache.clear() }
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean {
        val actions = JsonArray().apply {
            name?.let { add(json("action" to "ACTION_SET_PLAYLIST_NAME", "playlistName" to it)) }
            comment?.let { add(json("action" to "ACTION_SET_PLAYLIST_DESCRIPTION", "playlistDescription" to it)) }
        }
        if (actions.size() == 0) return true
        return mutation("browse/edit_playlist", json("playlistId" to id).apply { add("actions", actions) })
    }
    override suspend fun deletePlaylist(id: String) = mutation("playlist/delete", json("playlistId" to id))
    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        if (trackIds.isEmpty()) return true
        return mutation("browse/edit_playlist", json("playlistId" to playlistId).apply {
            add("actions", JsonArray().apply { trackIds.forEach { add(json("action" to "ACTION_ADD_VIDEO", "addedVideoId" to it)) } })
        })
    }
    override suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        var response = browse("VL${playlistId.removePrefix("VL")}")
        val entries = linkedMapOf<String, String>()
        val seen = hashSetOf<String>()
        while (true) {
            val content = YouTubeMusicParser.trackShelf(response)
            content.objects("playlistItemData").filter { it.string("videoId") in trackIds }.forEach {
                val entry = it.string("playlistSetVideoId")
                check(entry.isNotBlank()) { "This playlist cannot be edited." }
                entries[entry] = it.string("videoId")
            }
            val token = YouTubeMusicParser.continuation(content) ?: break
            check(seen.add(token) && seen.size <= 500) { "Incomplete playlist" }
            response = api.request("browse", json("continuation" to token))
        }
        // Missing entry IDs must not be reported as a successful removal.
        if (entries.isEmpty()) return false
        return mutation("browse/edit_playlist", json("playlistId" to playlistId.removePrefix("VL")).apply {
            add("actions", JsonArray().apply { entries.forEach { (entry, video) ->
                add(json("action" to "ACTION_REMOVE_VIDEO", "setVideoId" to entry, "removedVideoId" to video))
            } })
        })
    }

    override suspend fun scrobble(id: String) { /* Listening history is maintained locally. */ }
    override suspend fun serverLyrics(song: Song): Lyrics? = null
    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = YouTubeMusicParser.sentinel(songId)
    override fun coverArtUrl(id: String, size: Int) = songs[id]?.artworkUrl.orEmpty()

    companion object {
        suspend fun account(api: YouTubeMusicTransport, profileId: String = ""): Triple<String, String, String> {
            val response = api.request("account/account_menu", JsonObject())
            val header = response.objects("activeAccountHeaderRenderer").firstOrNull()
                ?: throw IOException("Sign in to YouTube Music, then try connecting again.")
            val name = header.obj("accountName").label()
            val handle = profileId.ifBlank { header.obj("channelHandle").label() }
                .ifBlank { header.obj("email").label() }
            if (name.isBlank() || handle.isBlank()) throw IOException("Choose a YouTube Music profile before connecting.")
            return Triple(name, handle, YouTubeMusicParser.thumbnail(header.obj("accountPhoto")))
        }
    }
}
