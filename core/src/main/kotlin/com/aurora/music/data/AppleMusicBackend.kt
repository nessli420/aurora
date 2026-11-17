package com.aurora.music.data

import com.aurora.music.data.remote.AppleMusicTransport
import com.aurora.music.data.remote.AppleMusicWebSession
import com.aurora.music.data.remote.array
import com.aurora.music.data.remote.obj
import com.aurora.music.data.remote.string
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentArgbFor
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

data class AppleMusicProfile(val userId: String, val name: String, val imageUrl: String, val storefront: String)

// apple's catalogue through its web player api; tracks play through a youtube match, as spotify's do
class AppleMusicBackend(
    override val session: Session,
    private val api: AppleMusicTransport,
    private val localize: (Song) -> Song = { it },
    private val guestLikes: GuestLikes? = null,
) : MediaBackend {
    private val songs = ConcurrentHashMap<String, Song>()
    private val pageCache = ConcurrentHashMap<String, Pair<Long, List<JsonObject>>>()
    @Volatile private var subscription: Boolean? = null
    // apple lists a new playlist, and drops a deleted one, a few seconds late
    private val createdPlaylists = ConcurrentHashMap<String, Pair<Long, Playlist>>()
    private val deletedPlaylists = ConcurrentHashMap<String, Long>()

    private fun remember(list: List<Song>) = list.map(localize).onEach { songs[it.id] = it }
    private suspend fun catalog(path: String) = api.get("/v1/catalog/${api.storefront()}/$path")

    // apple keeps likes and playlists only with a subscription; a guest's, or an account's without one, stay on this device
    override val signedIn: Boolean get() = !session.isGuest
    override fun likesLocally(id: String): Boolean = session.isGuest && guestLikes != null && id.matches(CATALOG_ID)
    private suspend fun device(): GuestLikes? = guestLikes?.takeIf { !subscribed() }
    override suspend fun acceptsLikes(): Boolean = subscribed()
    override suspend fun ping(): Boolean = runCatching { catalog("charts?types=songs&limit=1"); true }.getOrDefault(false)
    override suspend fun profileImageUrl(): String = session.imageUrl

    // a library, likes, history and lyrics come with a subscription; without one the account browses the catalogue
    private suspend fun subscribed(): Boolean {
        if (session.isGuest) return false
        subscription?.let { return it }
        return api.get("/v1/me/account?meta=subscription").obj("meta").obj("subscription").bool("active").also { subscription = it }
    }

    override suspend fun home(): HomeData = coroutineScope {
        val personal = async { runCatching { if (subscribed()) personalShelves() else emptyList() }.getOrDefault(emptyList()) }
        val browse = async { runCatching { api.get("/v1/editorial/${api.storefront()}/groupings?platform=web&name=music") }.getOrNull() }
        val charts = async { runCatching { catalog("charts?types=songs,albums,playlists&limit=20") }.getOrNull() }
        val sections = personal.await() + browse.await()?.let(::browseShelves).orEmpty() + charts.await()?.let(::chartShelves).orEmpty()
        if (sections.isEmpty()) throw IOException("Could not load Apple Music. Please retry.")
        HomeData(sections = sections.map { section ->
            section.copy(items = section.items.map { if (it is HomeFeedItem.Track) HomeFeedItem.Track(remember(listOf(it.song)).first()) else it })
        })
    }

    private suspend fun personalShelves(): List<HomeFeedSection> = coroutineScope {
        val played = async { runCatching { api.get("/v1/me/recent/played/tracks?types=songs,library-songs&limit=30").data() }.getOrDefault(emptyList()) }
        val added = async { runCatching { api.get("/v1/me/library/recently-added?limit=25").data() }.getOrDefault(emptyList()) }
        val picks = async { runCatching { api.get("/v1/me/recommendations?limit=10").data() }.getOrDefault(emptyList()) }
        listOfNotNull(
            shelf("am-played", "Recently played", played.await()),
            shelf("am-added", "Recently added", added.await()),
        ) + picks.await().mapNotNull { pick ->
            shelf("am-${pick.string("id")}", pick.obj("attributes").obj("title").string("stringForDisplay"), pick.related("contents"))
        }
    }

    // every page of a library listing, kept for a minute; a listing apple has nothing for is empty
    private suspend fun library(path: String, max: Int = MAX_LIBRARY): List<JsonObject> {
        if (!subscribed()) return emptyList()
        pageCache[path]?.takeIf { System.nanoTime() - it.first < CACHE_NS }?.let { return it.second }
        val out = mutableListOf<JsonObject>()
        while (out.size < max) {
            val page = try { api.get("$path&offset=${out.size}") } catch (_: FileNotFoundException) { break }
            val items = page.data()
            out += items
            if (items.isEmpty() || page.string("next").isBlank()) break
        }
        pageCache[path] = System.nanoTime() to out
        return out
    }

    private suspend fun librarySongs(path: String) = remember(library(path).mapNotNull(::librarySong)).distinctBy { it.id }
    private suspend fun favorites() = library(PLAYLISTS).firstOrNull(::isFavorites)

    override suspend fun allAlbums(): List<Album> = library("/v1/me/library/albums?limit=100&include=catalog").mapNotNull(::libraryAlbum).distinctBy { it.id }
    override suspend fun allArtists(): List<Artist> = library("/v1/me/library/artists?limit=100&include=catalog").mapNotNull(::libraryArtist).distinctBy { it.id }
    override suspend fun allPlaylists(): List<Playlist> = devicePlaylists() + settled(library(PLAYLISTS).filterNot(::isFavorites).mapNotNull(::playlist))

    // still listed once a subscription comes, until they are added to the account
    private fun devicePlaylists(): List<Playlist> = guestLikes?.playlists(session.type).orEmpty().map { p ->
        Playlist(p.id, p.name.orEmpty(), session.username, p.songs.orEmpty().firstOrNull()?.artworkUrl.orEmpty(), p.songs.orEmpty().size, accentArgbFor(p.id))
    }
    private fun onDevice(id: String) = id.startsWith(GuestLikes.DEVICE_PLAYLIST)

    private fun settled(listed: List<Playlist>): List<Playlist> {
        val now = System.nanoTime()
        createdPlaylists.entries.removeIf { (id, made) -> now - made.first > PENDING_NS || listed.any { it.id == id } }
        deletedPlaylists.entries.removeIf { (id, at) -> now - at > PENDING_NS || listed.none { it.id == id } }
        return createdPlaylists.values.map { it.second } + listed.filterNot { deletedPlaylists.containsKey(it.id) }
    }

    override suspend fun allSongs(): List<Song> = librarySongs("/v1/me/library/songs?limit=100&include=catalog")

    // apple's favorite songs live in a library playlist it tags as favorited
    override suspend fun starredSongs(): List<Song> = device()?.of(session.type)?.map { localize(it).copy(liked = true) }
        ?: favorites()?.let { librarySongs("/v1/me/library/playlists/${it.string("id")}/tracks?limit=100&include=catalog") }.orEmpty().map { it.copy(liked = true) }
    override suspend fun starredCount(): Int = device()?.count(session.type) ?: starredSongs().size
    override suspend fun starredIds(): Set<String> = device()?.let { d -> d.ids(session.type) + d.likedCollections(session.type).map { it.substringAfter(':') } }
        ?: starredSongs().mapTo(HashSet()) { it.id }

    // a favourite is a catalogue song the account rated 1
    override suspend fun likedSongIds(ids: List<String>): Set<String> {
        device()?.let { return it.ids(session.type).intersect(ids.toSet()) }
        if (!subscribed()) return emptySet()
        return ids.filter { it.matches(CATALOG_ID) }.distinct().chunked(100).flatMap { chunk ->
            api.get("/v1/catalog/${api.storefront()}?ids[songs]=${chunk.joinToString(",")}&fields[songs]=personalRating").data()
                .filter { it.obj("attributes").string("personalRating") == "1" }.map { it.string("id") }
        }.toSet()
    }

    override suspend fun songFor(id: String): Song? {
        songs[id]?.let { return it }
        return when {
            id.matches(CATALOG_ID) -> catalog("songs/$id").data().firstNotNullOfOrNull(::song)
            id.matches(LIBRARY_SONG) && subscribed() -> api.get("/v1/me/library/songs/$id?include=catalog").data().firstNotNullOfOrNull(::librarySong)
            else -> null
        }?.let { remember(listOf(it)).first() }
    }

    override suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val r = catalog("search?term=${enc(query)}&types=songs,albums,artists,playlists&limit=25&include[songs]=artists,albums").obj("results")
        return SearchResults(remember(r.data("songs").mapNotNull(::song)), r.data("albums").mapNotNull(::album),
            r.data("artists").mapNotNull(::artist), r.data("playlists").mapNotNull(::playlist))
    }

    override fun importStages(): List<ImportStage> = listOf(ImportStage(ImportSource.APPLE_MUSIC) { query ->
        if (query.isBlank()) emptyList()
        else remember(catalog("search?term=${enc(query)}&types=songs&limit=25&include[songs]=artists,albums").obj("results").data("songs").mapNotNull(::song))
    })

    override suspend fun scrobble(id: String) {}

    // the seed artist's top songs taken in turn with those of the artists apple calls similar
    override suspend fun radio(seedId: String): List<Song> {
        if (!seedId.matches(CATALOG_ID)) return emptyList()
        val artistId = songFor(seedId)?.artistId?.takeIf { it.matches(CATALOG_ID) }
            ?: catalog("songs/$seedId").data().firstOrNull()?.related("artists")?.firstOrNull()?.string("id") ?: return emptyList()
        val seed = catalog("artists/$artistId?views=top-songs,similar-artists").data().firstOrNull() ?: return emptyList()
        val similar = seed.view("similar-artists").map { it.string("id") }.filter { it.matches(CATALOG_ID) }.take(10)
        val others = if (similar.isEmpty()) emptyList() else catalog("artists?ids=${similar.joinToString(",")}&views=top-songs").data()
        val lists = (listOf(seed) + others).map { a -> a.view("top-songs").mapNotNull { song(it, artistId = a.string("id")) } }
        val mixed = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        return remember(mixed.filter { it.id != seedId })
    }

    override suspend fun detail(kind: String, id: String): DetailData? = when {
        kind == "liked" -> starredSongs().let {
            DetailData(DetailInfo("Liked Songs", session.username, it.firstOrNull()?.artworkUrl.orEmpty(), accentArgbFor("liked"), false, it.size, "Liked"), it)
        }
        kind == "playlist" && onDevice(id) -> {
            val p = guestLikes?.playlist(session.type, id) ?: throw FileNotFoundException("Apple Music could not find this item.")
            val tracks = remember(p.songs.orEmpty())
            DetailData(DetailInfo(p.name.orEmpty(), session.username, tracks.firstOrNull()?.artworkUrl.orEmpty(), accentArgbFor(id), false, tracks.size, "Playlist",
                editableDescription = p.description.orEmpty(), description = p.description.orEmpty(), editable = true), tracks)
        }
        kind == "playlist" && id.startsWith("p.") -> {
            val p = api.get("/v1/me/library/playlists/$id").data().firstOrNull() ?: throw FileNotFoundException("Apple Music could not find this item.")
            val info = playlist(p) ?: throw FileNotFoundException("Apple Music could not find this item.")
            val tracks = librarySongs("/v1/me/library/playlists/$id/tracks?limit=100&include=catalog")
            val editable = p.obj("attributes").bool("canEdit") && !isFavorites(p)
            DetailData(DetailInfo(info.title, info.subtitle, info.coverUrl.ifBlank { tracks.firstOrNull()?.artworkUrl.orEmpty() }, accentArgbFor(id), false,
                tracks.size, "Playlist", editableDescription = notes(p).takeIf { editable }, description = notes(p), editable = editable), tracks)
        }
        kind == "album" && id.startsWith("l.") -> {
            val a = api.get("/v1/me/library/albums/$id").data().firstOrNull() ?: throw FileNotFoundException("Apple Music could not find this item.")
            val info = album(a) ?: throw FileNotFoundException("Apple Music could not find this item.")
            val tracks = librarySongs("/v1/me/library/albums/$id/tracks?limit=100&include=catalog")
            DetailData(DetailInfo(info.title, info.artist, info.artworkUrl, accentArgbFor(id), false, tracks.size, info.typeLabel), tracks)
        }
        // a library artist apple matched opens the catalogue page; the rest show the albums in the library
        kind == "artist" && id.startsWith("r.") -> {
            val a = api.get("/v1/me/library/artists/$id?include=catalog").data().firstOrNull() ?: throw FileNotFoundException("Apple Music could not find this item.")
            a.related("catalog").firstOrNull()?.string("id")?.takeIf { it.matches(CATALOG_ID) }?.let { detail("artist", it) } ?: run {
                val albums = library("/v1/me/library/artists/$id/albums?limit=100").mapNotNull(::album)
                DetailData(DetailInfo(a.obj("attributes").string("name"), "", albums.firstOrNull()?.artworkUrl.orEmpty(), accentArgbFor(id), true, 0, "Artist"),
                    emptyList(), albums)
            }
        }
        kind == "album" -> {
            val a = catalog("albums/$id?include[songs]=artists").data().firstOrNull() ?: throw FileNotFoundException("Apple Music could not find this item.")
            val info = album(a) ?: throw FileNotFoundException("Apple Music could not find this item.")
            val artistId = a.related("artists").firstOrNull()?.string("id").orEmpty()
            val tracks = remember(tracks(a).mapNotNull { song(it, album = info, artistId = artistId) })
            DetailData(DetailInfo(info.title, info.artist, info.artworkUrl, accentArgbFor(id), false, tracks.size, info.typeLabel,
                description = notes(a)), tracks)
        }
        kind == "playlist" -> {
            val p = catalog("playlists/$id?include[songs]=artists").data().firstOrNull() ?: throw FileNotFoundException("Apple Music could not find this item.")
            val info = playlist(p) ?: throw FileNotFoundException("Apple Music could not find this item.")
            val tracks = remember(tracks(p).mapNotNull { song(it) })
            DetailData(DetailInfo(info.title, info.subtitle, info.coverUrl, accentArgbFor(id), false, tracks.size, "Playlist",
                description = notes(p), editable = false), tracks)
        }
        kind == "artist" -> {
            val a = catalog("artists/$id?views=top-songs,full-albums,singles&limit[top-songs]=20&limit[full-albums]=50&limit[singles]=50")
                .data().firstOrNull() ?: throw FileNotFoundException("Apple Music could not find this item.")
            val tracks = remember(a.view("top-songs").mapNotNull { song(it, artistId = id) })
            val albums = (a.view("full-albums") + a.view("singles")).mapNotNull(::album).distinctBy { it.id }
            val attributes = a.obj("attributes")
            DetailData(DetailInfo(attributes.string("name"), attributes.array("genreNames").firstOrNull()?.asString.orEmpty(),
                art(attributes.obj("artwork").string("url")), accentArgbFor(id), true, tracks.size, "Artist", description = notes(a)), tracks, albums)
        }
        else -> null
    }

    // an album or playlist lists its first hundred tracks and links the rest page by page
    private suspend fun tracks(resource: JsonObject): List<JsonObject> {
        val relation = resource.obj("relationships").obj("tracks")
        val out = relation.items("data").toMutableList()
        var next = relation.string("next")
        var pages = 0
        while (next.startsWith("/v1/") && pages++ < MAX_PAGES) {
            val page = api.get(next)
            out += page.items("data")
            next = page.string("next")
        }
        return out
    }

    // the web player favourites through one endpoint for every kind; a library item goes by its library type
    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean {
        device()?.let { d ->
            if (kind == "song") return d.setStarred(session.type, id, starred, kind, ::songFor)
            // the app keeps playlist likes itself, and a playlist on this device has nothing to favourite
            if (!onDevice(id)) d.setCollectionLiked(session.type, kind, id, starred)
            return true
        }
        if (!subscribed()) return false
        change(if (starred) "POST" else "DELETE", "/v1/me/favorites?ids[${resourceType(kind, id)}]=$id")
        return true
    }

    private suspend fun change(method: String, path: String, body: JsonObject? = null): JsonObject =
        api.send(method, path, body).also { pageCache.clear() }

    private fun songRefs(ids: List<String>) = com.google.gson.JsonArray().apply {
        ids.distinct().forEach { id -> add(JsonObject().apply { addProperty("id", id); addProperty("type", resourceType("song", id)) }) }
    }

    // only playlists the account made take new tracks
    override suspend fun playlistsForSong(songId: String): List<Playlist> =
        devicePlaylists() + settled(library(PLAYLISTS).filter { it.obj("attributes").bool("canEdit") && !isFavorites(it) }.mapNotNull(::playlist))

    override suspend fun createPlaylist(name: String): Boolean = createPlaylistWithId(name) != null
    override suspend fun createPlaylistWithId(name: String): String? = createPlaylistWithId(name, emptyList())
    override suspend fun createPlaylistWithId(name: String, trackIds: List<String>): String? {
        if (name.isBlank()) return null
        device()?.let { return it.createPlaylist(session.type, name, trackIds.mapNotNull { id -> songFor(id) }) }
        if (!subscribed()) return null
        val body = JsonObject().apply {
            add("attributes", JsonObject().apply { addProperty("name", name.trim()); addProperty("description", "") })
            if (trackIds.isNotEmpty()) add("relationships", JsonObject().apply { add("tracks", JsonObject().apply { add("data", songRefs(trackIds)) }) })
        }
        val id = change("POST", "/v1/me/library/playlists", body).data().firstOrNull()?.string("id")?.takeIf { it.isNotBlank() } ?: return null
        createdPlaylists[id] = System.nanoTime() to Playlist(id, name.trim(), "Apple Music", "", trackIds.distinct().size, accentArgbFor(id))
        return id
    }

    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean {
        if (name == null && comment == null) return true
        if (onDevice(id)) return guestLikes?.changePlaylist(session.type, id) { p ->
            p.copy(name = name?.trim()?.ifBlank { null } ?: p.name, description = comment ?: p.description)
        } == true
        if (!id.startsWith("p.") || !subscribed()) return false
        val attributes = JsonObject().apply {
            name?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("name", it) }
            comment?.let { addProperty("description", it) }
        }
        change("PATCH", "/v1/me/library/playlists/$id", JsonObject().apply { add("attributes", attributes) })
        return true
    }

    override suspend fun deletePlaylist(id: String): Boolean {
        if (onDevice(id)) return guestLikes?.changePlaylist(session.type, id) { null } == true
        if (!id.startsWith("p.") || !subscribed()) return false
        change("DELETE", "/v1/me/library/playlists/$id")
        createdPlaylists.remove(id)
        deletedPlaylists[id] = System.nanoTime()
        return true
    }

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        if (trackIds.isEmpty()) return true
        // songs are looked up here rather than taken from the caller, whose copies a merge has namespaced
        if (onDevice(playlistId)) return guestLikes?.addToPlaylist(session.type, playlistId, trackIds.mapNotNull { songFor(it) }) == true
        if (!playlistId.startsWith("p.") || !subscribed()) return false
        change("POST", "/v1/me/library/playlists/$playlistId/tracks?representation=resources", JsonObject().apply { add("data", songRefs(trackIds)) })
        return true
    }

    // apple removes a playlist's tracks by their library ids, every occurrence at once; a removal that matched nothing isn't reported as done
    override suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        if (onDevice(playlistId)) {
            val gone = trackIds.toSet()
            return guestLikes?.changePlaylist(session.type, playlistId) { p -> p.copy(songs = p.songs.orEmpty().filterNot { it.id in gone }) } == true
        }
        if (!playlistId.startsWith("p.") || !subscribed()) return false
        val path = "/v1/me/library/playlists/$playlistId/tracks?limit=100&include=catalog"
        pageCache.remove(path)
        val wanted = trackIds.toSet()
        val entries = library(path).filter { librarySong(it)?.id in wanted || it.string("id") in wanted }.map { it.string("id") }.distinct()
        if (entries.isEmpty()) return false
        entries.forEach { change("DELETE", "/v1/me/library/playlists/$playlistId/tracks?ids[library-songs]=$it&mode=all") }
        return true
    }

    // word-timed lyrics first, then line-timed; a song apple has none for answers 404
    override suspend fun serverLyrics(song: Song): Lyrics? {
        if (!song.id.matches(CATALOG_ID) || !subscribed()) return null
        for (kind in listOf("syllable-lyrics", "lyrics")) {
            val ttml = try { catalog("songs/${song.id}/$kind").data().firstOrNull()?.obj("attributes")?.string("ttml") } catch (_: FileNotFoundException) { null }
            ttml?.takeIf { it.isNotBlank() }?.let(::lyrics)?.let { return it }
        }
        return null
    }

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String = songs[songId]?.streamUrl ?: "aurora-yt://am$songId"

    override val linksSongs: Boolean get() = true
    // a library-only upload has no catalogue page
    override suspend fun songLink(song: Song): String? = song.id.takeIf { it.matches(CATALOG_ID) }?.let { "https://music.apple.com/${api.storefront()}/song/$it" }
    override fun coverArtUrl(id: String, size: Int): String = songs[id]?.artworkUrl.orEmpty()

    companion object {
        private val CATALOG_ID = Regex("[0-9]+")
        private val LIBRARY_SONG = Regex("""i\.[A-Za-z0-9]+""")
        private const val MAX_PAGES = 50
        private const val MAX_LIBRARY = 5_000
        private const val CACHE_NS = 60_000_000_000L
        private const val PENDING_NS = 120_000_000_000L
        // apple leaves a playlist's tags out unless asked, and favorite songs is only known by its tag
        private const val PLAYLISTS = "/v1/me/library/playlists?limit=100&extend=tags"
        private val RELEASE_SUFFIX = Regex("""\s+-\s+(Single|EP)$""")

        private fun enc(text: String) = URLEncoder.encode(text.trim(), "UTF-8")

        // apple's art urls carry the size, crop and format as placeholders
        fun art(url: String, size: Int = 600): String = url.replace("{w}", "$size").replace("{h}", "$size").replace("{c}", "bb").replace("{f}", "jpg")

        private fun sentinel(id: String, title: String, artist: String, durSec: Int): String =
            "aurora-yt://am$id?q=${URLEncoder.encode("$title $artist".trim(), "UTF-8")}&dur=$durSec"

        // a library song apple matched to its catalogue keeps the catalogue id, so it plays, links and likes like any other
        fun song(o: JsonObject, album: Album? = null, artistId: String = ""): Song? {
            val a = o.obj("attributes")
            val title = a.string("name")
            val type = o.string("type")
            val id = if (type == "library-songs") a.obj("playParams").string("catalogId").ifBlank { o.string("id") } else o.string("id")
            if ((type != "songs" && type != "library-songs") || !(id.matches(CATALOG_ID) || id.matches(LIBRARY_SONG)) || title.isBlank()) return null
            val artist = a.string("artistName")
            val duration = (a.string("durationInMillis").toLongOrNull() ?: 0L).toInt() / 1000
            // a song's page link ends in its album id, the only album link playlist tracks carry
            val albumId = o.related("albums").firstOrNull()?.string("id")
                ?: album?.id ?: a.string("url").substringBefore('?').substringAfterLast('/').takeIf { it.matches(CATALOG_ID) }.orEmpty()
            return Song(id, title, artist, album?.title ?: RELEASE_SUFFIX.replace(a.string("albumName"), ""), art(a.obj("artwork").string("url")), duration,
                explicit = a.string("contentRating") == "explicit", accentArgb = accentArgbFor(id),
                streamUrl = sentinel(id, title, artist, duration), albumId = albumId,
                artistId = o.related("artists").firstOrNull()?.string("id") ?: artistId,
                genre = a.array("genreNames").map { it.asString }.firstOrNull { it != "Music" }.orEmpty())
        }

        fun resourceType(kind: String, id: String): String {
            val base = when (kind) { "album" -> "albums"; "artist" -> "artists"; "playlist" -> "playlists"; else -> "songs" }
            return if (id.length > 2 && id[1] == '.' && id[0] in "ilrp") "library-$base" else base
        }

        fun librarySong(o: JsonObject): Song? = o.related("catalog").firstNotNullOfOrNull { song(it) } ?: song(o)
        fun libraryAlbum(o: JsonObject): Album? = o.related("catalog").firstNotNullOfOrNull(::album) ?: album(o)
        fun libraryArtist(o: JsonObject): Artist? = o.related("catalog").firstNotNullOfOrNull(::artist) ?: artist(o)
        fun isFavorites(o: JsonObject) = o.obj("attributes").array("tags").any { it.isJsonPrimitive && it.asString == "favorited" }

        fun album(o: JsonObject): Album? {
            val id = o.string("id")
            val a = o.obj("attributes")
            val name = a.string("name")
            if ((o.string("type") != "albums" && o.string("type") != "library-albums") || id.isBlank() || name.isBlank()) return null
            val kind = when {
                a.bool("isSingle") || name.endsWith(" - Single") -> "single"
                name.endsWith(" - EP") -> "ep"
                a.bool("isCompilation") -> "compilation"
                else -> ""
            }
            return Album(id, RELEASE_SUFFIX.replace(name, ""), a.string("artistName"), art(a.obj("artwork").string("url")),
                a.string("releaseDate").take(4).toIntOrNull() ?: 0, a.string("trackCount").toIntOrNull() ?: 0, releaseType = kind)
        }

        fun artist(o: JsonObject): Artist? {
            val id = o.string("id")
            val name = o.obj("attributes").string("name")
            if ((o.string("type") != "artists" && o.string("type") != "library-artists") || id.isBlank() || name.isBlank()) return null
            return Artist(id, name, art(o.obj("attributes").obj("artwork").string("url")), 0)
        }

        fun playlist(o: JsonObject): Playlist? {
            val id = o.string("id")
            val a = o.obj("attributes")
            if ((o.string("type") != "playlists" && o.string("type") != "library-playlists") || id.isBlank() || a.string("name").isBlank()) return null
            return Playlist(id, a.string("name"), a.string("curatorName").ifBlank { "Apple Music" }, art(a.obj("artwork").string("url")),
                a.string("trackCount").toIntOrNull() ?: 0, accentArgbFor(id))
        }

        private fun item(o: JsonObject): HomeFeedItem? = when (o.string("type")) {
            "songs", "library-songs" -> librarySong(o)?.let(HomeFeedItem::Track)
            "albums", "library-albums" -> libraryAlbum(o)?.let(HomeFeedItem::Record)
            "playlists", "library-playlists" -> playlist(o)?.let(HomeFeedItem::Collection)
            "artists", "library-artists" -> libraryArtist(o)?.let(HomeFeedItem::Performer)
            else -> null
        }

        private fun shelf(id: String, title: String, items: List<JsonObject>): HomeFeedSection? =
            items.mapNotNull(::item).distinctBy { it.key }.takeIf { title.isNotBlank() && it.isNotEmpty() }?.let { HomeFeedSection(id, title, items = it) }

        // the browse page's shelves; banners nest more elements instead of holding music and are skipped
        fun browseShelves(root: JsonObject): List<HomeFeedSection> =
            root.data().firstOrNull()?.related("tabs")?.firstOrNull()?.related("children").orEmpty().mapNotNull { element ->
                shelf("am-${element.string("id")}", element.obj("attributes").string("name"), element.related("contents"))
            }

        fun chartShelves(root: JsonObject): List<HomeFeedSection> = listOf("songs", "albums", "playlists").flatMap { type ->
            root.obj("results").items(type).mapNotNull { chart -> shelf("am-chart-$type", chart.string("name"), chart.items("data")) }
        }

        private val LINE = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        private val WORD = Regex("""<span\b([^>]*\bbegin="[^"]*"[^>]*)>([^<]*)</span>(\s*)""")
        private val TAG = Regex("<[^>]+>")
        private fun attribute(attributes: String, name: String) = Regex("""(?<![\w:])$name="([^"]*)"""").find(attributes)?.groupValues?.get(1).orEmpty()
        private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")

        // ttml clock values: 12.345, 1:02.345 or 1:02:03.456, sometimes with an s
        fun clock(value: String): Long? {
            val parts = value.trim().removeSuffix("s").takeIf { it.isNotEmpty() }?.split(':') ?: return null
            val seconds = parts.last().toDoubleOrNull() ?: return null
            val minutes = parts.dropLast(1).fold(0L) { total, part -> total * 60 + (part.toLongOrNull() ?: return null) }
            return (minutes * 60_000 + seconds * 1000).roundToLong()
        }

        // apple's lyrics are ttml: a <p> per line, and a timed <span> per word when the song is word-synced
        fun lyrics(ttml: String): Lyrics? {
            val lines = LINE.findAll(ttml).map { p ->
                val body = p.groupValues[2]
                val words = WORD.findAll(body).mapNotNull { span ->
                    val start = clock(attribute(span.groupValues[1], "begin")) ?: return@mapNotNull null
                    LyricWord(start, clock(attribute(span.groupValues[1], "end")) ?: start,
                        unescape(span.groupValues[2]) + if (span.groupValues[3].isNotEmpty()) " " else "")
                }.toList()
                Triple(clock(attribute(p.groupValues[1], "begin")), unescape(body.replace(TAG, "")).replace(Regex("\\s+"), " ").trim(), words)
            }.filter { it.second.isNotBlank() }.toList()
            if (lines.isEmpty()) return null
            val synced = lines.all { it.first != null }
            return Lyrics(lines.map { (begin, text, words) -> if (synced) LyricLine((begin!! / 1000).toInt(), text, begin, words) else LyricLine(-1, text) },
                synced, "Apple Music")
        }

        // checks the login and finds the account's storefront; apple names the account only when it has a social profile
        suspend fun profile(api: AppleMusicTransport, login: AppleMusicWebSession): AppleMusicProfile {
            val storefront = api.get("/v1/me/storefront").data().firstOrNull()?.string("id")?.lowercase()
                ?.takeIf { it.matches(AppleMusicWebSession.STOREFRONT) } ?: login.storefront
            val social = runCatching { api.get("/v1/me/social/profile").data().firstOrNull() }.getOrNull()
            val attributes = social?.obj("attributes") ?: JsonObject()
            return AppleMusicProfile(social?.string("id")?.takeIf { it.isNotBlank() } ?: login.fingerprint,
                attributes.string("name").ifBlank { attributes.string("handle") }.ifBlank { "Apple Music" },
                art(attributes.obj("artwork").string("url"), 300), storefront)
        }

        private fun notes(o: JsonObject): String {
            val notes = o.obj("attributes").obj("editorialNotes").let { it.string("standard").ifBlank { it.string("short") } }
                .ifBlank { o.obj("attributes").obj("description").let { it.string("standard").ifBlank { it.string("short") } } }
            return notes.replace(Regex("<[^>]+>"), "").trim()
        }

        private fun JsonObject.items(key: String) = array(key).filter { it.isJsonObject }.map { it.asJsonObject }
        private fun JsonObject.data() = items("data")
        private fun JsonObject.data(type: String) = obj(type).items("data")
        private fun JsonObject.related(key: String) = obj("relationships").obj(key).items("data")
        private fun JsonObject.view(key: String) = obj("views").obj(key).items("data")
        private fun JsonObject.bool(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
    }
}
