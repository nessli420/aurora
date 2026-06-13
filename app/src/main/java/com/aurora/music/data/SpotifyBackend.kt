package com.aurora.music.data

import com.aurora.music.data.remote.AddTracksBody
import com.aurora.music.data.remote.ChangeDetailsBody
import com.aurora.music.data.remote.CreatePlaylistBody
import com.aurora.music.data.remote.RemoveTracksBody
import com.aurora.music.data.remote.SpAlbum
import com.aurora.music.data.remote.SpAlbumRef
import com.aurora.music.data.remote.SpArtist
import com.aurora.music.data.remote.SpPlaylist
import com.aurora.music.data.remote.SpTrack
import com.aurora.music.data.remote.SpotifyClient
import com.aurora.music.data.remote.TrackUri
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import android.util.Log
import com.aurora.music.data.remote.SpPlaylistItem
import com.aurora.music.data.remote.SpSavedTrack
import com.aurora.music.data.remote.SpPaging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Spotify-backed [MediaBackend]: library + metadata come from the Spotify Web API; playback URLs are
 * sentinel `aurora-yt://` URIs resolved to a real YouTube audio stream at play time (see the
 * ResolvingDataSource in PlaybackService). Writes (likes/playlists/follows) sync to Spotify.
 */
class SpotifyBackend(
    private val c: SpotifyClient,
    private val maxBitrateProvider: () -> Int,
    private val localize: (Song) -> Song,
) : MediaBackend {

    private val api = c.api
    override val session: Session = c.session

    // Liked songs are large + paginated; cache the full list (invalidated on like/unlike).
    @Volatile private var likedCache: List<Song>? = null

    // Plain client for scraping the public embed page (no auth) when the Web API forbids non-owned playlists.
    private val embedHttp = OkHttpClient()

    // The user's market (ISO country). REQUIRED by several endpoints (artist top-tracks, search
    // relinking); passing the wrong/none yields empty results. Resolved once from /me and cached.
    @Volatile private var marketCache: String? = null
    private suspend fun market(): String {
        marketCache?.let { return it }
        val m = runCatching { api.me().country }.getOrNull()?.takeIf { it.isNotBlank() } ?: "US"
        marketCache = m
        return m
    }

    // Album header art/name, cached when the album detail loads, so paginated album tracks (which
    // carry no album object) still get the right cover.
    private val albumMeta = HashMap<String, Pair<String, String>>() // albumId -> (artUrl, name)

    /**
     * Fallback for playlists NOT owned by the user: Spotify's API 403s their tracks for new apps.
     * The public embed page carries only track ids (no album/art), so we scrape the ordered id list
     * and enrich it with full track objects via /v1/tracks (batched, ≤50 ids) — yielding real
     * per-track album art + names, rate-limit-safely.
     */
    /**
     * Fallback for playlists NOT owned by the user: Spotify's API 403s their tracks (and even
     * /v1/tracks) for new apps. The public embed page carries title/artist/duration (no per-track
     * album art), so we build tracks from it directly — art falls back to the playlist cover, and
     * the playlist name becomes the album so the player shows "Playing from <playlist>". They still
     * play (resolved by name+artist via YouTube).
     */
    private suspend fun fetchEmbedTracks(id: String): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://open.spotify.com/embed/playlist/$id")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            val html = embedHttp.newCall(req).execute().use { it.body?.string() } ?: return@runCatching emptyList()
            val script = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return@runCatching emptyList()
            val entity = JSONObject(script).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
            val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url").orEmpty()
            val plName = entity.optString("name").ifBlank { entity.optString("title") }
            val list = entity.optJSONArray("trackList") ?: return@runCatching emptyList()
            (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val uri = t.optString("uri")
                if (!uri.startsWith("spotify:track:")) return@mapNotNull null
                val tid = uri.substringAfterLast(":")
                val title = t.optString("title")
                val artist = t.optString("subtitle").ifBlank { "Unknown artist" }
                val durSec = (t.optLong("duration") / 1000).toInt()
                if (title.isBlank()) return@mapNotNull null
                localize(Song(
                    id = tid, title = title, artist = artist, album = plName, artworkUrl = cover,
                    durationSec = durSec, accent = accentFor(tid), streamUrl = sentinel(tid, title, artist, durSec),
                ))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Load liked songs GENTLY — sequential with a small delay and a [LIKED_CAP] limit. Paging
     * thousands of saved tracks in a burst trips Spotify's rate limit (429) and breaks the whole
     * session, so we cap the in-app list; the true total still shows via [starredCount].
     */
    private suspend fun loadAllLiked(): List<Song> {
        val out = ArrayList<Song>()
        var offset = 0
        while (offset < LIKED_CAP) {
            val page = runCatching { api.savedTracks(limit = 50, offset = offset) }.getOrNull() ?: break
            val items = page.items.orEmpty()
            out += items.mapNotNull { it.track?.toSong() }
            if (page.next == null || items.isEmpty()) break
            offset += 50
            delay(150)
        }
        return out
    }

    private companion object { const val LIKED_CAP = 10000; const val PAGE = 50 }


    // ---- mapping helpers ----

    private fun img(list: List<com.aurora.music.data.remote.SpImage>?): String = list?.firstOrNull()?.url ?: ""

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1f".format(n / 1_000_000.0).removeSuffix(".0") + "M"
        n >= 1_000 -> "%.1f".format(n / 1_000.0).removeSuffix(".0") + "K"
        else -> n.toString()
    }

    private fun sentinel(id: String, title: String, artist: String, durSec: Int): String {
        if (id.isBlank()) return ""
        val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        return "aurora-yt://$id?q=$q&dur=$durSec"
    }

    private fun SpTrack.toSong(fallbackArt: String = "", fallbackAlbum: String = "", fallbackAlbumId: String = ""): Song {
        val artistName = artists?.mapNotNull { it.name }?.joinToString(", ")?.ifBlank { null } ?: "Unknown artist"
        val art = img(album?.images).ifBlank { fallbackArt }
        val durSec = (durationMs / 1000).toInt()
        val sid = id ?: ""
        val song = Song(
            id = sid,
            title = name ?: "",
            artist = artistName,
            album = album?.name ?: fallbackAlbum,
            artworkUrl = art,
            durationSec = durSec,
            explicit = explicit,
            accent = accentFor(sid),
            streamUrl = sentinel(sid, name ?: "", artistName, durSec),
            albumId = album?.id ?: fallbackAlbumId,
            artistId = artists?.firstOrNull()?.id ?: "",
        )
        return localize(song)
    }

    private fun SpAlbumRef.toAlbum() = Album(
        id = id ?: "",
        title = name ?: "",
        artist = artists?.mapNotNull { it.name }?.joinToString(", ") ?: "",
        artworkUrl = img(images),
        year = releaseDate?.take(4)?.toIntOrNull() ?: 0,
        songCount = totalTracks,
    )

    private fun SpAlbum.toAlbum() = Album(
        id = id ?: "",
        title = name ?: "",
        artist = artists?.mapNotNull { it.name }?.joinToString(", ") ?: "",
        artworkUrl = img(images),
        year = releaseDate?.take(4)?.toIntOrNull() ?: 0,
        songCount = totalTracks,
    )

    private fun SpArtist.toArtist() = Artist(
        id = id ?: "",
        name = name ?: "",
        imageUrl = img(images),
        monthlyListeners = followers?.total ?: 0,
    )

    private fun SpPlaylist.toPlaylist() = Playlist(
        id = id ?: "",
        title = name ?: "",
        subtitle = owner?.displayName?.let { "by $it" } ?: (description ?: ""),
        coverUrl = img(images),
        songCount = items?.total ?: tracks?.total ?: 0,
        accent = accentFor(id ?: ""),
    )

    // ---- reads ----

    override suspend fun ping(): Boolean = runCatching { api.me(); true }.getOrDefault(false)

    override suspend fun home(): HomeData = coroutineScope {
        val newRel = async { runCatching { api.newReleases().albums?.items?.map { it.toAlbum() } }.getOrNull().orEmpty() }
        val recent = async {
            runCatching {
                api.recentlyPlayed().items?.mapNotNull { it.track?.album }?.distinctBy { it.id }?.map { it.toAlbum() }
            }.getOrNull().orEmpty()
        }
        val top = async {
            runCatching { api.topTracks(limit = 30).items?.mapNotNull { it.album }?.distinctBy { it.id }?.map { it.toAlbum() } }.getOrNull().orEmpty()
        }
        val playlists = async { runCatching { api.myPlaylists().items?.map { it.toPlaylist() } }.getOrNull().orEmpty() }
        val artists = async {
            runCatching { api.followedArtists().artists?.items?.map { it.toArtist() } }.getOrNull()?.ifEmpty { null }
                ?: runCatching { api.topArtists().items?.map { it.toArtist() } }.getOrNull().orEmpty()
        }
        val starred = async { runCatching { api.savedTracks(limit = 30).items?.mapNotNull { it.track?.toSong() } }.getOrNull().orEmpty() }
        HomeData(
            newReleases = newRel.await(),
            recentlyPlayed = recent.await(),
            mostPlayed = top.await(),
            playlists = playlists.await(),
            artists = artists.await(),
            starred = starred.await(),
        )
    }

    override suspend fun allAlbums(): List<Album> =
        runCatching { api.savedAlbums().items?.mapNotNull { it.album?.toAlbum() } }.getOrNull().orEmpty()

    override suspend fun allArtists(): List<Artist> =
        runCatching { api.followedArtists().artists?.items?.map { it.toArtist() } }.getOrNull().orEmpty()

    override suspend fun allPlaylists(): List<Playlist> =
        runCatching { api.myPlaylists().items?.map { it.toPlaylist() } }.getOrNull().orEmpty()

    // "All songs" for Spotify = a fast preview of liked tracks (the full list lives in Liked Songs).
    override suspend fun allSongs(): List<Song> = runCatching {
        val a = api.savedTracks(limit = 50, offset = 0).items.orEmpty()
        val b = runCatching { api.savedTracks(limit = 50, offset = 50).items }.getOrNull().orEmpty()
        (a + b).mapNotNull { it.track?.toSong() }
    }.getOrDefault(emptyList())

    override suspend fun starredSongs(): List<Song> {
        likedCache?.let { return it }
        val all = loadAllLiked()
        if (all.isNotEmpty()) likedCache = all
        return all
    }

    override suspend fun starredCount(): Int = runCatching { api.savedTracks(limit = 1).total }.getOrDefault(0)

    override suspend fun starredIds(): Set<String> = coroutineScope {
        val tracks = async { runCatching { api.savedTracks(limit = 50).items?.mapNotNull { it.track?.id } }.getOrNull().orEmpty() }
        val albums = async { runCatching { api.savedAlbums(limit = 50).items?.mapNotNull { it.album?.id } }.getOrNull().orEmpty() }
        val artists = async { runCatching { api.followedArtists().artists?.items?.mapNotNull { it.id } }.getOrNull().orEmpty() }
        (tracks.await() + albums.await() + artists.await()).toSet()
    }

    override suspend fun songFor(id: String): Song? = runCatching { api.track(id).toSong() }.getOrNull()

    override suspend fun likedSongIds(ids: List<String>): Set<String> {
        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (chunk in clean.chunked(50)) {
            val res = runCatching { api.tracksContains(chunk.joinToString(",")) }.getOrNull() ?: continue
            chunk.forEachIndexed { i, id -> if (res.getOrNull(i) == true) out += id }
        }
        return out
    }

    override suspend fun profileImageUrl(): String =
        runCatching { api.me().images?.firstOrNull()?.url }.getOrNull().orEmpty()

    override suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isBlank()) return SearchResults()
        return runCatching {
            val r = api.search(q, market = market())
            val res = SearchResults(
                songs = r.tracks?.items?.mapNotNull { it?.takeIf { t -> t.id != null }?.toSong() }.orEmpty(),
                albums = r.albums?.items?.mapNotNull { it?.takeIf { a -> a.id != null }?.toAlbum() }.orEmpty(),
                artists = r.artists?.items?.mapNotNull { it?.takeIf { a -> a.id != null }?.toArtist() }.orEmpty(),
                playlists = r.playlists?.items?.mapNotNull { it?.takeIf { p -> p.id != null }?.toPlaylist() }.orEmpty(),
            )
            Log.d("SpotifyBE", "search '$q' → songs=${res.songs.size} albums=${res.albums.size} artists=${res.artists.size} playlists=${res.playlists.size}")
            res
        }.getOrElse {
            Log.d("SpotifyBE", "search '$q' failed: ${it.message}")
            SearchResults()
        }
    }

    override suspend fun scrobble(id: String) { /* Spotify playback isn't reported; Last.fm handles scrobbles. */ }

    override suspend fun radio(seedId: String): List<Song> =
        runCatching { api.recommendations(seedTracks = seedId).tracks?.map { it.toSong() } }.getOrNull().orEmpty()

    override suspend fun detail(kind: String, id: String): DetailData? = runCatching {
        when (kind) {
            "album" -> {
                val a = api.album(id)
                val art = img(a.images)
                albumMeta[id] = art to (a.name ?: "")
                val tracks = a.tracks?.items?.map { it.toSong(art, a.name ?: "", a.id ?: "") }.orEmpty()
                val total = a.tracks?.total?.takeIf { it > 0 } ?: a.totalTracks.takeIf { it > 0 } ?: tracks.size
                DetailData(
                    info = DetailInfo(a.name ?: "", a.artists?.mapNotNull { it.name }?.joinToString(", ") ?: "", art, accentFor(id), false, total, "Album"),
                    tracks = tracks,
                )
            }
            "artist" -> {
                val ar = api.artist(id)
                val mkt = market()
                val top = runCatching { api.artistTopTracks(id, mkt).tracks?.map { it.toSong() } }.getOrNull().orEmpty()
                val albums = runCatching { api.artistAlbums(id).items?.map { it.toAlbum() } }.getOrNull().orEmpty()
                Log.d("SpotifyBE", "artist $id (${ar.name}) market=$mkt → top=${top.size} albums=${albums.size}")
                val followers = ar.followers?.total ?: 0
                val sub = if (followers > 0) "${formatCount(followers)} followers" else "Artist"
                DetailData(
                    info = DetailInfo(ar.name ?: "", sub, img(ar.images), accentFor(id), true, top.size, "Artist"),
                    tracks = top,
                    albums = albums,
                )
            }
            "playlist" -> {
                // Header from /playlists/{id}; first track page from /playlists/{id}/items (works
                // for owned AND followed/public playlists, and paginates uncapped via detailPage).
                val header = runCatching { api.playlist(id) }.getOrNull()
                val sub = header?.owner?.displayName?.let { "by $it" } ?: ""
                val apiPage = runCatching { api.playlistItems(id, 0, PAGE) }.getOrNull()
                val apiTracks = apiPage?.items?.mapNotNull { (it.item ?: it.track)?.toSong() }.orEmpty()
                if (apiTracks.isNotEmpty()) {
                    // Owned/accessible playlist — paginate via the API (detailPage).
                    DetailData(
                        info = DetailInfo(header?.name ?: "Playlist", sub, img(header?.images), accentFor(id), false, apiPage?.total ?: apiTracks.size, "Playlist"),
                        tracks = apiTracks,
                    )
                } else {
                    // Not owned by us → API forbids the tracks; scrape the public embed (all at once).
                    val embed = fetchEmbedTracks(id)
                    Log.d("SpotifyBE", "playlist $id non-owned → embed tracks=${embed.size}")
                    DetailData(
                        info = DetailInfo(header?.name ?: "Playlist", sub, img(header?.images), accentFor(id), false, embed.size, "Playlist"),
                        tracks = embed,
                    )
                }
            }
            "liked" -> {
                val page = api.savedTracks(limit = PAGE, offset = 0)
                val tracks = page.items?.mapNotNull { it.track?.toSong() }.orEmpty()
                DetailData(
                    info = DetailInfo("Liked Songs", "${page.total} songs", tracks.firstOrNull()?.artworkUrl ?: "", accentFor("liked"), false, page.total, "Liked"),
                    tracks = tracks,
                )
            }
            else -> null
        }
    }.getOrNull()

    override suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> = runCatching {
        when (kind) {
            "liked" -> api.savedTracks(limit = PAGE, offset = offset).items?.mapNotNull { it.track?.toSong() }.orEmpty()
            "playlist" -> api.playlistItems(id, offset, PAGE).items?.mapNotNull { (it.item ?: it.track)?.toSong() }.orEmpty()
            "album" -> {
                val meta = albumMeta[id]
                api.albumTracks(id, offset, PAGE).items?.map { it.toSong(meta?.first ?: "", meta?.second ?: "", id) }.orEmpty()
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    // ---- writes ----

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = runCatching {
        when (kind) {
            "album" -> if (starred) api.saveAlbums(id) else api.removeAlbums(id)
            "artist" -> if (starred) api.followArtists("artist", id) else api.unfollowArtists("artist", id)
            "playlist" -> if (starred) api.followPlaylist(id) else api.unfollowPlaylist(id)
            else -> {
                likedCache = null // liked set changed → invalidate the cached list
                if (starred) api.saveTracks(id) else api.removeTracks(id)
            }
        }.isSuccessful
    }.getOrDefault(false)

    override suspend fun createPlaylist(name: String): Boolean =
        runCatching { api.createPlaylist(session.userId, CreatePlaylistBody(name)).id != null }.getOrDefault(false)

    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean =
        runCatching { api.changePlaylistDetails(id, ChangeDetailsBody(name, comment)).isSuccessful }.getOrDefault(false)

    override suspend fun deletePlaylist(id: String): Boolean =
        runCatching { api.unfollowPlaylist(id).isSuccessful }.getOrDefault(false)

    override suspend fun createPlaylistWithId(name: String): String? =
        runCatching { api.createPlaylist(session.userId, CreatePlaylistBody(name)).id }.getOrNull()

    /** Add/remove tracks to a playlist (Spotify URIs). Used by P3 queue/playlist editing. */
    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean =
        runCatching { api.addToPlaylist(playlistId, AddTracksBody(trackIds.map { "spotify:track:$it" })).isSuccessful }.getOrDefault(false)

    suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean =
        runCatching { api.removeFromPlaylist(playlistId, RemoveTracksBody(trackIds.map { TrackUri("spotify:track:$it") })).isSuccessful }.getOrDefault(false)

    override suspend fun serverLyrics(song: Song): Lyrics? = null

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String = "aurora-yt://$songId"

    override fun coverArtUrl(id: String, size: Int): String = ""
}
