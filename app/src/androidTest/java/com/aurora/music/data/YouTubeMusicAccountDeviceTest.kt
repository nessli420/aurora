package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.remote.YouTubeMusicClient
import com.aurora.music.data.remote.YouTubeMusicWebSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class YouTubeMusicAccountDeviceTest {
    @Test fun mergedLibraryOffersBothHomesAndYoutubeCatalogueSearch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as AuroraApplication).container
        val store = container.settingsStore
        val account = store.session.first()
        assumeTrue(account?.type == ServerType.YOUTUBE_MUSIC)
        val unified = store.unifiedLibrary.first()
        val included = store.mergeSources.first()
        try {
            store.setMergeSources(setOf(account!!.accountKey()))
            store.setUnifiedLibrary(true)
            kotlinx.coroutines.withTimeout(10_000) {
                while (container.repository.homeFeeds.size != 2) kotlinx.coroutines.delay(50)
            }
            val regular = container.repository.home("library")
            assertTrue("Regular home includes recommendation shelves", regular.sections.isEmpty())
            val discovery = container.repository.home("discovery")
            assertTrue("YouTube home lost its shelves", discovery.sections.isNotEmpty())
            discovery.continuation?.let { assertTrue(container.repository.homePage(it, "discovery").sections.isNotEmpty()) }
            val result = container.repository.search("GLM-RST still love you")
            assertTrue("Catalogue search is empty", result.songs.isNotEmpty() || result.albums.isNotEmpty())
            assertTrue(result.songs.all { it.streamUrl.startsWith("aurora-yt:") })
            val source = container.backend
            val original = store.audioPrefs.first()
            try {
                store.importPrefs(PrefsBackup(floats = mapOf("dsp_preamp" to original.dspPreampDb - 0.25f)))
                kotlinx.coroutines.delay(300)
                assertSame("DSP edits rebuilt the merged provider", source, container.backend)
            } finally { store.importPrefs(PrefsBackup(floats = mapOf("dsp_preamp" to original.dspPreampDb))) }
        } finally {
            store.setMergeSources(included)
            store.setUnifiedLibrary(unified)
        }
    }

    @Test fun dspPreferenceWritesKeepSourceAndLibraryStable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as AuroraApplication).container
        val store = container.settingsStore
        assumeTrue(store.session.first()?.type == ServerType.YOUTUBE_MUSIC)
        kotlinx.coroutines.delay(1_000)
        val source = container.backend
        assertNotNull(source)
        val epoch = container.accountEpoch.value
        val reload = container.libraryReload.value
        val original = store.audioPrefs.first()
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val observer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { container.sourceErrors.collect { errors += it } }
        try {
            val changes = listOf("dsp_preamp" to original.dspPreampDb - 0.25f,
                "dsp_balance" to (if (original.dspBalance < 0.9f) original.dspBalance + 0.01f else original.dspBalance - 0.01f),
                "dsp_width" to (if (original.dspWidth > 0.01f) original.dspWidth - 0.01f else original.dspWidth + 0.01f))
            for ((key, value) in changes) {
                store.importPrefs(PrefsBackup(floats = mapOf(key to value)))
                kotlinx.coroutines.delay(250)
                assertSame("DSP change rebuilt the provider", source, container.backend)
                assertEquals("DSP change reloaded the library", reload, container.libraryReload.value)
                assertEquals(epoch, container.accountEpoch.value)
            }
            assertTrue("DSP changes reported a source failure", errors.isEmpty())
        } finally {
            store.importPrefs(PrefsBackup(floats = mapOf("dsp_preamp" to original.dspPreampDb,
                "dsp_balance" to original.dspBalance, "dsp_width" to original.dspWidth)))
            observer.cancel()
        }
    }

    @Test fun savedAccountCanReadHomeAndLibraryAfterBrowserCloses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as AuroraApplication).container
        val session = container.settingsStore.session.first()
        assumeTrue("Requires a user-connected YouTube Music account", session?.type == ServerType.YOUTUBE_MUSIC)
        val auth = YouTubeMusicWebSession.decode(container.youtubeMusicCredentials.read(session!!.token))
        val api = YouTubeMusicClient({ auth })
        assertTrue(YouTubeMusicBackend.account(api, auth.dataSyncId).first.isNotBlank())
        val backend = YouTubeMusicBackend(session, api)
        val home = backend.home()
        assertTrue("YouTube home shelves were lost", home.sections.isNotEmpty())
        val sections = home.sections.toMutableList()
        var token = home.continuation
        repeat(3) {
            token?.let { current ->
                val page = backend.homePage(current)
                sections += page.sections
                token = page.continuation
            }
        }
        assertTrue("Personalized playlists or mixes were lost", sections.any { shelf -> shelf.items.any { it is HomeFeedItem.Collection } })
        assertTrue("Recommended songs were lost", sections.any { shelf -> shelf.items.any { it is HomeFeedItem.Track } })
        File(context.getExternalFilesDir(null), "youtube-feed-check.txt").writeText(
            "Home shelves=${sections.size}, items=${sections.sumOf { it.items.size }}\n" + sections.joinToString("\n") { it.title })
        val featuredPlaylist = sections.flatMap { it.items }.filterIsInstance<HomeFeedItem.Collection>().first()
        assertTrue("Featured mix does not open", backend.detail("playlist", featuredPlaylist.playlist.id)?.tracks?.isNotEmpty() == true)
        backend.starredIds()
        val playlists = backend.allPlaylists()
        val albums = backend.allAlbums()
        val songs = backend.allSongs()
        // Reports contain feed labels and counts, never account credentials.
        File(context.getExternalFilesDir(null), "youtube-account-check.txt").writeText(
            "Account validation passed.\nHome shelves=${sections.size}, items=${sections.sumOf { it.items.size }}\n" +
                "Sections: ${sections.joinToString { it.title }}\n" +
                "Library playlists=${playlists.size}, albums=${albums.size}, songs=${songs.size}\n")
        assertTrue(songs.all { it.streamUrl.startsWith("aurora-yt://video/") })
    }
}
