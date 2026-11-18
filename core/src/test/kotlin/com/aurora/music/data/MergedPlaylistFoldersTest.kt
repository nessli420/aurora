package com.aurora.music.data

import com.aurora.music.data.PlaylistFolderEntry.Folder
import com.aurora.music.data.PlaylistFolderEntry.Item
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

private fun unsupported(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class MergedPlaylistFoldersTest {
    private class Source(override val session: Session, val tree: PlaylistFolder? = null) : MediaBackend by unsupported() {
        val moves = ArrayList<Pair<String, String>>()
        override val supportsPlaylistFolders get() = tree != null
        override suspend fun playlistFolders() = tree
        override suspend fun movePlaylistToFolder(playlistId: String, folderId: String): Boolean { moves += playlistId to folderId; return true }
    }

    private val spotifySession = Session(SpotifyWebBackend.SERVER, "Listener", SpotifyWebBackend.WEB_PLAYER, "reference", ServerType.SPOTIFY, userId = "listener")
    private val serverSession = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)

    @Test fun onlySpotifyBringsFoldersAndMovesStayInsideIt() = runBlocking {
        val tree = PlaylistFolder("", "", listOf(Folder(PlaylistFolder("f1", "Mixes", listOf(Item("p1")))), Item("p2")))
        val spotify = Source(spotifySession, tree)
        val merged = MergedBackend(listOf(Source(serverSession), spotify), serverSession)
        val own = mergeNamespace(spotifySession) + MERGE_NAMESPACE_SEP
        assertEquals(tree.mapIds { own + it }, merged.playlistFolders())
        assertFalse(merged.libraryView("library").supportsPlaylistFolders)
        assertTrue(merged.libraryView("spotify").supportsPlaylistFolders)
        assertTrue(merged.movePlaylistToFolder(own + "p2", own + "f1"))
        assertTrue(merged.movePlaylistToFolder(own + "p1", ""))
        // another source's folder is no place for a spotify playlist
        assertFalse(merged.movePlaylistToFolder(own + "p2", mergeNamespace(serverSession) + MERGE_NAMESPACE_SEP + "f1"))
        assertEquals(listOf("p2" to "f1", "p1" to ""), spotify.moves)
    }
}
