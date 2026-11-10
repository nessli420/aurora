package com.aurora.music.data

import com.aurora.music.data.remote.*
import com.aurora.music.model.*
import com.aurora.music.util.accentArgbFor
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class SoundCloudBackend(
    override val session: Session,
    private val api: SoundCloudTransport,
    private val localize: (Song) -> Song = { it },
    private val pending: SoundCloudPendingLikes = SoundCloudPendingLikes(),
    private val guestLikes: GuestLikes? = null,
) : MediaBackend {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    private val items = ConcurrentHashMap<String, JsonObject>()
    private val songs = ConcurrentHashMap<String, Song>()
    private val pageCache = ConcurrentHashMap<String, Pair<Long, List<JsonObject>>>()
    private val pageLock = Mutex()
    // tagged with its cache entry's time so clearing the cache invalidates it; it must not hold the raw json
    @Volatile private var mappedLikes: Pair<Long, List<Song>>? = null
    private val covers = ConcurrentHashMap<String, String>()
    private val user get() = session.userId

    private fun remember(list: List<Song>) = list.map(localize).onEach { songs[it.id] = it }

    // every page of a listing, cached for a minute like the youtube music library
    private suspend fun pageEntry(path: String, max: Int = 5000): Pair<Long, List<JsonObject>> = pageLock.withLock {
        pageCache[path]?.takeIf { System.nanoTime() - it.first < 60_000_000_000L }?.let { return@withLock it }
        val items = mutableListOf<JsonObject>()
        val seen = hashSetOf<String>()
        var next: String? = path
        while (next != null && items.size < max) {
            val page = api.get(next).asJsonObject
            items += page.items("collection")
            next = page.string("next_href").takeIf { it.isNotBlank() }
            if (next != null && (!seen.add(next) || seen.size > 200)) throw IOException("SoundCloud returned an incomplete list. Please retry.")
        }
        val entry: Pair<Long, List<JsonObject>> = System.nanoTime() to items
        pageCache[path] = entry
        background.launch { delay(60_000); pageCache.remove(path, entry) }
        entry
    }
    private suspend fun pages(path: String) = pageEntry(path).second
    private suspend fun list(path: String) = api.get(path).asJsonObject.items("collection")

    override val signedIn: Boolean get() = !session.isGuest
    override fun likesLocally(id: String): Boolean = !signedIn && guestLikes != null && id.matches(TRACK_ID)
    private val guest: GuestLikes? get() = guestLikes?.takeIf { !signedIn }

    override suspend fun ping(): Boolean = if (signedIn) api.get("/me").asJsonObject.string("id").isNotBlank() else list("/mixed-selections?limit=1").isNotEmpty()
    override suspend fun profileImageUrl(): String = session.imageUrl

    override suspend fun home(): HomeData = coroutineScope {
        val history = async { if (signedIn) runCatching { remember(list("/me/play-history/tracks?limit=30").mapNotNull { song(it.obj("track")) }) }.getOrNull() else null }
        val stream = async { if (signedIn) runCatching { remember(list("/stream?limit=40").mapNotNull { song(it.obj("track")) }) }.getOrNull() else null }
        val discover = async { runCatching { list("/mixed-selections?limit=10") }.getOrNull() }
        val sections = buildList {
            history.await()?.distinctBy { it.id }?.takeIf { it.isNotEmpty() }?.let { add(HomeFeedSection("history", "Recently played", items = it.map(HomeFeedItem::Track))) }
            stream.await()?.distinctBy { it.id }?.takeIf { it.isNotEmpty() }?.let { add(HomeFeedSection("stream", "From people you follow", items = it.map(HomeFeedItem::Track))) }
            discover.await()?.forEach { selection ->
                val items = selection.obj("items").items("collection").mapNotNull { collection(it) }.distinctBy { it.key }
                if (items.isNotEmpty()) add(HomeFeedSection(selection.string("id"), selection.string("title"), selection.string("description"), items))
            }
        }
        if (sections.isEmpty() && history.await() == null && discover.await() == null) throw IOException("Could not load SoundCloud. Please retry.")
        HomeData(sections = sections)
    }

    private suspend fun library(): List<JsonObject> {
        if (!signedIn) return emptyList()
        val entries = pages("/me/library/all?limit=200")
        val ids = entries.mapTo(HashSet(), ::collectionId)
        val waiting = waiting(PLAYLIST_LIKE, entries.filter { it.string("type").endsWith("-like") }.mapTo(HashSet(), ::collectionId))
        if (waiting.isEmpty()) return entries
        val added = waiting.filterValues { it.liked }.keys.filter { it !in ids }.reversed().mapNotNull { id ->
            if (id.startsWith(SYSTEM_PLAYLIST)) item("/system-playlists/$id")?.let { JsonObject().apply { addProperty("type", "system-playlist-like"); add("system_playlist", it) } }
            else item("/playlists/$id?representation=mini")?.let { JsonObject().apply { addProperty("type", "playlist-like"); add("playlist", it) } }
        }
        return added + entries.filter { waiting[collectionId(it)]?.liked != false }
    }
    private fun collectionId(entry: JsonObject) = entry.obj("playlist").string("id").ifBlank { entry.obj("system_playlist").string("urn") }

    private suspend fun item(path: String): JsonObject? =
        items[path] ?: runCatching { api.get(path).asJsonObject }.getOrNull()?.also { items[path] = it }
    override suspend fun allAlbums() = library().mapNotNull { it.obj("playlist").takeIf { p -> p.bool("is_album") }?.let(::album) }.distinctBy { it.id }
    override suspend fun allPlaylists() = withCovers(library().mapNotNull { entry ->
        entry.obj("playlist").takeIf { it.string("id").isNotBlank() && !it.bool("is_album") }?.let(::playlist)
            ?: entry.obj("system_playlist").takeIf { it.string("urn").isNotBlank() }?.let(::playlist)
    }.distinctBy { it.id })

    // the library lists coverless playlists without tracks, so borrow the first track's art like soundcloud does
    // shortcut: covers are kept for the session, a reordered playlist keeps its old cover until the app restarts
    private suspend fun withCovers(list: List<Playlist>): List<Playlist> {
        val missing = list.filter { it.coverUrl.isBlank() && !it.id.startsWith(SYSTEM_PLAYLIST) && !covers.containsKey(it.id) }
        val gate = Semaphore(8)
        withTimeoutOrNull(8_000) {
            coroutineScope {
                missing.forEach { playlist -> launch { gate.withPermit { runCatching {
                    covers[playlist.id] = cover(api.get("/playlists/${playlist.id}?representation=mini").asJsonObject)
                } } } }
            }
        }
        return list.map { p -> covers[p.id]?.takeIf { p.coverUrl.isBlank() && it.isNotBlank() }?.let { p.copy(coverUrl = it) } ?: p }
    }
    override suspend fun allArtists(): List<Artist> {
        if (!signedIn) return emptyList()
        val followed = pages("/users/$user/followings?limit=200").map(::artist)
        val ids = followed.mapTo(HashSet()) { it.id }
        val waiting = waiting(FOLLOW, ids)
        if (waiting.isEmpty()) return followed
        val added = waiting.filterValues { it.liked }.keys.filter { it !in ids }.reversed().mapNotNull { id -> item("/users/$id")?.let(::artist) }
        return added + followed.filter { waiting[it.id]?.liked != false }
    }
    override suspend fun allSongs(): List<Song> {
        if (!signedIn) return emptyList()
        val liked = likes()
        val ids = liked.mapTo(HashSet()) { it.id }
        val waiting = waiting(TRACK_LIKE, ids)
        if (waiting.isEmpty()) return liked
        val added = tracks(waiting.filterValues { it.liked }.keys.filter { it !in ids }.reversed())
        return added + liked.filter { waiting[it.id]?.liked != false }
    }

    private suspend fun likes(): List<Song> {
        val (time, entries) = pageEntry("/users/$user/track_likes?limit=200")
        mappedLikes?.takeIf { it.first == time }?.let { return it.second }
        return remember(entries.mapNotNull { song(it.obj("track")) }).distinctBy { it.id }.also { mappedLikes = time to it }
    }

    // likes soundcloud's own list now agrees with are done; ones it took a while ago but still doesn't list are left to it
    private fun waiting(kind: String, present: Set<String>): Map<String, PendingLike> {
        val now = System.currentTimeMillis()
        val ofKind = pending.of(user).filterKeys { it.substringBefore(':') == kind }
        ofKind.forEach { (key, like) ->
            if (like.liked == (key.substringAfter(':') in present) || (like.sentAt > 0 && now - like.sentAt > SoundCloudPendingLikes.SENT_GRACE_MS)) pending.clear(user, key)
        }
        val left = pending.of(user).filterKeys { it.substringBefore(':') == kind }
        if (left.values.any { it.sentAt == 0L } && pending.dueForSync(user)) background.launch { sync(check = false) }
        return left.mapKeys { it.key.substringAfter(':') }
    }

    private suspend fun tracks(ids: List<String>): List<Song> {
        val missing = ids.filterNot(songs::containsKey)
        missing.chunked(50).forEach { chunk ->
            runCatching { remember(api.get("/tracks?ids=${chunk.joinToString(",")}").asJsonArray.filter { it.isJsonObject }.mapNotNull { song(it.asJsonObject) }) }
        }
        return ids.mapNotNull { songs[it] }
    }

    // stops at the first refusal: until the user passes the bot check the rest would be refused too
    private suspend fun sync(check: Boolean): Boolean = syncLock.withLock {
        for ((key, like) in pending.of(user)) {
            if (like.sentAt > 0) continue
            val kind = key.substringBefore(':')
            val id = key.substringAfter(':')
            val path = when (kind) {
                FOLLOW -> "/me/followings/$id"
                PLAYLIST_LIKE -> if (id.startsWith(SYSTEM_PLAYLIST)) "/users/$user/system_playlist_likes/$id" else "/users/$user/playlist_likes/$id"
                else -> "/users/$user/track_likes/$id"
            }
            val method = if (!like.liked) "DELETE" else if (kind == FOLLOW) "POST" else "PUT"
            try {
                if (check) api.sendWithCheck(method, path) else api.send(method, path)
            } catch (e: SoundCloudCheckException) {
                return@withLock false
            } catch (e: MediaAccountExpiredException) {
                return@withLock false
            } catch (e: IOException) {
                if (like.liked) continue
            }
            pending.set(user, key, like.copy(sentAt = System.currentTimeMillis()))
            pageCache.clear()
        }
        pending.of(user).values.none { it.sentAt == 0L }
    }

    // sends whatever is waiting and lets soundcloud's bot check ask first; with nothing waiting, re-liking a liked track shows whether it would
    suspend fun verify(): Boolean {
        if (!signedIn) return true
        if (pending.of(user).values.any { it.sentAt == 0L }) return sync(check = true)
        val probe = allSongs().firstOrNull() ?: return true
        return runCatching { api.sendWithCheck("PUT", "/users/$user/track_likes/${probe.id}") }.isSuccess
    }
    override suspend fun librarySongs(limit: Int) = allSongs().take(limit)
    override suspend fun songsPage(offset: Int, count: Int) = allSongs().drop(offset).take(count)
    override suspend fun starredSongs() = guest?.of(session.type)?.map { localize(it).copy(liked = true) } ?: allSongs().map { it.copy(liked = true) }
    // the list the liked page shows, not /me's likes_count: that counter keeps likes of removed tracks and drifts from the list
    override suspend fun starredCount() = if (!signedIn) guest?.count(session.type) ?: 0 else allSongs().size
    override suspend fun starredIds(): Set<String> = guest?.ids(session.type) ?: coroutineScope {
        val tracks = async { allSongs().map { it.id } }
        val artists = async { allArtists().map { it.id } }
        val collections = async { library().filter { it.string("type").endsWith("-like") }.map { it.obj("playlist").string("id").ifBlank { it.obj("system_playlist").string("urn") } } }
        (tracks.await() + artists.await() + collections.await()).filter { it.isNotBlank() }.toSet()
    }
    override suspend fun likedSongIds(ids: List<String>) = (guest?.ids(session.type) ?: allSongs().map { it.id }.toSet()).intersect(ids.toSet())

    override suspend fun search(query: String): SearchResults = if (query.isBlank()) SearchResults() else coroutineScope {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val tracks = async { list("/search/tracks?q=$q&limit=30").mapNotNull(::song) }
        val albums = async { list("/search/albums?q=$q&limit=10").map(::album) }
        val users = async { list("/search/users?q=$q&limit=10").map(::artist) }
        val sets = async { list("/search/playlists_without_albums?q=$q&limit=10").map(::playlist) }
        SearchResults(remember(tracks.await()), albums.await(), users.await(), sets.await())
    }

    // matching an imported track only needs tracks, not the albums, people and sets a search also fetches
    override fun importStages(): List<ImportStage> = listOf(ImportStage(ImportSource.SOUNDCLOUD) { query ->
        if (query.isBlank()) emptyList() else remember(list("/search/tracks?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=20").mapNotNull(::song))
    })

    suspend fun resolvePlaylist(url: String): DetailData? {
        val item = api.get("/resolve?url=${URLEncoder.encode(url, "UTF-8")}").asJsonObject
        return if (item.string("kind") == "playlist") detail("playlist", item.string("id")) else null
    }

    override suspend fun songFor(id: String): Song? {
        songs[id]?.let { return it }
        if (!id.matches(Regex("[0-9]+"))) return null
        return song(api.get("/tracks/$id").asJsonObject)?.let(localize)?.also { songs[id] = it }
    }

    override suspend fun radio(seedId: String): List<Song> =
        remember(list("/tracks/$seedId/related?limit=50").mapNotNull(::song)).filter { it.id != seedId }

    // playlists list only their first few tracks in full, the rest are bare ids
    private suspend fun hydrate(entries: List<JsonObject>): List<Song> = coroutineScope {
        val ids = entries.map { it.string("id") }.filter { it.isNotBlank() }
        val known = entries.mapNotNull(::song).associateBy { it.id }
        val loaded = ids.filter { it !in known }.chunked(50).map { chunk ->
            async { api.get("/tracks?ids=${chunk.joinToString(",")}").asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonObject }?.let { song(it.asJsonObject) } } }
        }.awaitAll().flatten().associateBy { it.id }
        remember(ids.mapNotNull { known[it] ?: loaded[it] })
    }

    override suspend fun detail(kind: String, id: String): DetailData? = when (kind) {
        "liked" -> starredSongs().let { DetailData(DetailInfo("Liked Songs", "SoundCloud", it.firstOrNull()?.artworkUrl.orEmpty(), accentArgbFor("liked"), false, it.size, "Liked"), it) }
        "album", "playlist" -> {
            val set = api.get(if (id.startsWith(SYSTEM_PLAYLIST)) "/system-playlists/$id" else "/playlists/$id").asJsonObject
            val tracks = hydrate(set.items("tracks")).let { list ->
                if (!set.bool("is_album")) list else list.map { it.copy(album = set.string("title"), albumId = id).also { s -> songs[s.id] = s } }
            }
            val owner = set.obj("user").string("username").ifBlank { "SoundCloud" }
            DetailData(DetailInfo(set.string("title"), owner, cover(set).ifBlank { tracks.firstOrNull()?.artworkUrl.orEmpty() }, accentArgbFor(id), false,
                tracks.size, if (set.bool("is_album")) "Album" else "Playlist", description = set.string("description"),
                editableDescription = set.string("description"),
                editable = set.obj("user").string("id") == user && !id.startsWith(SYSTEM_PLAYLIST)), tracks)
        }
        "artist" -> coroutineScope {
            val profile = async { api.get("/users/$id").asJsonObject }
            val top = async { list("/users/$id/toptracks?limit=50").mapNotNull(::song) }
            val albums = async { list("/users/$id/albums?limit=50").map(::album) }
            val uploads = async { list("/users/$id/tracks?limit=50").mapNotNull(::song) }
            val person = profile.await()
            val tracks = remember((top.await() + uploads.await()).distinctBy { it.id })
            DetailData(DetailInfo(person.string("username"), person.string("full_name"), art(person.string("avatar_url")), accentArgbFor(id), true,
                tracks.size, "Artist", description = person.string("description"), followers = person.int("followers_count").toLong()), tracks, albums.await())
        }
        else -> null
    }

    private suspend fun change(method: String, path: String, body: JsonObject? = null): JsonElement {
        if (!signedIn) throw IOException("Sign in to SoundCloud to change playlists.")
        return api.send(method, path, body).also { pageCache.clear() }
    }

    // a like or follow counts here straight away and reaches soundcloud in the background, whenever its bot check lets it through
    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean {
        guest?.let { return it.setStarred(session.type, id, starred, kind, ::songFor) }
        if (!signedIn) return false
        val key = "${when (kind) { "artist" -> FOLLOW; "album", "playlist" -> PLAYLIST_LIKE; else -> TRACK_LIKE }}:$id"
        val earlier = pending.of(user)[key]
        if (earlier != null && earlier.liked != starred && earlier.sentAt == 0L) pending.clear(user, key)
        else pending.set(user, key, PendingLike(starred))
        background.launch { sync(check = false) }
        return true
    }

    // only playlists the account owns take new tracks
    override suspend fun playlistsForSong(songId: String) =
        withCovers(library().filter { it.string("type") == "playlist" && !it.obj("playlist").bool("is_album") }.map { playlist(it.obj("playlist")) })

    override suspend fun createPlaylist(name: String) = createPlaylistWithId(name) != null
    override suspend fun createPlaylistWithId(name: String): String? = createPlaylistWithId(name, emptyList())
    override suspend fun createPlaylistWithId(name: String, trackIds: List<String>): String? {
        if (name.isBlank()) return null
        val body = JsonObject().apply { add("playlist", JsonObject().apply {
            addProperty("title", name.trim()); addProperty("sharing", "private"); add("tracks", ids(trackIds))
        }) }
        return change("POST", "/playlists", body).asJsonObject.string("id").takeIf { it.isNotBlank() }
    }

    // soundcloud replaces a playlist's whole track list on every save, so each edit sends the full list back
    private suspend fun saveTracks(id: String, edit: (List<String>) -> List<String>?, title: String? = null, description: String? = null): Boolean {
        val set = api.get("/playlists/$id").asJsonObject
        val current = set.items("tracks").map { it.string("id") }
        if (current.size != set.int("track_count")) throw IOException("SoundCloud returned an incomplete playlist. Please retry.")
        val next = edit(current) ?: return false
        val saved = change("PUT", "/playlists/$id", JsonObject().apply { add("playlist", JsonObject().apply {
            title?.let { addProperty("title", it) }; description?.let { addProperty("description", it) }; add("tracks", ids(next))
        }) }).asJsonObject
        if (saved.has("track_count") && saved.int("track_count") != next.size) throw IOException("SoundCloud did not save this playlist. Please retry.")
        covers.remove(id)
        return true
    }

    override suspend fun updatePlaylist(id: String, name: String?, comment: String?) =
        if (name == null && comment == null) true else saveTracks(id, { it }, name?.trim()?.takeIf { it.isNotBlank() }, comment)
    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) =
        trackIds.isEmpty() || saveTracks(playlistId, { it + trackIds })
    // every occurrence goes, and a removal that matched nothing is not reported as done
    override suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>) =
        saveTracks(playlistId, { current -> current.filterNot { it in trackIds }.takeIf { it.size < current.size } })
    override suspend fun deletePlaylist(id: String): Boolean { change("DELETE", "/playlists/$id"); return true }

    // the web player adds each played track to the account's history the same way
    override suspend fun scrobble(id: String) {
        if (signedIn && id.matches(Regex("[0-9]+"))) api.send("POST", "/me/play-history", JsonObject().apply { addProperty("track_urn", "soundcloud:tracks:$id") })
    }

    override fun supportsComments(song: Song) = song.id.matches(Regex("[0-9]+"))

    // popular tracks carry thousands; the newest thousand are plenty to scatter along the waveform
    override suspend fun comments(song: Song): List<TrackComment>? {
        if (!supportsComments(song)) return null
        val out = mutableListOf<TrackComment>()
        val seen = hashSetOf<String>()
        var next: String? = "/tracks/${song.id}/comments?threaded=0&filter_replies=0&limit=200"
        while (next != null && out.size < 1000) {
            val page = api.get(next).asJsonObject
            out += page.items("collection").mapNotNull(::comment)
            next = page.string("next_href").takeIf { it.isNotBlank() && seen.add(it) }
        }
        return out.sortedBy { it.timestampMs }
    }

    override suspend fun postComment(song: Song, body: String, timestampMs: Long): TrackComment? {
        if (!canPostComments(song)) return null
        // the web player posts comments form-encoded and unwrapped, and json gets a 400
        return comment(api.send("POST", "/tracks/${song.id}/comments", form = mapOf("body" to body, "timestamp" to timestampMs.toString())).asJsonObject)
    }

    override fun supportsReposts(id: String) = signedIn && id.matches(Regex("[0-9]+"))

    // the web player keeps the account's reposts as pages of bare track ids
    override suspend fun isReposted(id: String): Boolean {
        if (!supportsReposts(id)) return false
        val seen = hashSetOf<String>()
        var next: String? = "/me/track_reposts/ids?limit=200"
        while (next != null) {
            val page = api.get(next).asJsonObject
            if (page.array("collection").any { it.isJsonPrimitive && it.asString == id }) return true
            next = page.string("next_href").takeIf { it.isNotBlank() && seen.add(it) }
        }
        return false
    }

    override suspend fun setReposted(id: String, reposted: Boolean): Boolean {
        if (!supportsReposts(id)) return false
        api.send(if (reposted) "PUT" else "DELETE", "/me/track_reposts/$id")
        return true
    }

    /** a signed hls link, or null when soundcloud only offers a preview or an encrypted stream */
    suspend fun playbackUrl(id: String): String? {
        require(id.matches(Regex("[0-9]+")))
        val track = api.get("/tracks/$id").asJsonObject
        val pick = transcoding(track) ?: return null
        val auth = URLEncoder.encode(track.string("track_authorization"), "UTF-8")
        return api.get("${pick.string("url")}?track_authorization=$auth").asJsonObject.string("url").takeIf { it.startsWith("https://") }
    }
    override suspend fun serverLyrics(song: Song): Lyrics? = null
    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = sentinel(songId)

    // the hls stream is joined into one file; previews and drm-only tracks save their youtube match instead
    override suspend fun downloadUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        playbackUrl(songId)?.let { return it }
        val song = songFor(songId) ?: throw IOException("SoundCloud could not find this item.")
        return "aurora-yt://sc$songId?q=${URLEncoder.encode("${song.title} ${song.artist}".trim(), "UTF-8")}&dur=${song.durationSec}"
    }
    override fun coverArtUrl(id: String, size: Int) = songs[id]?.artworkUrl.orEmpty()

    companion object {
        // the only ids a guest like can be looked up by; one from another source never reaches this account
        private val TRACK_ID = Regex("[0-9]+")
        private const val SYSTEM_PLAYLIST = "soundcloud:system-playlists:"
        private const val TRACK_LIKE = "track"
        private const val FOLLOW = "artist"
        private const val PLAYLIST_LIKE = "playlist"

        fun sentinel(id: String) = "aurora-sc://track/$id"

        private val PRESETS = listOf("aac_160k", "aac_96k", "abr_sq", "mp3_1_0", "mp3_0_1")
        private fun ids(values: List<String>) = JsonArray().apply { values.forEach { add(it.toLongOrNull() ?: return@forEach) } }

        // previews and drm streams are never picked; a go+ account gets the high quality one first
        fun transcoding(track: JsonObject): JsonObject? = track.obj("media").items("transcodings")
            .filter { !it.bool("snipped") && it.obj("format").string("protocol") in setOf("hls", "progressive") && it.string("url").isNotBlank() }
            .sortedWith(compareBy({ it.string("quality") != "hq" }, { PRESETS.indexOf(it.string("preset")).let { i -> if (i < 0) PRESETS.size else i } }))
            .firstOrNull()

        // the api links 100px art, the same file comes in 500px
        fun art(url: String) = url.replace("-large.", "-t500x500.")
        private fun art(item: JsonObject) = art(item.string("artwork_url").ifBlank { item.string("calculated_artwork_url") })
        // sets without their own art show their first track's, as on soundcloud
        private fun cover(set: JsonObject) = art(set).ifBlank { set.items("tracks").firstNotNullOfOrNull(::song)?.artworkUrl.orEmpty() }

        // bare id stubs carry no title and are skipped
        fun song(t: JsonObject): Song? {
            val id = t.string("id")
            val title = t.string("title")
            if (id.isBlank() || title.isBlank()) return null
            val user = t.obj("user")
            val meta = t.obj("publisher_metadata")
            return Song(id, title, meta.string("artist").ifBlank { user.string("username") }, meta.string("album_title"),
                art(t.string("artwork_url").ifBlank { user.string("avatar_url") }),
                (t.int("full_duration").takeIf { it > 0 } ?: t.int("duration")) / 1000,
                explicit = meta.bool("explicit"), accentArgb = accentArgbFor(id), streamUrl = sentinel(id),
                artistId = user.string("id"), genre = t.string("genre"))
        }

        fun album(p: JsonObject) = Album(p.string("id"), p.string("title"), p.obj("user").string("username"), cover(p),
            (p.string("release_date").ifBlank { p.string("published_at") }.ifBlank { p.string("created_at") }).take(4).toIntOrNull() ?: 0,
            p.int("track_count"), p.int("duration") / 1000, p.string("set_type"))

        fun playlist(p: JsonObject): Playlist {
            val id = p.string("urn").takeIf { it.startsWith(SYSTEM_PLAYLIST) } ?: p.string("id")
            return Playlist(id, p.string("title"), p.obj("user").string("username").ifBlank { p.string("short_description") }, cover(p),
                p.int("track_count").takeIf { it > 0 } ?: p.array("tracks").size(), accentArgbFor(id))
        }

        fun artist(u: JsonObject) = Artist(u.string("id"), u.string("username"), art(u.string("avatar_url")), 0)

        fun comment(c: JsonObject): TrackComment? {
            val id = c.string("id")
            val body = c.string("body").trim()
            if (id.isBlank() || body.isBlank()) return null
            val user = c.obj("user")
            return TrackComment(id, body, c.int("timestamp").toLong(), user.string("username"), art(user.string("avatar_url")))
        }

        private fun collection(item: JsonObject): HomeFeedItem? = when (item.string("kind")) {
            "track" -> song(item)?.let(HomeFeedItem::Track)
            "user" -> HomeFeedItem.Performer(artist(item))
            "playlist" -> if (item.bool("is_album")) HomeFeedItem.Record(album(item)) else HomeFeedItem.Collection(playlist(item))
            "system-playlist" -> HomeFeedItem.Collection(playlist(item))
            else -> null
        }

        private fun JsonObject.items(key: String) = array(key).filter { it.isJsonObject }.map { it.asJsonObject }
        private fun JsonObject.int(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.takeIf { it.isNumber }?.asInt ?: 0
        private fun JsonObject.bool(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.takeIf { it.isBoolean }?.asBoolean ?: false

        /** the signed-in account behind an oauth token: id, display name, avatar */
        suspend fun profile(api: SoundCloudTransport): Triple<String, String, String> {
            val me = api.get("/me").asJsonObject
            val id = me.string("id")
            if (id.isBlank()) throw IOException("Sign in to SoundCloud, then try connecting again.")
            return Triple(id, me.string("username").ifBlank { me.string("permalink") }, art(me.string("avatar_url")))
        }
    }
}
