package com.aurora.music.data

import com.aurora.music.data.remote.SpotifyGraphql
import com.aurora.music.data.remote.SpotifyGraphqlException
import com.aurora.music.data.remote.array
import com.aurora.music.data.remote.obj
import com.aurora.music.data.remote.string
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import com.aurora.music.util.accentArgbFor
import com.aurora.music.util.parseReleaseDateEpochSec
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

data class SpotifyWebProfile(val userId: String, val name: String, val imageUrl: String)

// spotify through the web player's own api, so no developer app or premium; audio resolves through youtube
class SpotifyWebBackend(
    override val session: Session,
    private val gql: SpotifyGraphql,
    private val localize: (Song) -> Song,
) : MediaBackend {

    private data class AlbumRef(val id: String, val name: String, val art: String)
    private class Page(val songs: List<Song>, val consumed: Int, val total: Int)

    @Volatile private var likedCache: List<Song>? = null
    @Volatile private var likedTotal = -1
    // album track pages carry no album so the header's name and art fill them in
    private val albumRefs = ConcurrentHashMap<String, AlbumRef>()
    // skipped podcast and local items make the app's song offsets drift from the server's item offsets
    private val cursors = ConcurrentHashMap<String, Int>()
    // playlist id to (revision, track count); a playlist's revision changes whenever its tracks do
    private val playlistCounts = ConcurrentHashMap<String, Pair<String, Int>>()

    private suspend fun q(operation: String, vararg variables: Pair<String, Any?>): JsonObject =
        gql.query(operation, variables(*variables))

    override suspend fun ping(): Boolean = runCatching { profile(gql) != null }.getOrDefault(false)

    override suspend fun home(): HomeData {
        val home = q("home", "homeEndUserIntegration" to "INTEGRATION_WEB_PLAYER", "timeZone" to TimeZone.getDefault().id,
            "sp_t" to "", "facet" to "", "sectionItemsLimit" to 12, "includeHomeChapterVideoCards" to false,
            "includeEpisodeContentRatingsV2" to false).obj("home")
        val sections = home.obj("sectionContainer").obj("sections").list("items").mapIndexedNotNull { index, section ->
            val data = section.obj("data")
            val title = data.obj("title").label()
                .ifBlank { if (data.string("__typename") == "HomeShortsSectionData") "Recently played" else "" }
            val items = section.obj("sectionItems").list("items")
                .mapNotNull { feedItem(it.obj("content").obj("data"), it.string("uri")) }.distinctBy { it.key }
            if (title.isBlank() || items.isEmpty()) null
            else HomeFeedSection(section.string("uri").ifBlank { "section-$index" }, title, data.obj("subtitle").label(), items)
        }
        return HomeData(sections = sections)
    }

    private fun feedItem(data: JsonObject, uri: String): HomeFeedItem? = when (data.string("__typename")) {
        "Album" -> album(data, uri)?.let { HomeFeedItem.Record(it) }
        "Playlist" -> playlist(data, uri)?.let { HomeFeedItem.Collection(it) }
        "Artist" -> artist(data, uri)?.let { HomeFeedItem.Performer(it) }
        else -> null
    }

    private suspend fun library(filter: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        while (out.size < LIBRARY_CAP) {
            val page = q("libraryV3", "filters" to listOf(filter), "order" to null, "textFilter" to "",
                "features" to listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS"),
                "limit" to LIBRARY_PAGE, "offset" to out.size, "flatten" to (filter == "Playlists"),
                "expandedFolders" to emptyList<String>(), "folderUri" to null, "includeFoldersWhenFlattening" to false,
            ).obj("me").obj("libraryV3")
            val items = page.list("items")
            out += items.map { it.obj("item") }
            if (items.isEmpty() || out.size >= page.int("totalCount")) break
        }
        return out
    }

    override suspend fun allAlbums(): List<Album> =
        runCatching { library("Albums").mapNotNull { album(it.obj("data"), it.string("_uri")) } }
            .onFailure { AppLog.d(TAG, "library albums failed: ${it.message}") }.getOrDefault(emptyList())

    override suspend fun allArtists(): List<Artist> =
        runCatching { library("Artists").mapNotNull { artist(it.obj("data"), it.string("_uri")) } }
            .onFailure { AppLog.d(TAG, "library artists failed: ${it.message}") }.getOrDefault(emptyList())

    override suspend fun allPlaylists(): List<Playlist> = runCatching {
        withCounts(library("Playlists").mapNotNull { item ->
            playlist(item.obj("data"), item.string("_uri"))?.let { it to item.obj("data").string("revisionId") }
        })
    }.onFailure { AppLog.d(TAG, "library playlists failed: ${it.message}") }.getOrDefault(emptyList())

    // the library listing carries no track counts, so each playlist's header is read once per revision
    private suspend fun withCounts(entries: List<Pair<Playlist, String>>): List<Playlist> {
        val gate = Semaphore(COUNT_PARALLEL)
        // rows don't wait for a slow header: counts that land stay for the next open
        withTimeoutOrNull(COUNT_BUDGET_MS) {
            coroutineScope {
                entries.filter { (playlist, revision) -> playlistCounts[playlist.id]?.first != revision }.forEach { (playlist, revision) ->
                    launch {
                        gate.withPermit {
                            runCatching {
                                q("fetchPlaylistMetadata", "uri" to PLAYLIST + playlist.id, "offset" to 0, "limit" to 0,
                                    "enableWatchFeedEntrypoint" to false).obj("playlistV2").obj("content").int("totalCount")
                            }.getOrNull()?.let { playlistCounts[playlist.id] = revision to it }
                        }
                    }
                }
            }
        }
        // a count still unknown is -1, so rows leave it out rather than show 0
        return entries.map { (playlist, revision) ->
            playlist.copy(songCount = playlistCounts[playlist.id]?.takeIf { it.first == revision }?.second ?: -1)
        }
    }

    override suspend fun playlistsForSong(songId: String): List<Playlist> {
        val out = ArrayList<Playlist>()
        var offset = 0
        while (offset < LIBRARY_CAP) {
            val page = q("editablePlaylists", "offset" to offset, "limit" to LIBRARY_PAGE, "textFilter" to "",
                "uris" to listOf(TRACK + songId)).obj("me").obj("editablePlaylists")
            val items = page.list("items")
            out += items.mapNotNull { playlist(it.obj("item").obj("data"), it.obj("item").string("_uri")) }
            offset += items.size
            if (items.isEmpty() || offset >= page.int("totalCount")) break
        }
        return out
    }

    private suspend fun likedPage(offset: Int, limit: Int): Page {
        val tracks = q("fetchLibraryTracks", "offset" to offset, "limit" to limit).obj("me").obj("library").obj("tracks")
        val items = tracks.list("items")
        val songs = items.mapNotNull { item ->
            val track = item.obj("track")
            song(track.obj("data"), track.string("_uri"), addedAt = item.obj("addedAt").string("isoString"))
        }
        return Page(songs, items.size, tracks.int("totalCount"))
    }

    override suspend fun allSongs(): List<Song> = runCatching { likedPage(0, TRACK_PAGE).songs }.getOrDefault(emptyList())

    // liked songs are spotify's closest thing to a song library
    override suspend fun songsPage(offset: Int, count: Int): List<Song> = runCatching {
        val out = ArrayList<Song>()
        var server = offset
        while (out.size < count) {
            val page = likedPage(server, minOf(TRACK_PAGE, count - out.size))
            out += page.songs
            server += page.consumed
            if (page.consumed == 0 || server >= page.total) break
        }
        out
    }.getOrDefault(emptyList())

    override suspend fun starredSongs(): List<Song> {
        val first = runCatching { likedPage(0, TRACK_PAGE) }.getOrNull() ?: return likedCache.orEmpty()
        // the newest page shows whether likes changed on another device since the whole list was loaded
        likedCache?.let { cached -> if (first.total == likedTotal && first.songs.map { it.id } == cached.take(first.songs.size).map { it.id }) return cached }
        val all = runCatching { loadAllLiked(first) }.getOrDefault(emptyList())
        if (all.isNotEmpty()) { likedCache = all; likedTotal = first.total }
        return all
    }

    private suspend fun loadAllLiked(first: Page): List<Song> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        val rest = (TRACK_PAGE until minOf(first.total, LIKED_CAP) step TRACK_PAGE).map { offset ->
            async { gate.withPermit { runCatching { likedPage(offset, TRACK_PAGE).songs }.getOrDefault(emptyList()) } }
        }.awaitAll()
        (first.songs + rest.flatten()).distinctBy { it.id }
    }

    override suspend fun starredCount(): Int = runCatching {
        q("fetchLibraryTracks", "offset" to 0, "limit" to 1).obj("me").obj("library").obj("tracks").int("totalCount")
    }.getOrDefault(0)

    override suspend fun starredIds(): Set<String> = coroutineScope {
        val tracks = async { runCatching { likedPage(0, TRACK_PAGE).songs.map { it.id } }.getOrDefault(emptyList()) }
        val albums = async { allAlbums().map { it.id } }
        val artists = async { allArtists().map { it.id } }
        (tracks.await() + albums.await() + artists.await()).toSet()
    }

    override suspend fun songFor(id: String): Song? = runCatching {
        song(q("getTrack", "uri" to TRACK + id, "includeVideoAssociationItems" to false).obj("trackUnion"))
    }.getOrNull()

    // throws instead of guessing so a failed check is retried rather than remembered as unliked
    override suspend fun likedSongIds(ids: List<String>): Set<String> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        ids.filter { it.isNotBlank() && !it.contains(':') }.distinct().chunked(LOOKUP_PAGE).map { chunk ->
            async {
                gate.withPermit {
                    val lookup = q("areEntitiesInLibrary", "uris" to chunk.map { TRACK + it }).list("lookup")
                    chunk.filterIndexed { i, _ -> lookup.getOrNull(i)?.obj("data")?.bool("saved") == true }
                }
            }
        }.awaitAll().flatten().toSet()
    }

    override suspend fun profileImageUrl(): String = runCatching { profile(gql)?.imageUrl }.getOrNull().orEmpty()

    override suspend fun search(query: String): SearchResults {
        val term = query.trim()
        if (term.isBlank()) return SearchResults()
        return runCatching {
            val s = q("searchDesktop", "searchTerm" to term, "offset" to 0, "limit" to SEARCH_PAGE, "numberOfTopResults" to 5,
                "includeAudiobooks" to false, "includeArtistHasConcertsField" to false, "includePreReleases" to false,
                "includeAlbumPreReleases" to false, "includeAuthors" to false, "includeEpisodeContentRatingsV2" to false,
                "isPrefix" to null, "sectionFilters" to listOf("GENERIC")).obj("searchV2")
            SearchResults(
                songs = s.obj("tracksV2").list("items").mapNotNull { song(it.obj("item").obj("data")) },
                albums = s.obj("albumsV2").list("items").mapNotNull { album(it.obj("data")) },
                artists = s.obj("artists").list("items").mapNotNull { artist(it.obj("data")) },
                playlists = s.obj("playlists").list("items").mapNotNull { playlist(it.obj("data")) },
            )
        }.getOrElse {
            AppLog.d(TAG, "search failed: ${it.message}")
            SearchResults()
        }
    }

    override suspend fun scrobble(id: String) {}

    override suspend fun radio(seedId: String): List<Song> = runCatching {
        q("internalLinkRecommenderTrack", "uri" to TRACK + seedId, "limit" to RADIO_SIZE)
            .obj("seoRecommendedTrack").list("items").mapNotNull { song(it.obj("data")) }
    }.getOrDefault(emptyList())

    override suspend fun detail(kind: String, id: String): DetailData? = runCatching {
        when (kind) {
            "album" -> albumDetail(id)
            "artist" -> artistDetail(id)
            "playlist" -> playlistDetail(id)
            "liked" -> likedDetail()
            else -> null
        }
    }.onFailure { AppLog.d(TAG, "$kind detail failed: ${it.message}") }.getOrNull()

    // the full album query also pages tracks, so a fresh album costs one request
    private suspend fun albumPage(id: String, offset: Int): Pair<JsonObject, Page> {
        val known = albumRefs[id]
        val a = if (known != null && offset > 0) q("queryAlbumTracks", "uri" to ALBUM + id, "offset" to offset, "limit" to ALBUM_PAGE).obj("albumUnion")
            else q("getAlbum", "uri" to ALBUM + id, "locale" to "", "offset" to offset, "limit" to ALBUM_PAGE).obj("albumUnion")
        val ref = known ?: AlbumRef(id, a.string("name"), image(a.obj("coverArt"))).also { albumRefs[id] = it }
        val tracks = a.obj("tracksV2")
        val items = tracks.list("items")
        return a to Page(items.mapNotNull { song(it.obj("track"), album = ref) }, items.size, tracks.int("totalCount"))
    }

    private suspend fun albumDetail(id: String): DetailData {
        val (a, page) = albumPage(id, 0)
        mark(cursor("album", id, page.songs.size), page.consumed)
        return DetailData(
            DetailInfo(a.string("name"), artistNames(a), image(a.obj("coverArt")), accentArgbFor(id), false,
                page.total.takeIf { it > 0 } ?: page.songs.size, album(a, ALBUM + id)?.typeLabel ?: "Album"),
            page.songs,
        )
    }

    private suspend fun artistDetail(id: String): DetailData = coroutineScope {
        val releases = async { runCatching { releases(ARTIST + id) }.getOrDefault(emptyList()) }
        val a = q("queryArtistOverview", "uri" to ARTIST + id, "locale" to "", "preReleaseV2" to false).obj("artistUnion")
        val name = a.obj("profile").string("name")
        val top = a.obj("discography").obj("topTracks").list("items").mapNotNull { song(it.obj("track")) }
        val listeners = a.obj("stats").long("monthlyListeners")
        DetailData(
            DetailInfo(name, if (listeners > 0) "${formatCount(listeners)} monthly listeners" else "Artist",
                image(a.obj("visuals").obj("avatarImage")), accentArgbFor(id), true, top.size, "Artist"),
            top,
            releases.await().map { if (it.artist.isBlank()) it.copy(artist = name) else it },
        )
    }

    private suspend fun releases(artistUri: String): List<Album> {
        val out = ArrayList<Album>()
        var offset = 0
        while (offset < RELEASES_CAP) {
            val all = q("queryArtistDiscographyAll", "uri" to artistUri, "offset" to offset, "limit" to RELEASES_PAGE, "order" to "DATE_DESC")
                .obj("artistUnion").obj("discography").obj("all")
            val items = all.list("items")
            out += items.mapNotNull { group -> group.obj("releases").list("items").firstOrNull()?.let { album(it) } }
            offset += items.size
            if (items.isEmpty() || offset >= all.int("totalCount")) break
        }
        return out
    }

    private suspend fun playlistDetail(id: String): DetailData {
        val p = q("fetchPlaylist", "uri" to PLAYLIST + id, "offset" to 0, "limit" to PLAYLIST_PAGE,
            "enableWatchFeedEntrypoint" to false, "includeEpisodeContentRatingsV2" to false).obj("playlistV2")
        val content = p.obj("content")
        val items = content.list("items")
        val tracks = items.mapNotNull(::playlistSong)
        mark(cursor("playlist", id, tracks.size), items.size)
        val total = content.int("totalCount").takeIf { it > 0 } ?: tracks.size
        val description = plain(p.string("description"))
        val capabilities = p.obj("currentUserCapabilities")
        val editable = if (capabilities.has("canEditMetadata")) capabilities.bool("canEditMetadata")
            else p.obj("ownerV2").obj("data").string("username") == session.userId
        p.string("revisionId").takeIf { it.isNotBlank() }?.let { playlistCounts[id] = it to total }
        return DetailData(
            DetailInfo(p.string("name").ifBlank { "Playlist" }, p.obj("ownerV2").obj("data").string("name"),
                image(p.obj("images").list("items").firstOrNull() ?: JsonObject()), accentArgbFor(id), false,
                total, "Playlist", description = description, followers = p.long("followers"), statsLine = true,
                editableDescription = description, editable = editable),
            tracks,
        )
    }

    private suspend fun likedDetail(): DetailData {
        val page = likedPage(0, TRACK_PAGE)
        mark(cursor("liked", "", page.songs.size), page.consumed)
        return DetailData(
            DetailInfo("Liked Songs", session.username, page.songs.firstOrNull()?.artworkUrl.orEmpty(),
                accentArgbFor("liked"), false, page.total, "Liked", statsLine = true),
            page.songs,
        )
    }

    private fun playlistSong(item: JsonObject): Song? =
        item.obj("itemV2").obj("data").takeIf { it.string("__typename") == "Track" }?.let { song(it) }

    private suspend fun page(kind: String, id: String, offset: Int): Page? = when (kind) {
        "liked" -> likedPage(offset, TRACK_PAGE)
        "playlist" -> {
            val content = q("fetchPlaylistContents", "uri" to PLAYLIST + id, "offset" to offset, "limit" to PLAYLIST_PAGE,
                "includeEpisodeContentRatingsV2" to false).obj("playlistV2").obj("content")
            val items = content.list("items")
            Page(items.mapNotNull(::playlistSong), items.size, content.int("totalCount"))
        }
        "album" -> albumPage(id, offset).second
        else -> null
    }

    private fun mark(key: String, serverOffset: Int) {
        if (cursors.size > CURSOR_CAP) cursors.clear()
        cursors[key] = serverOffset
    }

    override suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> = runCatching {
        val key = if (kind == "liked") "" else id
        var server = cursors[cursor(kind, key, offset)] ?: offset
        var handed = offset
        var songs = emptyList<Song>()
        while (songs.isEmpty()) {
            val page = page(kind, id, server) ?: break
            server += page.consumed
            handed += page.songs.size
            songs = page.songs
            mark(cursor(kind, key, handed), server)
            if (page.consumed == 0 || server >= page.total) break
        }
        songs
    }.getOrDefault(emptyList())

    override suspend fun collectionTracks(kind: String, id: String): List<Song> = when (kind) {
        "artist" -> coroutineScope {
            val gate = Semaphore(PARALLEL)
            val albums = releases(ARTIST + id).map { album -> async { gate.withPermit { collectionTracks("album", album.id) } } }
            val top = detail("artist", id)?.tracks.orEmpty()
            (albums.awaitAll().flatten() + top).distinctBy { it.id }
        }
        "album", "playlist", "liked" -> {
            val out = ArrayList<Song>()
            var server = 0
            while (out.size < LIKED_CAP) {
                val page = page(kind, id, server) ?: break
                out += page.songs
                server += page.consumed
                if (page.consumed == 0 || server >= page.total) break
            }
            out
        }
        else -> super.collectionTracks(kind, id)
    }

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = runCatching {
        when (kind) {
            // playlists live in the rootlist, everything else in the library
            "playlist" -> q(if (starred) "addItemsToRootlist" else "removeItemsFromRootlist", "uris" to listOf(PLAYLIST + id))
            else -> {
                val uri = when (kind) {
                    "album" -> ALBUM + id
                    "artist" -> ARTIST + id
                    else -> {
                        likedCache = null
                        TRACK + id
                    }
                }
                if (starred) q("addToLibrary", "libraryItemUris" to listOf(uri), "interactionId" to null)
                else q("removeFromLibrary", "libraryItemUris" to listOf(uri))
            }
        }
        true
    }.getOrDefault(false)

    override suspend fun createPlaylist(name: String): Boolean = createPlaylistWithId(name) != null

    // a new playlist only shows up in the library once it's added to the rootlist
    override suspend fun createPlaylistWithId(name: String): String? = runCatching {
        val body = element(mapOf("ops" to listOf(listAttributesOp(JsonObject().apply { addProperty("name", name.trim()) }, emptyList())))).asJsonObject
        val uri = gql.spclient("/playlist/v2/playlist", body).string("uri").takeIf { it.startsWith(PLAYLIST) } ?: return@runCatching null
        q("addItemsToRootlist", "uris" to listOf(uri))
        uri.removePrefix(PLAYLIST)
    }.onFailure { AppLog.d(TAG, "create playlist failed: ${it.message}") }.getOrNull()

    // a blank description is cleared the way the web player does it, by naming it as having no value
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean = runCatching {
        val values = JsonObject()
        val cleared = ArrayList<String>()
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { values.addProperty("name", it) }
        comment?.let { if (it.isBlank()) cleared += "LIST_DESCRIPTION" else values.addProperty("description", it.trim()) }
        if (values.size() == 0 && cleared.isEmpty()) return@runCatching true
        gql.spclient("/playlist/v2/playlist/$id/changes", element(mapOf("deltas" to listOf(mapOf(
            "ops" to listOf(listAttributesOp(values, cleared)),
            "info" to mapOf("source" to mapOf("client" to "WEBPLAYER")),
        )))).asJsonObject)
        true
    }.onFailure { AppLog.d(TAG, "update playlist failed: ${it.message}") }.getOrDefault(false)

    override suspend fun deletePlaylist(id: String): Boolean = setStarred(id, false, "playlist")

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean = runCatching {
        trackIds.chunked(100).forEach { chunk ->
            q("addToPlaylist", "playlistUri" to PLAYLIST + playlistId, "playlistItemUris" to chunk.map { TRACK + it },
                "newPosition" to mapOf("moveType" to "BOTTOM_OF_PLAYLIST", "fromUid" to null), "interactionId" to null)
        }
        true
    }.getOrDefault(false)

    // removal goes by each entry's uid, so the playlist is read first
    override suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean = runCatching {
        val wanted = trackIds.map { TRACK + it }.toSet()
        val uids = ArrayList<String>()
        var offset = 0
        while (true) {
            val content = q("fetchPlaylistContents", "uri" to PLAYLIST + playlistId, "offset" to offset, "limit" to PLAYLIST_PAGE,
                "includeEpisodeContentRatingsV2" to false).obj("playlistV2").obj("content")
            val items = content.list("items")
            items.filter { it.obj("itemV2").obj("data").string("uri") in wanted }.mapNotNullTo(uids) { it.string("uid").ifBlank { null } }
            offset += items.size
            if (items.isEmpty() || offset >= content.int("totalCount")) break
        }
        if (uids.isEmpty()) return@runCatching false
        uids.chunked(100).forEach { q("removeFromPlaylist", "playlistUri" to PLAYLIST + playlistId, "uids" to it) }
        true
    }.getOrDefault(false)

    // spotify's own lyrics (usually musixmatch), line synced when it has timings; a 404 means it has none
    override suspend fun serverLyrics(song: Song): Lyrics? {
        if (!song.id.matches(TRACK_ID)) return null
        val response = try {
            gql.spclient("/color-lyrics/v2/track/${song.id}/image/${URLEncoder.encode(song.artworkUrl, "UTF-8")}?format=json&vocalRemoval=false&market=from_token")
        } catch (e: SpotifyGraphqlException) {
            if (e.status == 404) return null
            AppLog.d(TAG, "lyrics failed: ${e.message}")
            throw e
        }
        val lyrics = response.obj("lyrics")
        val synced = lyrics.string("syncType") in setOf("LINE_SYNCED", "SYLLABLE_SYNCED")
        val raw = lyrics.list("lines")
        val lines = raw.mapIndexed { i, line ->
            val start = line.long("startTimeMs")
            val text = line.string("words")
            if (!synced) return@mapIndexed LyricLine(-1, text)
            val end = line.long("endTimeMs").takeIf { it > start } ?: raw.getOrNull(i + 1)?.long("startTimeMs") ?: -1L
            LyricLine((start / 1000).toInt(), text, start, syllableWords(line.list("syllables"), text, end))
        }
        if (lines.none { it.text.isNotBlank() }) return null
        return Lyrics(lines, synced, listOf("Spotify", lyrics.string("providerDisplayName")).filter { it.isNotBlank() }.joinToString(" · "))
    }

    override fun supportsCanvas(song: Song): Boolean = song.id.matches(TRACK_ID)

    // image canvases are skipped; only the looping videos replace a music video
    override suspend fun canvasUrl(song: Song): String? {
        if (!song.id.matches(TRACK_ID)) return null
        val canvas = runCatching { q("canvas", "trackUri" to TRACK + song.id).obj("trackUnion").obj("canvas") }
            .onFailure { AppLog.d(TAG, "canvas failed: ${it.message}") }.getOrNull() ?: return null
        return canvas.string("url").takeIf { it.startsWith("https://") && canvas.string("type").startsWith("VIDEO") }
    }

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String = "aurora-yt://$songId"

    override fun coverArtUrl(id: String, size: Int): String = ""

    private fun song(t: JsonObject, uri: String = t.string("uri"), album: AlbumRef? = null, addedAt: String = ""): Song? {
        if (!uri.startsWith(TRACK)) return null
        val id = uri.removePrefix(TRACK)
        val title = t.string("name").ifBlank { return null }
        val artists = t.obj("artists").list("items").ifEmpty { t.obj("firstArtist").list("items") + t.obj("otherArtists").list("items") }
        val artist = artists.map { it.obj("profile").string("name") }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Unknown artist" }
        val ofTrack = t.obj("albumOfTrack")
        val durationMs = t.obj("duration").long("totalMilliseconds").takeIf { it > 0 } ?: t.obj("trackDuration").long("totalMilliseconds")
        val durSec = (durationMs / 1000).toInt()
        return localize(Song(
            id = id,
            title = title,
            artist = artist,
            album = ofTrack.string("name").ifBlank { album?.name.orEmpty() },
            artworkUrl = image(ofTrack.obj("coverArt")).ifBlank { album?.art.orEmpty() },
            durationSec = durSec,
            explicit = t.obj("contentRating").string("label") == "EXPLICIT",
            accentArgb = accentArgbFor(id),
            streamUrl = sentinel(id, title, artist, durSec),
            albumId = ofTrack.string("uri").removePrefix(ALBUM).ifBlank { album?.id.orEmpty() },
            artistId = artists.firstOrNull()?.string("uri").orEmpty().removePrefix(ARTIST),
            dateAddedSec = epochSec(addedAt).takeIf { it > 0 } ?: parseReleaseDateEpochSec(ofTrack.obj("date").string("isoString").take(10)),
        ))
    }

    private fun sentinel(id: String, title: String, artist: String, durSec: Int): String =
        "aurora-yt://$id?q=${URLEncoder.encode("$title $artist".trim(), "UTF-8")}&dur=$durSec"

    companion object {
        const val WEB_PLAYER = "web-player"
        const val SERVER = "https://open.spotify.com"
        private const val TAG = "SpotifyWeb"
        private const val TRACK = "spotify:track:"
        private const val ALBUM = "spotify:album:"
        private const val ARTIST = "spotify:artist:"
        private const val PLAYLIST = "spotify:playlist:"
        private val TRACK_ID = Regex("[A-Za-z0-9]{22}")
        private const val LIBRARY_PAGE = 200
        private const val LIBRARY_CAP = 5000
        private const val TRACK_PAGE = 100
        private const val PLAYLIST_PAGE = 100
        private const val ALBUM_PAGE = 300
        private const val RELEASES_PAGE = 50
        private const val RELEASES_CAP = 400
        private const val SEARCH_PAGE = 20
        private const val RADIO_SIZE = 50
        private const val LIKED_CAP = 10_000
        private const val PARALLEL = 4
        private const val COUNT_PARALLEL = 8
        private const val COUNT_BUDGET_MS = 3_000L
        private const val LOOKUP_PAGE = 100
        private const val CURSOR_CAP = 2000

        suspend fun profile(gql: SpotifyGraphql): SpotifyWebProfile? {
            val p = gql.query("profileAttributes", JsonObject()).obj("me").obj("profile")
            val id = p.string("username").ifBlank { return null }
            return SpotifyWebProfile(id, p.string("name").ifBlank { id }, image(p.obj("avatar")))
        }

        // the session keeps only an opaque reference to the encrypted web login
        fun session(profile: SpotifyWebProfile, credential: String) = Session(
            server = SERVER,
            username = profile.name,
            salt = WEB_PLAYER,
            token = credential,
            type = ServerType.SPOTIFY,
            userId = profile.userId,
            imageUrl = profile.imageUrl,
        )
    }
}

