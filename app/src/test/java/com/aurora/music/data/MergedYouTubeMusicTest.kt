package com.aurora.music.data

import com.aurora.music.data.rules.RuleSource
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class MergedYouTubeMusicTest {
    private val yt = Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC, userId = "account")
    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
    private val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)
    private val original = Song("youtube-id", "Still love you", "GLM-RST", "Still love you", "", 146,
        streamUrl = "aurora-yt://video/youtube-id", albumId = "youtube-album")
    private val file = original.copy(id = "file", streamUrl = "file:///music.wav", suffix = "wav", bitDepth = 16, sampleRateHz = 44100)
    private val hosted = original.copy(id = "server", streamUrl = "https://server.invalid/stream", suffix = "flac", bitDepth = 24, sampleRateHz = 96000)

    private class Calls { val searches = mutableListOf<String>(); val likes = mutableListOf<String>(); var homes = 0; val pages = mutableListOf<String>() }
    private fun source(session: Session, songs: List<Song>, calls: Calls = Calls(), failSearch: Boolean = false): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> session
                "home" -> { calls.homes++; HomeData(starred = songs, sections = listOf(HomeFeedSection("shelf", "For you", items = songs.map { HomeFeedItem.Track(it) })), continuation = "next") }
                "homePage" -> { calls.pages += args!![0] as String; HomeData(sections = listOf(HomeFeedSection("more", "More", items = songs.map { HomeFeedItem.Track(it) }))) }
                "search" -> { calls.searches += args!![0] as String; if (failSearch) error("Unavailable") else SearchResults(songs = songs) }
                "matchingSongs" -> { calls.searches += recordingTitle((args!![0] as Song).title); if (failSearch) error("Unavailable") else songs }
                "songFor" -> songs.firstOrNull { it.id == args!![0] }
                "allSongs", "librarySongs", "starredSongs", "collectionTracks" -> songs
                "allAlbums", "allArtists", "allPlaylists" -> emptyList<Any>()
                "playbackSourceIdentity" -> (args!![0] as Song).playbackSource ?: PlaybackSourceIdentity.fromSession(session, (args[0] as Song).albumId)
                "setStarred" -> { calls.likes += args!![0] as String; true }
                "streamUrl" -> songs.firstOrNull { it.id == args!![0] }?.streamUrl.orEmpty()
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    @Test fun libraryHomeExcludesDiscoveryButSwitchAndPaginationRetainTrackRouting() = runBlocking {
        val localCalls = Calls(); val ytCalls = Calls()
        val merged = MergedBackend(listOf(source(local, listOf(file), localCalls), source(yt, listOf(original), ytCalls)), yt)
        assertEquals(listOf("Library", "YouTube Music"), merged.homeFeeds.map { it.label })
        val home = merged.home("library")
        assertEquals(1, localCalls.homes); assertEquals(0, ytCalls.homes)
        assertEquals(file.streamUrl, home.starred.single().streamUrl)
        val discovery = merged.home("discovery")
        val track = (discovery.sections.single().items.single() as HomeFeedItem.Track).song
        assertEquals(original.id, stripMergeNamespace(track.id))
        assertEquals(original.streamUrl, merged.songFor(track.id)!!.streamUrl)
        merged.homePage("discovery", discovery.continuation!!)
        assertEquals(listOf("next"), ytCalls.pages)
    }

    @Test fun catalogueSearchDoesNotQueryServersUntilPlaybackAndKeepsLikesOnYoutube() = runBlocking {
        val serverCalls = Calls(); val ytCalls = Calls()
        val merged = MergedBackend(listOf(source(server, listOf(hosted), serverCalls), source(yt, listOf(original), ytCalls)), yt)
        val track = merged.search("still love").songs.single()
        assertTrue(serverCalls.searches.isEmpty()); assertEquals(listOf("still love"), ytCalls.searches)
        val candidate = merged.playbackCandidates(track).single()
        assertEquals(hosted.streamUrl, candidate.streamUrl)
        assertEquals(track.id, candidate.id); assertEquals(track.albumId, candidate.albumId)
        assertEquals(24, candidate.bitDepth)
        assertEquals(PlaybackSourceIdentity.fromSession(server, hosted.albumId), candidate.playbackSource)
        merged.setStarred(candidate.id, true, "song")
        assertEquals(listOf(original.id), ytCalls.likes); assertTrue(serverCalls.likes.isEmpty())
        merged.playbackCandidates(track)
        assertEquals(1, serverCalls.searches.size)
    }

    @Test fun sourcePriorityRanksLocalDownloadAndServerThenQuality() = runBlocking {
        val downloaded = file.copy(id = "download", streamUrl = "file:///download.flac",
            playbackSource = PlaybackSourceIdentity.fromSession(server, "album", RuleSource.DOWNLOAD))
        var order = listOf("local", "downloaded", "stream")
        val merged = MergedBackend(listOf(source(local, listOf(file)), source(server, listOf(hosted, hosted.copy(id = "low", suffix = "mp3", bitDepth = 0))), source(yt, listOf(original))), yt,
            priority = { order }, downloads = { listOf(downloaded) })
        val track = merged.search("title").songs.single()
        assertEquals(listOf(file.streamUrl, downloaded.streamUrl, hosted.streamUrl), merged.playbackCandidates(track).map { it.streamUrl })
        order = listOf("stream", "downloaded", "local")
        assertEquals(hosted.streamUrl, merged.playbackCandidates(track).first().streamUrl)
    }

    @Test fun namedVersionsAndDifferentArtistsAreNeverSubstituted() = runBlocking {
        val wrong = listOf(hosted.copy(title = "Still love you (Slowed)"), hosted.copy(artist = "Another artist"))
        val merged = MergedBackend(listOf(source(server, wrong), source(yt, listOf(original))), yt)
        assertTrue(merged.playbackCandidates(merged.search("title").songs.single()).isEmpty())
        assertFalse(recordingMatches(original.copy(title = "夜"), original.copy(title = "朝")))
        assertTrue(recordingMatches(original.copy(durationSec = 0), hosted))
        assertTrue(recordingMatches(original.copy(durationSec = 190, explicit = true), hosted))
    }

    @Test fun unavailableServerDoesNotPreventOtherCopiesOrCatalogueFallback() = runBlocking {
        val merged = MergedBackend(listOf(source(server, listOf(hosted), failSearch = true), source(local, listOf(file)), source(yt, listOf(original))), yt)
        assertEquals(file.streamUrl, merged.playbackCandidates(merged.search("title").songs.single()).single().streamUrl)
        val unavailable = MergedBackend(listOf(source(server, listOf(hosted), failSearch = true), source(yt, listOf(original))), yt)
        assertTrue(unavailable.playbackCandidates(unavailable.search("title").songs.single()).isEmpty())
    }

    @Test fun namespaceSurvivesSourceReorderingAndRemovedAccountDoesNotRouteElsewhere() = runBlocking {
        val a = source(server, listOf(hosted)); val b = source(yt, listOf(original))
        val first = MergedBackend(listOf(a, b), yt).search("title").songs.single()
        val reordered = MergedBackend(listOf(b, a), yt)
        assertEquals(first.id, reordered.search("title").songs.single().id)
        assertEquals(original.streamUrl, reordered.songFor(first.id)!!.streamUrl)
        val removed = MergedBackend(listOf(a), server)
        assertNull(removed.songFor(first.id)); assertEquals("", removed.streamUrl(first.id, 0, false))
    }

    @Test fun albumQueuePreservesOrderAndRepeatedOccurrences() = runBlocking {
        val second = original.copy(id = "second", title = "Still love you (Slowed)", durationSec = 154)
        val merged = MergedBackend(listOf(source(local, emptyList()), source(yt, listOf(original, second, original))), yt)
        val first = merged.search("title").songs.first()
        val queue = merged.collectionTracks("album", first.albumId)
        assertEquals(listOf(original.id, second.id, original.id), queue.map { stripMergeNamespace(it.id) })
    }

    @Test fun recommendationsWithoutDurationStillMatchOwnedRecordings() = runBlocking {
        val merged = MergedBackend(listOf(source(local, listOf(file)), source(yt, listOf(original))), yt)
        val track = merged.search("title").songs.single().copy(durationSec = 0)
        val selected = merged.playbackCandidates(track).single()
        assertEquals(file.streamUrl, selected.streamUrl)
        assertEquals(original.durationSec, selected.durationSec)
        assertEquals(track.id, selected.id)
    }

    @Test fun catalogueCreditsAndMissingExplicitTagsDoNotHideOwnedRecordings() = runBlocking {
        val king = original.copy(title = "KING", artist = "Kanye West & Ye", durationSec = 127, explicit = true)
        val father = original.copy(id = "father", title = "FATHER (feat. Travis Scott)", artist = "Ye & Kanye West", durationSec = 170, explicit = true)
        val localKing = file.copy(title = "KING", artist = "Kanye West", durationSec = 126)
        val localFather = file.copy(id = "father-file", title = "FATHER", artist = "Kanye West", durationSec = 169)
        val calls = Calls()
        val merged = MergedBackend(listOf(source(local, listOf(localKing, localFather), calls), source(yt, listOf(king, father))), yt)
        val tracks = merged.search("BULLY").songs
        assertEquals(1, merged.playbackCandidates(tracks[0]).size)
        assertEquals(1, merged.playbackCandidates(tracks[1]).size)
        assertEquals(listOf("KING", "FATHER"), calls.searches)
        assertTrue(recordingMatches(father, localFather.copy(explicit = false)))
        assertFalse(recordingMatches(father.copy(title = "FATHER (Slowed)"), localFather))
        assertFalse(recordingMatches(king.copy(title = "KING (Live)"), localKing))
        assertFalse(recordingMatches(king.copy(artist = "Another singer"), localKing))
        assertFalse(recordingMatches(king.copy(artist = "Another singer (feat. Kanye West)"), localKing))
        assertTrue(recordingMatches(original.copy(title = "Runaway (feat. Pusha T)", artist = "Kanye West", durationSec = 548),
            file.copy(title = "Runaway", artist = "Kanye West", durationSec = 547)))
        assertFalse(recordingMatches(original.copy(title = "Runaway (Video Version) (feat. Pusha T)", artist = "Kanye West", durationSec = 278),
            file.copy(title = "Runaway", artist = "Kanye West", durationSec = 547)))
    }

    @Test fun librarySearchExcludesCatalogueAndRetainsServerRouting() = runBlocking {
        val serverCalls = Calls(); val catalogueCalls = Calls()
        val merged = MergedBackend(listOf(source(server, listOf(hosted), serverCalls), source(yt, listOf(original), catalogueCalls)), yt)
        assertEquals(listOf("Local & servers", "YouTube Music"), merged.searchSources.map { it.label })
        val result = merged.search("Title", "library")
        assertEquals(hosted.streamUrl, result.songs.single().streamUrl)
        assertTrue(catalogueCalls.searches.isEmpty())
        merged.setStarred(result.songs.single().id, true, "song")
        assertEquals(listOf(hosted.id), serverCalls.likes)
    }
}
