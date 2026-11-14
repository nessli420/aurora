package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class PlaylistImportTest {
    @get:Rule val temp = TemporaryFolder()

    private fun song(id: String, title: String, artist: String, seconds: Int, url: String = "stream://$id") = Song(id, title, artist, "", "", seconds, streamUrl = url)

    @Test fun exportedListsFromEveryAppRead() {
        val exportify = "\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\n" +
            "\"spotify:track:1\",\"Hey Jude - Remastered 2015\",\"The Beatles\",\"1\",\"429000\"\n" +
            "\"spotify:track:2\",\"Under Pressure\",\"Queen, David Bowie\",\"Hot Space\",\"248000\"\n"
        val a = PlaylistFiles.read("Road Trip.csv", exportify.toByteArray())
        assertEquals("Road Trip", a.name)
        assertEquals(listOf(ImportTrack("Hey Jude - Remastered 2015", "The Beatles", "1", 429), ImportTrack("Under Pressure", "Queen, David Bowie", "Hot Space", 248)), a.tracks)

        val apple = "Name\tArtist\tComposer\tAlbum\tTime\r\nTitanium\tDavid Guetta\t\tNothing but the Beat\t245\r\n"
        val b = PlaylistFiles.read("Workout.txt", ("﻿" + apple).toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it.drop(2) })
        assertEquals(listOf(ImportTrack("Titanium", "David Guetta", "Nothing but the Beat", 245)), b.tracks)

        val tunemymusic = "Track name,Artist name,Album,Playlist name,Type,ISRC\nYellow,Coldplay,Parachutes,Chill,Playlist,GBAYE0000351\n"
        assertEquals("Chill", PlaylistFiles.read("export.csv", tunemymusic.toByteArray()).name)

        val text = "1. Daft Punk - One More Time\nRadiohead – Creep\nJust A Title\n"
        assertEquals(listOf(ImportTrack("One More Time", "Daft Punk"), ImportTrack("Creep", "Radiohead"), ImportTrack("Just A Title")),
            PlaylistFiles.read("list.txt", text.toByteArray()).tracks)

        val xspf = "<?xml version=\"1.0\"?><playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\"><title>Mix &amp; Match</title><trackList>" +
            "<track><title>Song 2</title><creator>Blur</creator><duration>122000</duration></track></trackList></playlist>"
        val x = PlaylistFiles.read("x.xspf", xspf.toByteArray())
        assertEquals("Mix & Match", x.name)
        assertEquals(listOf(ImportTrack("Song 2", "Blur", durationSec = 122)), x.tracks)

        val m3u = "#EXTM3U\n#EXTINF:200,Blur - Parklife\nMusic/Blur/Parklife.mp3\n"
        assertEquals(listOf(ImportTrack("Parklife", "Blur", durationSec = 200, location = "Music/Blur/Parklife.mp3")), PlaylistFiles.read("p.m3u8", m3u.toByteArray()).tracks)
    }

    @Test fun linksFromEveryAppAreRecognised() {
        val links = PlaylistLinks(OkHttpClient())
        fun check(url: String, service: PlaylistLinks.Service, kind: String, id: String) {
            val link = links.parse(url) ?: error("not recognised: $url")
            assertEquals(url, service to kind, link.service to link.kind)
            if (id.isNotEmpty()) assertEquals(url, id, link.id)
        }
        check("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc", PlaylistLinks.Service.SPOTIFY, "playlist", "37i9dQZF1DXcBWIGoYBM5M")
        check("https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy", PlaylistLinks.Service.SPOTIFY, "album", "4aawyAB9vmqN3uQ7FjRGTy")
        check("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", PlaylistLinks.Service.SPOTIFY, "playlist", "37i9dQZF1DXcBWIGoYBM5M")
        check("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb", PlaylistLinks.Service.APPLE_MUSIC, "playlist", "pl.f4d106fed2bd41149aaacabb233eb5eb")
        check("https://www.deezer.com/en/playlist/908622995", PlaylistLinks.Service.DEEZER, "playlist", "908622995")
        check("https://tidal.com/browse/playlist/4261748a-4287-4758-aaab-6d5be3e99e52", PlaylistLinks.Service.TIDAL, "playlist", "4261748a-4287-4758-aaab-6d5be3e99e52")
        check("https://music.youtube.com/playlist?list=PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i", PlaylistLinks.Service.YOUTUBE, "playlist", "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i")
        check("https://www.youtube.com/watch?v=abc&list=PLx", PlaylistLinks.Service.YOUTUBE, "playlist", "PLx")
        check("https://soundcloud.com/someone/sets/late-night?si=1", PlaylistLinks.Service.SOUNDCLOUD, "playlist", "")
        assertNull(links.parse("https://example.com/playlist/1"))
        assertNull(links.parse("https://soundcloud.com/someone/a-single-track"))
    }

    @Test fun yourOwnSourcesWinThenTheCatalogues() = runBlocking {
        val library = listOf(song("lib1", "Under Pressure", "Queen & David Bowie", 248))
        val youtube = listOf(song("yt1", "Hey Jude", "The Beatles", 431, "aurora-yt://video/yt1"), song("yt2", "Under Pressure", "Queen", 248, "aurora-yt://video/yt2"))
        val soundcloud = listOf(song("sc1", "Night Drive (Original Mix)", "Some Producer", 300, "aurora-sc://track/sc1"))
        val stages = listOf(
            ImportStage(ImportSource.LIBRARY) { library },
            ImportStage(ImportSource.YOUTUBE_MUSIC) { youtube },
            ImportStage(ImportSource.SOUNDCLOUD) { soundcloud },
        )
        val tracks = listOf(
            ImportTrack("Under Pressure", "Queen, David Bowie", durationSec = 248),
            ImportTrack("Hey Jude - Remastered 2015", "The Beatles", durationSec = 429),
            ImportTrack("Night Drive", "Some Producer", durationSec = 300),
            ImportTrack("Not Anywhere", "Nobody", durationSec = 100),
        )
        val seen = mutableListOf<Int>()
        val m = PlaylistMatcher.matchAll(tracks, stages) { done, _ -> synchronized(seen) { seen += done } }
        assertEquals(listOf("lib1", "yt1", "sc1", null), m.map { it.song?.id })
        assertEquals(listOf(ImportSource.LIBRARY, ImportSource.YOUTUBE_MUSIC, ImportSource.SOUNDCLOUD, null), m.map { it.source })
        assertEquals(4, seen.max())
    }

    @Test fun anotherVersionOrArtistIsNotTheSameSong() {
        val track = ImportTrack("Creep", "Radiohead", durationSec = 236)
        assertNull(PlaylistMatcher.best(track, listOf(song("a", "Creep (Live)", "Radiohead", 290))))
        assertNull(PlaylistMatcher.best(track, listOf(song("b", "Creep", "TLC", 268))))
        assertNull(PlaylistMatcher.best(track, listOf(song("c", "Creep", "Radiohead", 236, url = ""))))
        assertEquals("d", PlaylistMatcher.best(track, listOf(song("d", "Creep", "Radiohead", 238)))?.id)
        assertEquals("Radiohead Creep", PlaylistMatcher.query(track))
        assertEquals("Queen Under Pressure", PlaylistMatcher.query(ImportTrack("Under Pressure (feat. David Bowie)", "Queen, David Bowie")))
    }

    @Test fun aCatalogueListKeepsItsOwnSongWhenNothingElseHasIt() = runBlocking {
        val original = song("vid", "Unreleased Demo", "Band", 180, "aurora-yt://video/vid")
        val m = PlaylistMatcher.match(ImportTrack("Unreleased Demo", "Band", durationSec = 180, original = original, originalSource = ImportSource.YOUTUBE_MUSIC),
            listOf(ImportStage(ImportSource.LIBRARY) { emptyList() }))
        assertEquals("vid", m.song?.id)
        assertEquals(ImportSource.YOUTUBE_MUSIC, m.source)
    }

    @Test fun anM3uOfYourOwnFilesSkipsTheSearch() = runBlocking {
        val songs = listOf(
            Song("a", "Parklife", "Blur", "", "", 187, streamUrl = "file://a", path = "/storage/emulated/0/Music/Blur/Parklife/03 Parklife.mp3"),
            Song("b", "Song 2", "Blur", "", "", 122, streamUrl = "file://b", path = "/storage/emulated/0/Music/Blur/Blur/02 Song 2.flac"),
            Song("c", "Creep", "Radiohead", "", "", 236, streamUrl = "file://c", path = "/storage/emulated/0/Music/Radiohead/Pablo Honey/02 Creep.flac"),
            Song("d", "Intro", "Other", "", "", 60, streamUrl = "file://d", path = "/storage/emulated/0/Music/Other/One/01 Intro.mp3"),
            Song("e", "Intro", "Another", "", "", 70, streamUrl = "file://e", path = "/storage/emulated/0/Music/Another/One/01 Intro.mp3"),
        )
        val local = localLibrary(songs)
        val repository = MusicRepository({ local }, DownloadManager(temp.newFolder("dl"), { "file://$it" }, { null }), mapping = kotlin.coroutines.EmptyCoroutineContext)
        val m3u = "#EXTM3U\n" +
            "#EXTINF:187,Blur - Parklife\n/storage/emulated/0/Music/Blur/Parklife/03 Parklife.mp3\n" +
            "#EXTINF:122,Blur - Song 2\n..\\Blur\\BLUR\\02 Song 2.flac\n" +
            "#EXTINF:236,Radiohead - Creep\nD:\\Old PC\\Creep.mp3\n" +
            "#EXTINF:200,Nobody - Not Here\nfile:///C:/Music/Nobody/Not%20Here.mp3\n"
        val searched = mutableListOf<String>()
        val yt = song("vid", "Not Here", "Nobody", 200, "aurora-yt://video/vid").inNamespace(Session.guest(ServerType.YOUTUBE_MUSIC, "Guest"))
        val result = repository.importTracks(PlaylistFiles.read("Mine.m3u8", m3u.toByteArray()),
            listOf(ImportStage(ImportSource.YOUTUBE_MUSIC) { q -> synchronized(searched) { searched += q }; listOf(yt) }))
        assertEquals(listOf("Nobody Not Here"), searched)
        assertEquals(mapOf(ImportSource.LIBRARY to 3, ImportSource.YOUTUBE_MUSIC to 1), result.found)
        assertEquals(listOf("a", "b", "c", yt.id), local.playlistEntries(result.playlistId)!!.map { it.id })
        assertTrue(MergedBackend(listOf(local), local.session).fileCopy(ImportTrack("x", location = "Parklife/03 Parklife.mp3"))!!.id.endsWith("a"))

        val index = LocalCatalogIndex.fileIndex(songs)
        assertEquals("a", LocalCatalogIndex.findFile(index, "03 Parklife.mp3")?.id)
        assertNull(LocalCatalogIndex.findFile(index, "Elsewhere/03 Parklife.mp3"))
        assertNull(LocalCatalogIndex.findFile(index, "One/01 Intro.mp3"))
        assertEquals("e", LocalCatalogIndex.findFile(index, "file:///sdcard/Music/Another/One/01%20Intro.mp3")?.id)
    }

    @Test fun aLinkFindsYourOwnCopyByArtistAndTitle() = runBlocking {
        val local = localLibrary(listOf(
            Song("q", "Under Pressure", "Queen", "Hot Space", "", 248, streamUrl = "file://q"),
            Song("p", "Pressure", "Billy Joel", "The Nylon Curtain", "", 279, streamUrl = "file://p"),
        ))
        val track = ImportTrack("Under Pressure - Remastered 2011", "Queen, David Bowie", durationSec = 248)
        assertEquals("q", PlaylistMatcher.match(track, local.importStages()).song?.id)
        assertTrue(PlaylistMatcher.match(track, MergedBackend(listOf(local), local.session).importStages()).song!!.id.endsWith("q"))
        assertEquals(listOf("q", "p"), local.search("pressure").songs.map { it.id })
        assertEquals(listOf("q"), local.search("queen pressure").songs.map { it.id })
        assertTrue(local.search("queen nylon").songs.isEmpty())
    }

    private fun localLibrary(songs: List<Song>): LocalBackend {
        val catalog = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, args ->
            when (method.name) {
                "getSongs" -> songs
                "getAlbums", "getArtists" -> emptyList<Any>()
                "song" -> songs.firstOrNull { it.id == args!![0] }
                "findMatch" -> LocalCatalogIndex.findMatch(LocalCatalogIndex.matchIndex(songs), args!![0] as String, args[1] as String, args[2] as Int)
                "ensureLoaded" -> Unit
                else -> error("Unexpected ${method.name}")
            }
        } as LocalCatalog
        return LocalBackend(catalog, LocalStore(temp.newFolder()), Session("local", "Me", "", "", ServerType.LOCAL))
    }

    @Test fun importedPlaylistsKeepSongsFromEverySource() = runBlocking {
        val store = LocalStore(temp.root)
        val catalog = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, args ->
            when (method.name) {
                "song" -> null
                "ensureLoaded" -> Unit
                else -> error("Unexpected ${method.name}")
            }
        } as LocalCatalog
        val local = LocalBackend(catalog, store, Session("local", "Me", "", "", ServerType.LOCAL))
        val guest = Session.guest(ServerType.YOUTUBE_MUSIC, "Guest")
        val yt = song("vid", "Hey Jude", "The Beatles", 431, "aurora-yt://video/vid").inNamespace(guest)
        val id = local.createMixedPlaylist("From Spotify", listOf(yt))!!
        val entries = local.playlistEntries(id)!!
        assertEquals(listOf("aurora-yt://video/vid"), entries.map { it.streamUrl })
        assertTrue(entries.single().id.startsWith(mergeNamespace(guest)))
        assertEquals(listOf(yt.id), local.detail("playlist", id)!!.tracks.map { it.id })
    }
}