private fun cursor(kind: String, id: String, handed: Int) = "$kind|$id|$handed"

// syllable-synced lines time runs of characters; slices are joined back into the exact line text
private fun syllableWords(syllables: List<JsonObject>, text: String, lineEndMs: Long): List<LyricWord> {
    val counts = syllables.map { it.int("numChars") }
    if (syllables.isEmpty() || counts.any { it <= 0 } || counts.sum() > text.length) return emptyList()
    var offset = 0
    return syllables.mapIndexed { i, syllable ->
        var stop = if (i == syllables.lastIndex) text.length else minOf(text.length, offset + counts[i])
        while (i < syllables.lastIndex && stop < text.length && text[stop].isWhitespace()) stop++
        val next = syllables.getOrNull(i + 1)?.long("startTimeMs") ?: lineEndMs
        LyricWord(syllable.long("startTimeMs"), next, text.substring(offset, stop)).also { offset = stop }
    }
}

// one list-attributes op as the web player's playlist service expects it; creating takes bare ops, changes wrap them with info
private fun listAttributesOp(values: JsonObject, cleared: List<String>): Map<String, Any> = mapOf(
    "kind" to "UPDATE_LIST_ATTRIBUTES",
    "updateListAttributes" to mapOf("newAttributes" to buildMap<String, Any> {
        put("values", values)
        if (cleared.isNotEmpty()) put("noValue", cleared)
    }),
)

