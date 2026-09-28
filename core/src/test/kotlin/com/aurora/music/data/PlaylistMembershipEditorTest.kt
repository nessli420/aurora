package com.aurora.music.data

import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PlaylistMembershipEditorTest {
    private class Source(val host: String = "https://server.invalid") {
        val song = Song("song", "Track $host", "Artist", "Album", "", 180)
        var tracks = emptyList<Song>()
        var failRead = false
        var allowWrite = true
        val writes = mutableListOf<String>()
        val backend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> Session(host, "Listener", "", "token", ServerType.SUBSONIC)
                "allSongs" -> listOf(song)
                "playbackSourceIdentity" -> null
                "playlistsForSong", "allPlaylists" -> listOf(Playlist("playlist", "Playlist", "", "", tracks.size))
                "collectionTracks" -> { check(!failRead) { "Unavailable" }; tracks }
                "addToPlaylist", "removeFromPlaylist" -> {
                    assertEquals("playlist", args!![0])
                    assertEquals(listOf("song"), args[1])
                    writes += method.name
                    if (allowWrite) tracks = if (method.name == "addToPlaylist") tracks + song else tracks.filterNot { it.id == song.id }
                    allowWrite
                }
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend
    }

    @Test fun editsAvoidDuplicatesRemoveMembershipAndPublishOnlyAfterClosing() = runBlocking {
        val source = Source()
        val changes = mutableListOf<String>()
        val editor = PlaylistMembershipEditor(source.backend, "song", onChanged = changes::add)
        editor.playlists()
        assertFalse(editor.contains("playlist"))
        assertTrue(editor.setIncluded("playlist", true))
        assertTrue(editor.setIncluded("playlist", true))
        assertEquals(listOf("addToPlaylist"), source.writes)
        source.tracks = source.tracks + source.song
        assertTrue(editor.setIncluded("playlist", false))
        assertTrue(source.tracks.isEmpty())
        assertTrue(changes.isEmpty())
        editor.publishChanges()
        editor.publishChanges()
        assertEquals(listOf("playlist"), changes)
    }

    @Test fun failedReadNeverBecomesAnAddAndFailedWriteNeverPublishesSuccess() = runBlocking {
        val source = Source()
        val changes = mutableListOf<String>()
        val editor = PlaylistMembershipEditor(source.backend, "song", onChanged = changes::add)
        editor.playlists()
        source.failRead = true
        try { editor.setIncluded("playlist", true); fail("Read failure was accepted") } catch (_: IllegalStateException) { }
        assertTrue(source.writes.isEmpty())
        source.failRead = false
        source.allowWrite = false
        assertFalse(editor.setIncluded("playlist", true))
        editor.publishChanges()
        assertTrue(changes.isEmpty())
        assertFalse(editor.contains("playlist"))
    }

    @Test fun accountSwitchAndForeignPlaylistCannotMutateAnything() = runBlocking {
        val source = Source()
        var active = true
        val editor = PlaylistMembershipEditor(source.backend, "song", isActive = { active })
        editor.playlists()
        assertFalse(editor.setIncluded("foreign", true))
        active = false
        assertFalse(editor.setIncluded("playlist", true))
        assertTrue(source.writes.isEmpty())
    }

    @Test fun mergedEditorListsAndMutatesOnlyTheTracksProvider() = runBlocking {
        val first = Source("https://first.invalid")
        val second = Source("https://second.invalid")
        val merged = MergedBackend(listOf(first.backend, second.backend), first.backend.session)
        val songs = merged.allSongs()
        val firstPlaylists = merged.playlistsForSong(songs[0].id)
        val secondPlaylists = merged.playlistsForSong(songs[1].id)
        assertEquals(1, firstPlaylists.size)
        assertNotEquals(firstPlaylists.single().id, secondPlaylists.single().id)
        val editor = PlaylistMembershipEditor(merged, songs[1].id)
        val playlist = editor.playlists().single()
        assertTrue(editor.setIncluded(playlist.id, true))
        assertTrue(editor.setIncluded(playlist.id, false))
        assertTrue(first.writes.isEmpty())
        assertEquals(listOf("addToPlaylist", "removeFromPlaylist"), second.writes)
        assertFalse(merged.removeFromPlaylist(playlist.id, songs.map { it.id }))
        assertEquals(2, second.writes.size)
    }
}
