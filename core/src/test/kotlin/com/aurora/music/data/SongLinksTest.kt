package com.aurora.music.data

import com.aurora.music.data.remote.AppleMusicTransport
import com.aurora.music.data.remote.SoundCloudClient
import com.aurora.music.data.remote.SoundCloudTransport
import com.aurora.music.data.remote.SpotifyGraphql
import com.aurora.music.data.remote.YouTubeMusicTransport
import com.aurora.music.model.Song
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SongLinksTest {
    private fun song(id: String) = Song(id, "Halcyon", "Seefeel", "Quique", "", 300)

    @Test fun eachCatalogueBuildsItsOwnPublicLink() = runBlocking {
        val spotify = SpotifyWebBackend(Session(SpotifyWebBackend.SERVER, "Listener", SpotifyWebBackend.WEB_PLAYER, "reference", ServerType.SPOTIFY, "listener"),
            object : SpotifyGraphql {
                override suspend fun query(operation: String, variables: JsonObject) = error("unused")
                override suspend fun spclient(path: String, body: JsonObject?) = error("unused")
            }) { it }
        assertEquals("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", spotify.songLink(song("4uLU6hMCjMI75M1A2tKUQC")))

        val yt = YouTubeMusicBackend(Session("https://music.youtube.com", "Listener", "", "reference", ServerType.YOUTUBE_MUSIC, "@listener"),
            YouTubeMusicTransport { _, _ -> error("unused") })
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", yt.songLink(song("dQw4w9WgXcQ")))
        assertNull(yt.songLink(song("not a video")))

        val soundcloud = SoundCloudBackend(Session(SoundCloudClient.WEB, "listener", "", "reference", ServerType.SOUNDCLOUD, "42"), SoundCloudTransport { path ->
            assertEquals("/tracks/5", path)
            JsonParser.parseString("""{"id":5,"title":"Halcyon","permalink_url":"https://soundcloud.com/seefeel/halcyon"}""")
        })
        assertEquals("https://soundcloud.com/seefeel/halcyon", soundcloud.songLink(song("5")))

        val apple = AppleMusicBackend(Session.guest(ServerType.APPLE_MUSIC, "Guest"), object : AppleMusicTransport {
            override suspend fun get(path: String): JsonObject = error("unused")
            override suspend fun storefront() = "nl"
        })
        assertEquals("https://music.apple.com/nl/song/1623924746", apple.songLink(song("1623924746")))
        assertNull(apple.songLink(song("i.uploadedSong")))
        assertTrue(listOf(spotify, yt, soundcloud, apple).all { it.linksSongs })
    }

    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
    private val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)
    private val sc = Session(SoundCloudClient.WEB, "listener", "", "reference", ServerType.SOUNDCLOUD, "42")
    private val yt = Session("https://music.youtube.com", "Listener", "", "reference", ServerType.YOUTUBE_MUSIC, "@listener")

    private fun source(session: Session, songs: List<Song>, searched: MutableList<ServerType> = mutableListOf()): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> session
                "search" -> { searched += session.type; SearchResults(songs = songs) }
                "matchingSongs" -> { searched += session.type; songs }
                "allSongs" -> songs
                "playbackSourceIdentity" -> PlaybackSourceIdentity.fromSession(session, (args!![0] as Song).albumId)
                "getLinksSongs" -> session.type.isCatalogue
                "songLink" -> if (session.type.isCatalogue) "https://${session.type}/${(args!![0] as Song).id}" else null
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    @Test fun aMergedSongGetsALinkFromEveryMergedCatalogueThatHasIt() = runBlocking {
        val hosted = song("server").copy(streamUrl = "https://server.invalid/stream", suffix = "flac")
        val searched = mutableListOf<ServerType>()
        val merged = MergedBackend(listOf(source(server, listOf(hosted), searched), source(sc, listOf(song("5")), searched),
            source(yt, listOf(song("dQw4w9WgXcQ")), searched)), server)
        val own = merged.allSongs().single()
        val links = merged.songLinks(own)
        assertEquals(listOf("https://SOUNDCLOUD/5", "https://YOUTUBE_MUSIC/dQw4w9WgXcQ"), links.map { it.url })
        assertEquals(listOf(ServerType.SOUNDCLOUD, ServerType.YOUTUBE_MUSIC), links.map { it.type })
        assertFalse(ServerType.SUBSONIC in searched)

        val soundcloudOnly = MergedBackend(listOf(source(server, listOf(hosted)), source(sc, listOf(song("5").copy(title = "Other")))), server)
        assertTrue(soundcloudOnly.songLinks(soundcloudOnly.allSongs().first { it.title == "Halcyon" }).isEmpty())
        assertFalse(MergedBackend(listOf(source(server, listOf(hosted)), source(local, emptyList())), server).linksSongs)
    }
}