private fun album(a: JsonObject, fallbackUri: String = ""): Album? {
    val uri = a.string("uri").ifBlank { fallbackUri }
    if (!uri.startsWith("spotify:album:")) return null
    val count = a.obj("tracks").int("totalCount").takeIf { it > 0 } ?: a.obj("tracksV2").int("totalCount")
    val type = a.string("type").ifBlank { a.string("albumType") }.lowercase()
    val date = a.obj("date")
    return Album(
        id = uri.removePrefix("spotify:album:"),
        title = a.string("name"),
        artist = artistNames(a),
        artworkUrl = image(a.obj("coverArt")),
        year = date.int("year").takeIf { it > 0 } ?: date.string("isoString").take(4).toIntOrNull() ?: 0,
        songCount = count,
        // spotify tags eps as singles so the track count tells them apart
        releaseType = if (type == "single" && count >= 4) "ep" else type,
    )
}

private fun artist(a: JsonObject, fallbackUri: String = ""): Artist? {
    val uri = a.string("uri").ifBlank { fallbackUri }
    if (!uri.startsWith("spotify:artist:")) return null
    return Artist(uri.removePrefix("spotify:artist:"), a.obj("profile").string("name"),
        image(a.obj("visuals").obj("avatarImage")), a.obj("stats").long("monthlyListeners"))
}

