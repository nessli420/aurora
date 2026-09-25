package com.aurora.music.data

import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.PlexException
import com.aurora.music.data.remote.PlexMetadata
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.model.inferReleaseType
import com.aurora.music.model.releaseTypeLabel
import com.aurora.music.util.accentFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class PlexBackend(
    private val client: PlexClient,
    private val maxBitrateProvider: () -> Int,
    private val localize: (Song) -> Song,
) : MediaBackend {
    override val session: Session get() = client.session

    private companion object {
        const val ARTIST = 8
        const val ALBUM = 9
        const val TRACK = 10
        const val PROVIDER = "com.plexapp.plugins.library"
        // four or five stars count as liked
        const val LIKE_THRESHOLD = 8f
        val LIKED = mapOf("userRating>" to "8")
    }

    private val PlexMetadata.id: String get() = ratingKey.orEmpty()
    private val PlexMetadata.liked: Boolean get() = (userRating ?: 0f) >= LIKE_THRESHOLD
    private fun List<PlexMetadata>.ofType(type: String) = filter { it.type == type && it.id.isNotBlank() }

    private fun PlexMetadata.toSong(): Song {
        client.remember(this)
        val media = media.orEmpty().firstOrNull()
        val part = media?.parts.orEmpty().firstOrNull()
        val stream = part?.streams.orEmpty().firstOrNull { it.streamType == 2 }
        val bitrate = maxBitrateProvider()
        val albumId = parentRatingKey.orEmpty()
        return localize(Song(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: "Unknown track",
            artist = originalTitle?.takeIf { it.isNotBlank() }
                ?: grandparentTitle?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            album = parentTitle.orEmpty(),
            artworkUrl = client.coverArtUrl(id),
            durationSec = ((duration ?: 0L) / 1000L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            liked = liked,
            accent = accentFor(id),
            streamUrl = client.streamUrl(id, bitrate, bitrate <= 0),
            albumId = albumId,
            artistId = grandparentRatingKey.orEmpty(),
            suffix = media?.container ?: part?.container ?: media?.audioCodec.orEmpty(),
            bitrateKbps = media?.bitrate ?: 0,
            sampleRateHz = stream?.samplingRate ?: 0,
            bitDepth = stream?.bitDepth ?: 0,
            replayGainTrack = stream?.gain ?: 0f,
            replayGainAlbum = stream?.albumGain ?: 0f,
            path = part?.file.orEmpty(),
            genre = genres.orEmpty().firstOrNull()?.tag.orEmpty(),
            playCount = viewCount ?: 0,
            dateAddedSec = addedAt ?: 0L,
            playbackSource = PlaybackSourceIdentity.fromSession(session, albumId),
        ))
    }

    private fun PlexMetadata.toAlbum(): Album {
        client.remember(this)
        return Album(
            id = id,
            title = title ?: "Album",
            artist = parentTitle ?: "Unknown artist",
            artworkUrl = client.coverArtUrl(id),
            year = year ?: 0,
            songCount = leafCount ?: 0,
            durationSec = ((duration ?: 0L) / 1000L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            playCount = viewCount ?: 0,
        )
    }

    private fun PlexMetadata.toArtist(): Artist {
        client.remember(this)
        return Artist(id, title ?: "Artist", client.coverArtUrl(id), 0)
    }

    private fun PlexMetadata.toPlaylist(): Playlist {
        client.remember(this)
        return Playlist(id, title ?: "Playlist", "${leafCount ?: 0} songs", client.coverArtUrl(id), leafCount ?: 0, accentFor(id))
    }

    private suspend fun library(
        type: Int,
        parameters: Map<String, String> = emptyMap(),
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
    ): List<PlexMetadata> {
        if (limit <= 0 || offset < 0) return emptyList()
        val expectedType = when (type) { ARTIST -> "artist"; ALBUM -> "album"; else -> "track" }
        val result = mutableListOf<PlexMetadata>()
        var skip = offset
        for (section in client.musicSections()) {
            val path = "/library/sections/${PlexClient.validId(section.key.orEmpty())}/all"
            val query = mapOf("type" to type.toString(), "sort" to "titleSort") + parameters
            val page = client.metadataWindow(path, query, limit - result.size, skip)
            if (skip > 0 && page.totalSize == null) {
                val requested = (skip.toLong() + limit - result.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val rows = client.allMetadata(path, query, requested).ofType(expectedType)
                val consumed = minOf(skip, rows.size)
                skip -= consumed
                result += rows.drop(consumed).take(limit - result.size)
            } else {
                result += page.metadata.orEmpty().ofType(expectedType)
                skip = (skip - (page.totalSize ?: skip)).coerceAtLeast(0)
            }
            if (result.size >= limit) break
        }
        return result
    }

    private suspend fun albumSample(parameters: Map<String, String>): List<PlexMetadata> =
        client.musicSections().flatMap { section ->
            client.allMetadata("/library/sections/${PlexClient.validId(section.key.orEmpty())}/all",
                mapOf("type" to ALBUM.toString()) + parameters, 12).ofType("album")
        }

    override suspend fun ping(): Boolean = safely { !client.serverInfo().machineIdentifier.isNullOrBlank() }

    override suspend fun home(): HomeData = coroutineScope {
        val newest = async { albumSample(mapOf("sort" to "addedAt:desc")).sortedByDescending { it.addedAt ?: 0 }.take(12).map { it.toAlbum() } }
        val recent = async { albumSample(mapOf("sort" to "lastViewedAt:desc", "viewCount>" to "1"))
            .sortedByDescending { it.lastViewedAt ?: 0 }.take(12).map { it.toAlbum() } }
        val frequent = async { albumSample(mapOf("sort" to "viewCount:desc", "viewCount>" to "1"))
            .sortedByDescending { it.viewCount ?: 0 }.take(12).map { it.toAlbum() } }
        val random = async { albumSample(mapOf("sort" to "random")).shuffled().take(12).map { it.toAlbum() } }
        val playlists = async { allPlaylists() }
        val artists = async { library(ARTIST, limit = 20).map { it.toArtist() } }
        val starred = async { library(TRACK, LIKED, limit = 50).filter { it.liked }.map { it.toSong() } }
        val recommendations = async { recommendationSections() }
        HomeData(
            newReleases = newest.await(),
            recentlyPlayed = recent.await(),
            mostPlayed = frequent.await(),
            random = random.await(),
            playlists = playlists.await(),
            artists = artists.await(),
            starred = starred.await(),
            sections = recommendations.await(),
        )
    }

    private suspend fun recommendationSections(): List<HomeFeedSection> = coroutineScope {
        client.musicSections().map { section -> async {
            val sectionId = PlexClient.validId(section.key.orEmpty())
            val recommendations = try {
                withTimeoutOrNull(5_000) { client.get("/hubs/sections/$sectionId", mapOf("count" to "12")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlexException) {
                if (e.statusCode == 401) throw e
                null
            } catch (_: Exception) {
                null
            }
            recommendations?.hubs.orEmpty().mapIndexedNotNull { index, hub ->
                val items = hub.metadata.orEmpty().filter { it.id.isNotBlank() }.mapNotNull { item ->
                    when (item.type) {
                        "track" -> item.toSong().takeIf { it.streamUrl.isNotBlank() }?.let { HomeFeedItem.Track(it) }
                        "album" -> HomeFeedItem.Record(item.toAlbum())
                        "artist" -> HomeFeedItem.Performer(item.toArtist())
                        "playlist" -> item.takeIf { it.playlistType == "audio" }?.let { HomeFeedItem.Collection(it.toPlaylist()) }
                        else -> null
                    }
                }.distinctBy { it.key }.take(12)
                if (items.isEmpty() || hub.title.isNullOrBlank()) null else HomeFeedSection(
                    id = "plex:$sectionId:${hub.hubIdentifier ?: index}", title = hub.title, subtitle = section.title.orEmpty(), items = items,
                )
            }
        } }.awaitAll().flatten()
    }

    override suspend fun allAlbums(): List<Album> = library(ALBUM).map { it.toAlbum() }
    override suspend fun allArtists(): List<Artist> = library(ARTIST).map { it.toArtist() }
    override suspend fun allSongs(): List<Song> = library(TRACK).map { it.toSong() }
    override suspend fun librarySongs(limit: Int): List<Song> = library(TRACK, limit = limit).map { it.toSong() }
    override suspend fun songsPage(offset: Int, count: Int): List<Song> = library(TRACK, offset = offset, limit = count).map { it.toSong() }

    override suspend fun allPlaylists(): List<Playlist> = client.allMetadata("/playlists", mapOf("playlistType" to "audio"))
        .ofType("playlist").filter { it.playlistType == "audio" }.map { it.toPlaylist() }

    override suspend fun playlistsForSong(songId: String): List<Playlist> =
        client.allMetadata("/playlists", mapOf("playlistType" to "audio", "smart" to "0"))
            .ofType("playlist").filter { it.playlistType == "audio" && !it.isSmart }.map { it.toPlaylist() }

    override suspend fun starredSongs(): List<Song> = library(TRACK, LIKED).filter { it.liked }.map { it.toSong() }
    override suspend fun starredCount(): Int = library(TRACK, LIKED).count { it.liked }
    override suspend fun starredIds(): Set<String> = listOf(ARTIST, ALBUM, TRACK)
        .flatMap { library(it, LIKED) }.filter { it.liked }.map { it.id }.toSet()

    override suspend fun likedSongIds(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val wanted = ids.toHashSet()
        return library(TRACK, LIKED).filter { it.id in wanted && it.liked }.map { it.id }.toSet()
    }

    override suspend fun songFor(id: String): Song? = client.metadata(id)?.takeIf { it.type == "track" }?.toSong()

    override suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val filter = mapOf("title" to query.trim())
        return SearchResults(
            songs = library(TRACK, filter, limit = 60).map { it.toSong() },
            albums = library(ALBUM, filter, limit = 40).map { it.toAlbum() },
            artists = library(ARTIST, filter, limit = 40).map { it.toArtist() },
            playlists = allPlaylists().filter { it.title.contains(query.trim(), ignoreCase = true) }.take(40),
        )
    }

    override suspend fun matchingSongs(song: Song): List<Song> =
        library(TRACK, mapOf("title" to recordingTitle(song.title)), limit = 200).map { it.toSong() }

    override suspend fun scrobble(id: String) {
        safely { client.mutate("PUT", "/:/scrobble", mapOf("key" to PlexClient.validId(id), "identifier" to PROVIDER)) }
    }

    override suspend fun reportPlayback(report: PlaybackReport) {
        // timeline updates own play counts and history
        if (report.event == PlaybackReportEvent.SCROBBLE) return
        val duration = report.durationMs.takeIf { it > 0 } ?: (report.song.durationSec.toLong() * 1000L).coerceAtLeast(0)
        client.timeline(
            id = report.song.id,
            sessionId = report.sessionId,
            state = when (report.state) {
                PlaybackReportState.PLAYING -> "playing"
                PlaybackReportState.PAUSED -> "paused"
                PlaybackReportState.BUFFERING -> "buffering"
                PlaybackReportState.STOPPED -> "stopped"
            },
            positionMs = report.positionMs,
            durationMs = duration,
            listenedMs = report.listenedMs,
        )
    }

    override fun prefersServerRadio(seedId: String): Boolean = true

    override suspend fun radio(seedId: String): List<Song> {
        val seed = client.metadata(seedId, parameters = mapOf("includeStations" to "1")) ?: return emptyList()
        stationTracks(seed, seedId).takeIf { it.isNotEmpty() }?.let { return it }
        if (seed.type == "track") {
            val nearest = optional { client.get("/library/metadata/${PlexClient.validId(seedId)}/nearest", mapOf("limit" to "31")) }
            radioTracks(nearest?.metadata.orEmpty(), seedId).takeIf { it.isNotEmpty() }?.let { return it }
            seed.grandparentRatingKey?.takeIf { it.isNotBlank() }?.let { artistId ->
                optional { client.metadata(artistId, parameters = mapOf("includeStations" to "1")) }?.let { artist ->
                    stationTracks(artist, seedId).takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        val candidates = client.musicSections().flatMap { section ->
            client.allMetadata("/library/sections/${PlexClient.validId(section.key.orEmpty())}/all",
                mapOf("type" to TRACK.toString(), "sort" to "random"), 31)
        }
        return radioTracks(candidates.shuffled(), seedId)
    }

    private fun radioTracks(items: List<PlexMetadata>, seedId: String): List<Song> = items.ofType("track")
        .filter { it.id != seedId }.distinctBy { it.id }.map { it.toSong() }.filter { it.streamUrl.isNotBlank() }.take(30)

    private suspend fun stationTracks(item: PlexMetadata, seedId: String): List<Song> {
        for (station in item.stations?.metadata.orEmpty()) {
            if (station.playlistType != null && station.playlistType != "audio") continue
            val key = station.key ?: continue
            val uri = try { client.sourceUri(key) } catch (_: IllegalArgumentException) { continue }
            val queue = optional { client.post("/playQueues", mapOf("type" to "audio", "uri" to uri)) }
            radioTracks(queue?.metadata.orEmpty(), seedId).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    override suspend fun createPlaylist(name: String): Boolean = createPlaylistWithId(name) != null
    override suspend fun createPlaylistWithId(name: String): String? {
        return createPlaylistWithId(name, emptyList())
    }

    override suspend fun createPlaylistWithId(name: String, trackIds: List<String>): String? {
        if (name.isBlank()) return null
        return try { client.createPlaylist(name.trim(), trackIds) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean = safely {
        val parameters = buildMap {
            name?.let { if (it.isBlank()) return@safely false else put("title", it.trim()) }
            comment?.let { put("summary", it) }
        }
        if (parameters.isEmpty()) true else client.mutate("PUT", "/playlists/${PlexClient.validId(id)}", parameters)
    }

    override suspend fun deletePlaylist(id: String): Boolean = safely {
        client.mutate("DELETE", "/playlists/${PlexClient.validId(id)}")
    }

    private suspend fun editablePlaylist(id: String): Boolean = client.metadata(id, playlist = true)
        ?.let { it.type == "playlist" && it.playlistType == "audio" && !it.isSmart } == true

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean = safely {
        if (trackIds.isEmpty()) return@safely true
        val id = PlexClient.validId(playlistId)
        trackIds.forEach(PlexClient::validId)
        if (!editablePlaylist(playlistId)) return@safely false
        trackIds.chunked(100).forEach { ids ->
            client.mutate("PUT", "/playlists/$id/items", mapOf("uri" to client.sourceUri(ids)))
        }
        true
    }

    override suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean = safely {
        if (trackIds.isEmpty()) return@safely true
        if (!editablePlaylist(playlistId)) return@safely false
        val wanted = trackIds.toHashSet()
        val entries = client.allMetadata("/playlists/${PlexClient.validId(playlistId)}/items").filter { it.id in wanted }
        val occurrences = entries.map { PlexClient.validId(it.playlistItemID.orEmpty()) }
        // remove playlist occurrences rather than recording ids
        occurrences.forEach { occurrence ->
            client.mutate("DELETE", "/playlists/${PlexClient.validId(playlistId)}/items/$occurrence")
        }
        true
    }

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = safely {
        if (kind !in setOf("song", "track", "album", "artist")) return@safely false
        client.mutate("PUT", "/:/rate", mapOf("key" to PlexClient.validId(id), "identifier" to PROVIDER,
            "rating" to if (starred) "10" else "0"))
    }

    override suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "liked") {
            val tracks = starredSongs()
            return DetailData(DetailInfo("Liked Songs", "${tracks.size} songs you love", tracks.firstOrNull()?.artworkUrl.orEmpty(),
                accentFor("liked"), false, tracks.size, "Liked"), tracks)
        }
        if (kind !in setOf("album", "artist", "playlist")) return null
        val item = client.metadata(id, playlist = kind == "playlist")?.takeIf { it.type == kind } ?: return null
        if (kind == "playlist" && item.playlistType != "audio") return null
        val tracks = collectionTracks(kind, id)
        val albums = if (kind == "artist") client.allMetadata("/library/metadata/${PlexClient.validId(id)}/children")
            .ofType("album").map { it.toAlbum() } else emptyList()
        val subtitle = when (kind) {
            "album" -> listOfNotNull(item.parentTitle, item.year?.toString()).joinToString(" · ")
            "artist" -> "${albums.size} albums · ${tracks.size} tracks"
            else -> item.summary?.takeIf { it.isNotBlank() } ?: "${tracks.size} songs"
        }
        val label = when (kind) {
            "album" -> releaseTypeLabel(inferReleaseType(tracks.size, tracks.sumOf { it.durationSec }))
            "artist" -> "Artist"
            else -> "Playlist"
        }
        return DetailData(DetailInfo(item.title ?: label, subtitle, client.coverArtUrl(id), accentFor(id),
            kind == "artist", tracks.size, label,
            editableDescription = if (kind == "playlist") item.summary.orEmpty() else null), tracks, albums)
    }

    override suspend fun collectionTracks(kind: String, id: String): List<Song> {
        val itemId = PlexClient.validId(id)
        val path = when (kind) {
            "album" -> "/library/metadata/$itemId/children"
            "artist" -> "/library/metadata/$itemId/allLeaves"
            "playlist" -> "/playlists/$itemId/items"
            else -> return super.collectionTracks(kind, id)
        }
        return client.allMetadata(path).ofType("track").map { it.toSong() }
    }

    override suspend fun serverLyrics(song: Song): Lyrics? = plexLyrics(client, song.id)
    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String = client.streamUrl(songId, maxBitrate, lossless)
    override fun coverArtUrl(id: String, size: Int): String = client.coverArtUrl(id, size)

    private suspend inline fun safely(block: () -> Boolean): Boolean = try { block() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    private suspend inline fun <T> optional(block: () -> T): T? = try { block() }
        catch (e: PlexException) { if (e.statusCode in setOf(400, 403, 404, 405, 422)) null else throw e }
}
