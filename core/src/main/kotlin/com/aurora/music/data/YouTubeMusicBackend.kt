package com.aurora.music.data

import com.aurora.music.data.remote.*
import com.aurora.music.model.*
import com.aurora.music.util.accentArgbFor
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class YouTubeMusicBackend(
    override val session: Session,
    private val api: YouTubeMusicTransport,
    signatureTimestamp: (String) -> Int? = { null },
    private val localize: (Song) -> Song = { it },
    private val guestLikes: GuestLikes? = null,
    private val videoComments: (videoId: String, page: Any?) -> CommentPage? = { _, _ -> null },
) : MediaBackend {
    private val songs = ConcurrentHashMap<String, Song>()
    private val pageCache = ConcurrentHashMap<String, Pair<Long, SearchResults>>()
    private val pageLocks = Array(16) { Mutex() }
    private val albumLookups = ConcurrentHashMap.newKeySet<String>()
    private val playbackTracker = YouTubeMusicPlaybackTracker(api, signatureTimestamp)
    private fun remember(result: SearchResults): SearchResults = result.also { it.songs.forEach { song -> songs[song.id] = song } }
    // a downloaded or on-device copy plays instead of the stream, as it does for every other source
    private fun parse(root: JsonElement): SearchResults = YouTubeMusicParser.results(root).let { it.copy(songs = it.songs.map(localize)) }
    private fun shelves(root: JsonObject): HomeData = YouTubeMusicParser.home(root).let { home ->
        home.copy(sections = home.sections.map { section ->
            section.copy(items = section.items.map { if (it is HomeFeedItem.Track) HomeFeedItem.Track(localize(it.song)) else it })
        })
    }
    private suspend fun browse(id: String) = api.request("browse", json("browseId" to id))

    override val signedIn: Boolean get() = !session.isGuest
    override fun likesLocally(id: String): Boolean = !signedIn && guestLikes != null && id.matches(VIDEO_ID)
    private val guest: GuestLikes? get() = guestLikes?.takeIf { !signedIn }

    override suspend fun ping(): Boolean = if (signedIn) account(api).first.isNotBlank() else browse("FEmusic_moods_and_genres").size() > 0
    override suspend fun profileImageUrl(): String = session.imageUrl

    // a guest has no library, and youtube answers its library pages with a sign-in prompt
    private suspend fun library(id: String): SearchResults = if (signedIn) pages(id) else SearchResults()

    private suspend fun pages(id: String): SearchResults = pageLocks[(id.hashCode() and Int.MAX_VALUE) % pageLocks.size].withLock {
        pageCache[id]?.takeIf { System.nanoTime() - it.first < 60_000_000_000L }?.let { return@withLock it.second }
        loadPages(id).also {
            pageCache[id] = System.nanoTime() to it
            pageCache.entries.removeIf { entry -> System.nanoTime() - entry.value.first >= 60_000_000_000L }
        }
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
            val result = remember(parse(content))
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
        remember(parse(response))
        return shelves(response)
    }
    override suspend fun allAlbums() = library("FEmusic_liked_albums").albums
    override suspend fun allArtists() = library("FEmusic_library_corpus_track_artists").artists
    override suspend fun allPlaylists() = library("FEmusic_liked_playlists").playlists.filter { it.id != "LM" && it.id != "SE" }
    override suspend fun allSongs() = library("FEmusic_liked_videos").songs.distinctBy { it.id }
    override suspend fun librarySongs(limit: Int) = allSongs().take(limit)
    override suspend fun songsPage(offset: Int, count: Int) = allSongs().drop(offset).take(count)
    override suspend fun starredSongs() = guest?.of(session.type)?.map { localize(it).copy(liked = true) } ?: library("VLLM").songs.distinctBy { it.id }.map { it.copy(liked = true) }
    override suspend fun starredCount() = starredSongs().size
    override suspend fun starredIds(): Set<String> = guest?.ids(session.type) ?: coroutineScope {
        val tracks = async { starredSongs().map { it.id } }
        val albums = async { allAlbums().map { it.id } }
        val artists = async { library("FEmusic_library_corpus_artists").artists.map { it.id } }
        (tracks.await() + albums.await() + artists.await()).toSet()
    }
    override suspend fun likedSongIds(ids: List<String>) = starredSongs().map { it.id }.toSet().intersect(ids.toSet())
    override suspend fun search(query: String): SearchResults = if (query.isBlank()) SearchResults() else
        remember(parse(api.request("search", json("query" to query.trim()))))

    override suspend fun songFor(id: String): Song? {
        val cached = songs[id]
        cached?.takeIf { it.durationSec > 0 && (it.album.isNotBlank() || id in albumLookups) }?.let { return it }
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        albumLookups.add(id)
        val fetched = remember(parse(api.request("next", json("videoId" to id))))
            .songs.firstOrNull { it.id == id } ?: return cached
        // card/home rows carry no album run; the up-next panel does
        val merged = cached?.let { it.copy(album = it.album.ifBlank { fetched.album }, albumId = it.albumId.ifBlank { fetched.albumId },
            durationSec = it.durationSec.takeIf { d -> d > 0 } ?: fetched.durationSec) } ?: fetched
        return merged.also { songs[id] = it }
    }

    override val supportsExplore: Boolean get() = true

    override suspend fun explore(): ExploreData = coroutineScope {
        suspend fun page(id: String) = runCatching { browse(id) }.getOrNull()
        val releases = async { page("FEmusic_new_releases") }
        val charts = async { page("FEmusic_charts") }
        val explore = async { page("FEmusic_explore") }
        val moods = async { page("FEmusic_moods_and_genres") }
        val pages = listOfNotNull(releases.await(), charts.await(), explore.await())
        pages.forEach { remember(parse(it)) }
        val sections = pages.flatMap { shelves(it).sections }.distinctBy { it.id }
        ExploreData(moods.await()?.let(YouTubeMusicParser::categories).orEmpty(), sections)
    }

    override suspend fun exploreCategory(params: String): HomeData =
        homeData(api.request("browse", json("browseId" to "FEmusic_moods_and_genres_category", "params" to params))).copy(continuation = null)

    // songs from elsewhere find their youtube twin first; the watch page then links the related shelves
    override suspend fun related(song: Song): HomeData {
        val id = song.id.takeIf { it.matches(VIDEO_ID) } ?: search("${song.title} ${song.artist}".trim()).songs
            .firstOrNull { song.durationSec <= 0 || kotlin.math.abs(it.durationSec - song.durationSec) <= 3 }?.id ?: return HomeData()
        val page = YouTubeMusicParser.relatedBrowseId(api.request("next", json("videoId" to id))) ?: return HomeData()
        return homeData(browse(page)).copy(continuation = null)
    }

    override suspend fun radio(seedId: String): List<Song> = remember(parse(
        api.request("next", json("videoId" to seedId, "playlistId" to "RDAMVM$seedId"))))
        .songs.distinctBy { it.id }.filter { it.id != seedId }

    override suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "liked") {
            val tracks = starredSongs()
            return DetailData(DetailInfo("Liked Songs", "YouTube Music", tracks.firstOrNull()?.artworkUrl.orEmpty(), accentArgbFor("liked"), false, tracks.size, "Liked"), tracks)
        }
        if (kind !in setOf("album", "artist", "playlist")) return null
        val browseId = if (kind == "playlist") "VL${id.removePrefix("VL")}" else id
        val response = browse(browseId)
        val header = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer")
            .firstNotNullOfOrNull { response.objects(it).firstOrNull() }
        val content = if (kind == "playlist" || kind == "album") YouTubeMusicParser.trackShelf(response) else response
        // Album shelves can substitute one music video for several different recordings.
        val audioPlaylist = if (kind == "album") YouTubeMusicParser.albumPlaylistId(response) else null
        val initial = if (audioPlaylist != null) pages("VL$audioPlaylist") else remember(parse(content))
        val tracks = initial.songs.toMutableList()
        // Generated mixes are changing radio queues, not finite saved playlists.
        var token = if (audioPlaylist != null || (kind == "playlist" && id.removePrefix("VL").startsWith("RD"))) null
            else YouTubeMusicParser.continuation(content)
        val seen = hashSetOf<String>()
        while (token != null && kind != "artist") {
            if (!seen.add(token) || seen.size > 500) throw IOException("YouTube Music returned an incomplete tracklist. Please retry.")
            val page = api.request("browse", json("continuation" to token))
            tracks += remember(parse(page)).songs
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
        val description = if (kind != "playlist") null
            else header?.objects("musicDescriptionShelfRenderer")?.firstOrNull()?.obj("description")?.label().orEmpty()
        return DetailData(DetailInfo(title, header?.obj("subtitle")?.label().orEmpty(), art, accentArgbFor(id), kind == "artist", enriched.size,
            kind.replaceFirstChar { it.uppercase() }, editableDescription = description, description = description.orEmpty(), editable = signedIn), enriched, initial.albums)
    }

    private suspend fun mutation(endpoint: String, body: JsonObject): Boolean {
        if (!signedIn) return false
        val response = api.request(endpoint, body)
        val status = response.string("status")
        if (status.isNotBlank() && status != "STATUS_SUCCEEDED" && status != "SUCCESS")
            throw IOException("YouTube Music did not accept this change.")
        pageCache.clear()
        return true
    }

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = guest?.setStarred(session.type, id, starred, kind, ::songFor)
        ?: if (!signedIn) false else when (kind) {
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
    override suspend fun createPlaylistWithId(name: String): String? = if (!signedIn) null else api.request("playlist/create",
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
        if (!signedIn) return false
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

    override suspend fun scrobble(id: String) {}
    override suspend fun reportPlayback(report: PlaybackReport) { if (signedIn) playbackTracker.report(report) }
    override suspend fun serverLyrics(song: Song): Lyrics? = null
    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = YouTubeMusicParser.sentinel(songId)

    override fun supportsComments(song: Song) = song.id.matches(VIDEO_ID)

    // comment id to its like and unlike tokens, and song id to the token that posts under the video its comments came from
    private val commentActions = ConcurrentHashMap<String, Pair<String, String>>()
    private val postParams = ConcurrentHashMap<String, String>()

    // a track with comments turned off or none yet shows the ones on its music video
    override suspend fun comments(song: Song, source: ServerType, page: Any?): CommentPage? {
        if (source != ServerType.YOUTUBE_MUSIC || !supportsComments(song)) return null
        if (page != null) return commentPage(song, song.id, page)
        val first = commentPage(song, song.id, null)
        if (first != null && first.comments.isNotEmpty()) return first
        val video = search("${recordingTitle(song.title)} ${song.artist}".trim()).songs.firstOrNull {
            it.id != song.id && artistKey(it.artist) == artistKey(song.artist) && recordingKey(it.title).startsWith(recordingKey(song.title))
        } ?: return first
        return commentPage(song, video.id, null) ?: first
    }

    // an account reads its own view of the thread, which carries its likes and the tokens to like and post with
    private suspend fun commentPage(song: Song, videoId: String, page: Any?): CommentPage? {
        // a refused account read falls back to the public one, without likes or posting
        if (signedIn && (page == null || page is String)) try {
            val token = page as? String ?: YouTubeCommentPages.sectionToken(api.youtube("next", json("videoId" to videoId))) ?: return null
            val parsed = YouTubeCommentPages.page(api.youtube("next", json("continuation" to token)))
            // shortcut: tokens pile up for the whole run, fine for a few thousand comments
            commentActions.putAll(parsed.actions)
            if (page == null) parsed.createParams?.let { postParams[song.id] = it }
            return CommentPage(parsed.comments, parsed.next)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            com.aurora.music.util.AppLog.w("YouTube", "account comments failed: ${e.message}")
            if (page != null) return null
        }
        return withContext(Dispatchers.IO) { videoComments(videoId, page) }
    }

    override suspend fun postComment(song: Song, source: ServerType, body: String, timestampMs: Long): TrackComment? {
        if (!canPostComments(song, source)) return null
        val params = postParams[song.id] ?: comments(song, source).let { postParams[song.id] } ?: return null
        val created = YouTubeCommentPages.created(api.youtube("comment/create_comment", json("createCommentParams" to params, "commentText" to body)))
        commentActions.putAll(created.actions)
        return created.comments.firstOrNull() ?: TrackComment("posted-${System.currentTimeMillis()}", body, -1, session.username, session.imageUrl)
    }

    override suspend fun likeComment(song: Song, source: ServerType, commentId: String, liked: Boolean): Boolean {
        if (!canLikeComments(song, source)) return false
        val action = commentActions[commentId]?.let { if (liked) it.first else it.second }?.takeIf { it.isNotBlank() } ?: return false
        val result = api.youtube("comment/perform_comment_action", JsonObject().apply { add("actions", JsonArray().apply { add(action) }) })
        return result.array("actionResults").none { it.isJsonObject && it.asJsonObject.string("status") == "STATUS_FAILED" }
    }

    override val linksSongs: Boolean get() = true
    override suspend fun songLink(song: Song): String? = song.id.takeIf { it.matches(VIDEO_ID) }?.let { "https://music.youtube.com/watch?v=$it" }
    override fun coverArtUrl(id: String, size: Int) = songs[id]?.artworkUrl.orEmpty()

    companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

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