private fun playlist(p: JsonObject, fallbackUri: String = ""): Playlist? {
    val uri = p.string("uri").ifBlank { fallbackUri }
    if (!uri.startsWith("spotify:playlist:")) return null
    val id = uri.removePrefix("spotify:playlist:")
    val owner = p.obj("ownerV2").obj("data").string("name")
    return Playlist(id, p.string("name"), if (owner.isNotBlank()) "by $owner" else plain(p.string("description")),
        image(p.obj("images").list("items").firstOrNull() ?: JsonObject()), p.obj("content").int("totalCount"), accentArgbFor(id))
}

private val TAGS = Regex("<[^>]+>")
private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

// playlist descriptions arrive as html with links and escaped characters
private fun plain(html: String): String = ENTITY.replace(html.replace(TAGS, "")) { match ->
    val entity = match.groupValues[1]
    when {
        entity.startsWith("#x") || entity.startsWith("#X") -> codePoint(entity.substring(2).toIntOrNull(16)) ?: match.value
        entity.startsWith("#") -> codePoint(entity.substring(1).toIntOrNull()) ?: match.value
        entity == "amp" -> "&"
        entity == "lt" -> "<"
        entity == "gt" -> ">"
        entity == "quot" -> "\""
        entity == "apos" -> "'"
        else -> " "
    }
}.trim()

