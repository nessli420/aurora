package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class MergedCombinedHomeTest {
    @get:Rule val temp = TemporaryFolder()

    private class Source(name: String, type: ServerType, private val data: HomeData, private val albums: List<Album> = emptyList()) : MediaBackend {
        override val session = Session("https://$name.invalid", name, "salt", "token", type, "user-$name")
        override suspend fun ping() = true
        override suspend fun home() = data
        override suspend fun allAlbums() = albums
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = emptyList<Song>()
        override suspend fun starredSongs() = emptyList<Song>()
        override suspend fun starredCount() = 0
        override suspend fun starredIds() = emptySet<String>()
        override suspend fun songFor(id: String): Song? = null
        override suspend fun search(query: String) = SearchResults()
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = emptyList<Song>()
        override suspend fun createPlaylist(name: String) = false
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = false
        override suspend fun deletePlaylist(id: String) = false
        override suspend fun setStarred(id: String, starred: Boolean, kind: String) = false
        override suspend fun detail(kind: String, id: String): DetailData? = null
        override suspend fun serverLyrics(song: Song): Lyrics? = null
        override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = ""
        override fun coverArtUrl(id: String, size: Int) = ""
    }

    private fun album(id: String, title: String, artist: String = "Artist") = Album(id, title, artist, "", 2020, 1)
    private fun shelf(id: String) = HomeFeedSection(id, "Shelf $id", items = listOf(HomeFeedItem.Record(album(id, "Record $id", "Artist $id"))))

    private val shared = album("d1", "Shared")
    private val catalog = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, _ ->
        when (method.name) {
            "getAlbums" -> listOf(shared, album("d2", "Device only"))
            "getArtists" -> emptyList<Artist>()
            "getSongs" -> emptyList<Song>()
            "ensureLoaded" -> Unit
            else -> error("Unexpected ${method.name}")
        }
    } as LocalCatalog
    private val localSession = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)

    private fun device() = LocalBackend(catalog, LocalStore(temp.newFolder()), localSession)
    private fun server() = Source("nas", ServerType.SUBSONIC, HomeData(newReleases = listOf(album("s1", "Shared"), album("s2", "Server only"))))
    private fun youtube() = Source("yt", ServerType.YOUTUBE_MUSIC, HomeData(sections = listOf(shelf("y0"), shelf("y1"), shelf("y2"))),
        albums = listOf(album("ya", "YouTube album")))
    private fun soundcloud() = Source("sc", ServerType.SOUNDCLOUD, HomeData(sections = listOf(shelf("c0"))))

    @Test fun theCombinedHomeTakesEverySourceDedupesAndTakesShelvesInTurn() = runBlocking {
        val merged = MergedBackend(listOf(device(), server(), youtube(), soundcloud()), localSession)
        val home = merged.home("library")
        assertEquals(listOf("Shared", "Device only", "Server only"), home.newReleases.map { it.title })
        assertEquals(listOf("Shelf y0", "Shelf c0", "Shelf y1", "Shelf y2"), home.sections.map { it.title })
        assertEquals(home, merged.homeUpdates("library").toList().last())
    }

    @Test fun theLocalHomeKeepsTheDeviceAndServersWithoutTheCatalogues() = runBlocking {
        val merged = MergedBackend(listOf(device(), server(), youtube()), localSession)
        val choice = merged.localHome!!
        assertEquals(LOCAL_HOME_FEED, choice.id)
        assertEquals("Local & servers", choice.label)
        val home = merged.home(LOCAL_HOME_FEED)
        assertEquals(listOf("Shared", "Device only", "Server only"), home.newReleases.map { it.title })
        assertTrue(home.sections.isEmpty())
        assertEquals(home, merged.homeUpdates(LOCAL_HOME_FEED).toList().last())
        assertEquals("On this device", MergedBackend(listOf(device(), youtube()), localSession).localHome!!.label)
    }

    @Test fun onlyAMergeWithTheDevicesFilesAndACatalogueOffersTheLocalHome() {
        assertNull(MergedBackend(listOf(device(), server()), localSession).localHome)
        val storeOnly = LocalBackend(NoDeviceFiles, LocalStore(temp.newFolder()), localSession)
        assertNull(MergedBackend(listOf(storeOnly, server(), youtube()), localSession).localHome)
        assertNull(MergedBackend(listOf(device(), youtube()), localSession).libraryView("library").localHome)
    }

    @Test fun withoutTheDeviceOrAServerTheLibraryIsEverySource() = runBlocking {
        val storeOnly = LocalBackend(NoDeviceFiles, LocalStore(temp.newFolder()), localSession)
        val yt = youtube()
        val merged = MergedBackend(listOf(storeOnly, yt, soundcloud()), yt.session)
        assertSame(merged, merged.libraryView("library"))
        assertEquals(listOf("YouTube album"), merged.libraryView("library").allAlbums().map { it.title })
        assertEquals("Library", merged.libraryViews.first().label)
        assertEquals(listOf("discovery", "soundcloud"), merged.searchSources.map { it.id })
        assertEquals(emptyList<Album>(), merged.libraryView(LOCAL_HOME_FEED).allAlbums())
        assertTrue(!storeOnly.supportsFolders)
        assertEquals(listOf("Shelf y0", "Shelf c0", "Shelf y1", "Shelf y2"), merged.home("library").sections.map { it.title })
    }
}
