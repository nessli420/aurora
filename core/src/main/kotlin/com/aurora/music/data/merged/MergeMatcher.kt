package com.aurora.music.data.merged

import com.aurora.music.data.CatalogueMatchPrefs
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.ServerType
import com.aurora.music.data.accountKey
import com.aurora.music.data.merged.SourceRouter.Companion.DOWN_MS
import com.aurora.music.data.merged.SourceRouter.Companion.SEP
import com.aurora.music.data.recordingMatches
import com.aurora.music.data.recordingTitle
import com.aurora.music.data.sameLength
import com.aurora.music.data.withPlaybackFrom
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal class MergeMatcher(
    private val router: SourceRouter,
    private val priority: () -> List<String>,
    private val downloads: (Song) -> List<Song>,
    private val catalogueMatch: () -> CatalogueMatchPrefs,
) {

    private val matchCache = LinkedHashMap<String, Pair<Long, List<Song>>>()
    // apart from downUntil: running out of this short budget says nothing about browsing that server
    private val matchDownUntil = ConcurrentHashMap<Int, Long>()

    suspend fun playbackCandidates(song: Song): List<Song> {
        if (SEP in song.id && router.unwrap(song.id) == null) return emptyList()
        val index = router.unwrap(song.id)?.first ?: router.sources.indexOfFirst { it.session.accountKey() == router.session.accountKey() }
        if (index !in router.sources.indices) return emptyList()
        if (!router.isCatalogue(router.sources[index])) return emptyList()
        val rules = catalogueMatch()
        if (!rules.useLibrary) return emptyList()
        val order = priority()
        val key = order.joinToString(",") + "\u0000" + recordingTitle(song.title) + "\u0000" + song.artist
        // the lock never spans the search, so a tap doesn't queue behind a prefetch
        val copies = synchronized(matchCache) { matchCache[key]?.takeIf { System.nanoTime() - it.first < 60_000_000_000L }?.second } ?: coroutineScope {
            router.sources.mapIndexedNotNull { i, source ->
                if (router.isCatalogue(source) || router.isDown(i) || (matchDownUntil[i] ?: 0L) > System.currentTimeMillis()) null else async<List<Song>> {
                    try {
                        val found = withTimeoutOrNull(3_000) { source.matchingSongs(song).map { router.wrap(it, i) } }
                        // the device's library can't be unreachable, only busy with its first scan or index
                        if (found == null && source.session.type != ServerType.LOCAL) matchDownUntil[i] = System.currentTimeMillis() + DOWN_MS
                        found.orEmpty()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList<Song>() }
                }
            }.awaitAll().flatten()
        }.also { fresh ->
            synchronized(matchCache) {
                matchCache[key] = System.nanoTime() to fresh
                while (matchCache.size > 128) matchCache.remove(matchCache.keys.first())
            }
        }
        fun tier(copy: Song) = when {
            copy.playbackSource?.source == com.aurora.music.data.rules.RuleSource.DOWNLOAD -> "downloaded"
            copy.streamUrl.startsWith("file:") || copy.streamUrl.startsWith("content:") -> "local"
            else -> "stream"
        }
        return (copies + downloads(song)).filter { copy ->
            copy.streamUrl.isNotBlank() && (recordingMatches(song, copy) || rules.relaxed && sameLength(song, copy)) &&
                (!rules.losslessOnly || copy.suffix.lowercase() in LOSSLESS)
        }
            .distinctBy { it.streamUrl }
            .sortedWith(compareBy<Song> { order.indexOf(tier(it)).let { rank -> if (rank < 0) Int.MAX_VALUE else rank } }
                .thenBy { if (song.durationSec > 0 && it.durationSec > 0 && kotlin.math.abs(song.durationSec - it.durationSec) > TrackMatch.DURATION_TOLERANCE_SEC) 1 else 0 }
                .thenByDescending { qualityScore(it) })
            .map { song.withPlaybackFrom(it) }
    }

    fun dedupAlbums(all: List<Album>): List<Album> =
        all.distinctBy { TrackMatch.key(it.artist, it.title) }

    fun dedupArtists(all: List<Artist>): List<Artist> =
        all.distinctBy { TrackMatch.identity(it.name) }.filter { it.name.isNotBlank() }

    // cluster by real duration window not fixed bins so near-equal durations dont split across a boundary
    fun dedupSongs(all: List<Song>): List<Song> =
        all.groupBy { TrackMatch.key(it.artist, it.title) }
            .values
            .flatMap { group ->
                val clusters = ArrayList<MutableList<Song>>()
                for (s in group.sortedBy { it.durationSec }) {
                    val cur = clusters.lastOrNull()
                    if (cur != null && s.durationSec - cur.first().durationSec <= TrackMatch.DURATION_TOLERANCE_SEC) cur.add(s)
                    else clusters.add(mutableListOf(s))
                }
                clusters.map { c -> c.maxByOrNull { qualityScore(it) } ?: c.first() }
            }

    private fun qualityScore(s: Song): Long {
        var score = 0L
        if (s.suffix.lowercase() in LOSSLESS) score += 2_000_000
        score += s.bitDepth.toLong() * 100_000
        score += s.sampleRateHz.toLong() / 100
        score += s.bitrateKbps.toLong()
        return score
    }

    fun mergeHomes(homes: List<HomeData?>): HomeData {
        val albums = ArrayList<Album>(); val recent = ArrayList<Album>(); val most = ArrayList<Album>(); val random = ArrayList<Album>()
        val playlists = ArrayList<Playlist>(); val artists = ArrayList<Artist>(); val starred = ArrayList<Song>()
        val sections = ArrayList<HomeFeedSection>()
        for ((idx, h) in homes.withIndex()) {
            if (h == null) continue
            albums += h.newReleases.map { router.wrap(it, idx) }
            recent += h.recentlyPlayed.map { router.wrap(it, idx) }
            most += h.mostPlayed.map { router.wrap(it, idx) }
            random += h.random.map { router.wrap(it, idx) }
            playlists += h.playlists.map { router.wrap(it, idx) }
            artists += h.artists.map { router.wrap(it, idx) }
            starred += h.starred.map { router.wrap(it, idx) }
            sections += router.wrapSections(h.sections, idx)
        }
        return HomeData(
            newReleases = dedupAlbums(albums),
            recentlyPlayed = dedupAlbums(recent),
            mostPlayed = dedupAlbums(most),
            random = dedupAlbums(random),
            playlists = playlists,
            artists = dedupArtists(artists),
            starred = dedupSongs(starred),
            sections = sections,
        )
    }

    private companion object {
        val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")
    }
}
