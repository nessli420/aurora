package com.aurora.music.data

import com.aurora.music.data.rules.RuleSource
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PlaybackSourceIdentityTest {
    private val first = Session("https://music.example.invalid", "first", "salt", "token", ServerType.SUBSONIC)
    private val second = first.copy(username = "second")
    private val song = Song("track", "Title", "Artist", "Album", "", 60, streamUrl = "https://music.example.invalid/audio", albumId = "album")

    @Test fun accountHashIgnoresTokenRefreshAndScopesAlbumAndPlaylistIds() {
        val a = PlaybackSourceIdentity.fromSession(first, "album")
        val provider = requireNotNull(a.providerId)
        val refreshed = PlaybackSourceIdentity.fromSession(first.copy(token = "refreshed", salt = "fresh"), "album")
        val b = PlaybackSourceIdentity.fromSession(second, "album")
        assertEquals(a, refreshed)
        assertNotEquals(a.providerId, b.providerId)
        assertNotEquals(a.albumId, b.albumId)
        assertFalse(provider.contains(first.username))
        assertFalse(provider.contains(first.server))
        assertNotEquals(PlaybackSourceIdentity.scoped(provider, "playlist", "same"),
            PlaybackSourceIdentity.scoped(b.providerId!!, "playlist", "same"))
        assertNotEquals(a.albumId, PlaybackSourceIdentity.scoped(provider, "playlist", "album"))
    }

    @Test fun mergedSourceIdentitySurvivesChangingSourceOrder() = runBlocking {
        val a = source(first)
        val b = source(second)
        val forward = MergedBackend(listOf(a, b), first)
        val reversed = MergedBackend(listOf(b, a), first)
        val wrappedA = forward.songFor("0\u0001track")!!
        val movedA = reversed.songFor("1\u0001track")!!
        assertEquals(wrappedA.playbackSource, movedA.playbackSource)
        assertEquals(PlaybackSourceIdentity.fromSession(first, "album"), wrappedA.playbackSource)
        assertNotEquals(wrappedA.playbackSource, forward.songFor("1\u0001track")!!.playbackSource)
        assertEquals(forward.playbackCollectionIdentity("playlist", "0\u0001list", "List"),
            reversed.playbackCollectionIdentity("playlist", "1\u0001list", "List"))
        assertNull(forward.playbackSourceIdentity(song))
        assertNull(forward.playbackCollectionIdentity("playlist", "list", "List"))
    }

    @Test fun explicitPlayableCopyOriginSurvivesMergedWrapping() = runBlocking {
        val actual = PlaybackSourceIdentity.fromSession(second, "download-album", RuleSource.DOWNLOAD)
        val wrapped = MergedBackend(listOf(source(first, song.copy(playbackSource = actual))), first).songFor("0\u0001track")!!
        assertEquals(actual, wrapped.playbackSource)
    }

    private fun source(session: Session, track: Song = song): MediaBackend = Proxy.newProxyInstance(
        MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)
    ) { _, method, args -> when (method.name) {
        "getSession" -> session
        "songFor" -> track
        "playbackSourceIdentity" -> (args!![0] as Song).playbackSource ?: PlaybackSourceIdentity.fromSession(session, (args[0] as Song).albumId)
        "playbackCollectionIdentity" -> PlaybackCollectionIdentity(
            PlaybackSourceIdentity.scoped(PlaybackSourceIdentity.fromSession(session, "").providerId!!, "playlist", args!![1] as String), args[2] as String?)
        else -> error("Unexpected fixture call: ${method.name}")
    } } as MediaBackend
}
