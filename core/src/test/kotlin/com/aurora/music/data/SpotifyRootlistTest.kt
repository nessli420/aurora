package com.aurora.music.data

import com.aurora.music.data.PlaylistFolderEntry.Folder
import com.aurora.music.data.PlaylistFolderEntry.Item
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyRootlistTest {
    private fun rootlist(vararg uris: String) = JsonParser.parseString(
        """{"revision":"AAAA","contents":{"items":[${uris.joinToString(",") { """{"uri":"$it","attributes":{"timestamp":"1"}}""" }}]}}""",
    ).asJsonObject

    @Test
    fun nestsFoldersAndKeepsOrder() {
        val tree = SpotifyRootlist.parse(rootlist(
            "spotify:playlist:top1",
            "spotify:start-group:aaaa:Road+trips",
            "spotify:playlist:a1",
            "spotify:start-group:bbbb:Sm%C3%A5+%26+slow",
            "spotify:playlist:b1",
            "spotify:end-group:bbbb",
            "spotify:playlist:a2",
            "spotify:end-group:aaaa",
            "spotify:start-group:cccc:Empty",
            "spotify:end-group:cccc",
            "spotify:user:someone:collection",
            "spotify:playlist:top2",
        ))
        val inner = PlaylistFolder("bbbb", "Små & slow", listOf(Item("b1")))
        val road = PlaylistFolder("aaaa", "Road trips", listOf(Item("a1"), Folder(inner), Item("a2")))
        val expected = PlaylistFolder("", "", listOf(Item("top1"), Folder(road), Folder(PlaylistFolder("cccc", "Empty")), Item("top2")))
        assertEquals(expected, tree)
        assertEquals(listOf("top1", "top2"), tree.playlistIds)
        assertEquals(setOf("a1", "a2", "b1"), tree.nestedPlaylistIds())
        assertEquals(listOf("aaaa" to 0, "bbbb" to 1, "cccc" to 0), tree.allFolders().map { it.first.id to it.second })
        assertEquals("bbbb", tree.folderOf("b1")?.id)
        assertEquals(inner, tree.find("bbbb"))
    }

    @Test
    fun unclosedAndStrayMarkersKeepEveryPlaylist() {
        val tree = SpotifyRootlist.parse(rootlist(
            "spotify:end-group:zzzz",
            "spotify:start-group:aaaa",
            "spotify:playlist:a1",
        ))
        assertEquals(PlaylistFolder("", "", listOf(Folder(PlaylistFolder("aaaa", "", listOf(Item("a1")))))), tree)
    }

    @Test
    fun markersRoundTripTheWebPlayersEncoding() {
        assertEquals("spotify:start-group:abcd:My+mixes+%26+more", SpotifyRootlist.start("abcd", " My  mixes & more "))
        assertEquals("spotify:start-group:abcd", SpotifyRootlist.start("abcd"))
        assertEquals("My mixes & more", SpotifyRootlist.parse(rootlist(SpotifyRootlist.start("abcd", "My mixes & more"))).folders.single().name)
        assertEquals(16, SpotifyRootlist.newId().length)
    }
}