private fun codePoint(value: Int?): String? = value?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }

private fun artistNames(o: JsonObject): String =
    o.obj("artists").list("items").map { it.obj("profile").string("name") }.filter { it.isNotBlank() }.joinToString(", ")

// the widest source that is still card sized
private fun image(holder: JsonObject): String {
    val sources = holder.list("sources")
    val sized = sources.filter { it.int("width") > 0 }
    val best = sized.filter { it.int("width") <= 640 }.maxByOrNull { it.int("width") } ?: sized.minByOrNull { it.int("width") } ?: sources.firstOrNull()
    return best?.string("url").orEmpty()
}

private fun JsonObject.label(): String = string("transformedLabel").ifBlank { string("translatedBaseText") }

private fun JsonObject.list(key: String): List<JsonObject> = array(key).mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }

private fun JsonObject.long(key: String): Long = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L

private fun JsonObject.int(key: String): Int = long(key).toInt()

private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

private fun epochSec(iso: String): Long = if (iso.isBlank()) 0L else runCatching { Instant.parse(iso).epochSecond }.getOrDefault(0L)

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1f".format(n / 1_000_000.0).removeSuffix(".0") + "M"
    n >= 1_000 -> "%.1f".format(n / 1_000.0).removeSuffix(".0") + "K"
    else -> n.toString()
}

private fun variables(vararg entries: Pair<String, Any?>) = JsonObject().apply { entries.forEach { (key, value) -> add(key, element(value)) } }

private fun element(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is List<*> -> JsonArray().apply { value.forEach { add(element(it)) } }
    is Map<*, *> -> JsonObject().apply { value.forEach { (key, item) -> add(key.toString(), element(item)) } }
    else -> JsonPrimitive(value.toString())
}
