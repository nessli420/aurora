package com.aurora.music.foryou

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.data.DetailData
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeSection
import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalCatalog
import com.aurora.music.data.LocalStore
import com.aurora.music.data.Lyrics
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MergedBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlayEvent
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.SearchResults
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SettingsStore
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class MadeForYouTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun stop() = scope.cancel()

    private val day = MixClock.DAY
    // 2026-11-18 20:00 utc
    private var time = 1_795_032_000_000L

    private fun song(id: String, artist: String) = Song(id, "Title $id", artist, "Album", "", 200, artistId = artist)

    private val library = listOf("Rock A", "Rock B", "Rock C", "Calm A", "Calm B", "Calm C").flatMap { artist -> (1..12).map { song("${artist.replace(" ", "")}$it", artist) } }
    private val catalogue = (1..60).map { song("new$it", "Newcomer ${it % 30}").copy(artworkUrl = "https://art.invalid/new$it.jpg") }

    private inner class Source : MediaBackend {
        var asked = 0
        override val session = Session("https://mixes.invalid", "listener", "salt", "token", ServerType.SUBSONIC, "user")
        override suspend fun ping() = true
        override suspend fun home() = HomeData()
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = library.also { asked++ }
        override suspend fun starredSongs() = library.take(3)
        override suspend fun starredCount() = 3
        override suspend fun starredIds() = library.take(3).map { it.id }.toSet()
        override suspend fun songFor(id: String) = (library + catalogue).firstOrNull { it.id == id }
        override suspend fun search(query: String) = SearchResults()
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = catalogue
        override suspend fun createPlaylist(name: String) = false
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = false
        override suspend fun deletePlaylist(id: String) = false
        override suspend fun setStarred(id: String, starred: Boolean, kind: String) = false
        override suspend fun detail(kind: String, id: String): DetailData? = if (kind != "artist" || id.startsWith("Newcomer")) null
            else DetailData(com.aurora.music.model.DetailInfo(id, "", "https://faces.invalid/${id.replace(" ", "")}.jpg", 0, true, 0, "Artist"), emptyList())
        override suspend fun serverLyrics(song: Song): Lyrics? = null
        override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = ""
        override fun coverArtUrl(id: String, size: Int) = ""
    }

    private val nothingOnDevice = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, _ ->
        when (method.name) {
            "getSongs", "getAlbums", "getArtists" -> emptyList<Any>()
            "ensureLoaded" -> Unit
            "song", "artist", "findMatch" -> null
            else -> error("Unexpected ${method.name}")
        }
    } as LocalCatalog

    private inner class World(kinds: Set<MixKind> = MixKind.entries.toSet()) {
        val dir: File = temp.newFolder()
        val source = Source()
        val store = LocalStore(dir)
        val merged = MergedBackend(listOf(LocalBackend(nothingOnDevice, store, Session("local", "Me", "", "local", ServerType.LOCAL)), source), source.session)
        val history = PlayHistoryStore(dir)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "mixes.preferences_pb") }, dir, dir)
        val kinds = MutableStateFlow(kinds)
        lateinit var made: MadeForYou
        val repository = MusicRepository({ merged }, DownloadManager(dir, { "file://$it" }, { null }), mixes = { made.detail(it) })

        fun service() = MadeForYou(repository, history, File(dir, "made-for-you.json"), { "listener" }, this.kinds, settings.mixSnapshots,
            { id, playlist -> settings.setMixLiked(id, playlist) }, { settings.keepMixLikes(it) },
            { MixName("Mix ${it.slot}", it.artists.joinToString(), "Copy of ${it.slot}") }, now = { time }, offset = { 0 }, seed = 3, settleMs = 0)

        init {
            made = service()
            runBlocking {
                // rock in the evenings and calm in the mornings, never in the same sitting
                val songs = merged.allSongs()
                val rock = songs.filter { it.artist.startsWith("Rock") }
                val calm = songs.filter { it.artist.startsWith("Calm") }
                fun play(s: Song, at: Long) = PlayEvent(s.id, s.title, s.artist, s.album, "", s.artistId, "", s.durationSec, at)
                history.restore((1..20).flatMap { d ->
                    val evening = time - d * day
                    (0 until 6).map { i -> play(rock[(d * 5 + i * 7) % rock.size], evening + i * 240_000L) } +
                        (0 until 6).map { i -> play(calm[(d * 3 + i * 5) % calm.size], evening - 12 * MixClock.HOUR + i * 240_000L) }
                })
            }
            source.asked = 0
        }
    }

    @Test fun aLikeFreezesTheEditionAsALocalPlaylist() = runBlocking {
        val w = World()
        w.made.refresh()
        val mix = w.made.mixes.value.first { it.kind == MixKind.DAILY }

        assertEquals(true, w.made.toggleLike(mix.id))
        assertTrue(mix.id in w.settings.likedPlaylists.first())
        val playlist = w.settings.mixSnapshots.first().getValue(mix.id)
        assertEquals("Copy of ${mix.slot}", w.store.playlists().single().title)
        val frozen = w.repository.detail("playlist", playlist)!!.tracks.map { it.id }
        assertEquals(mix.songs.map { it.id }, frozen)

        time += day
        w.made.refresh()
        val next = w.made.mixes.value.first { it.slot == mix.slot }
        assertNotEquals(mix.id, next.id)
        assertNotEquals(mix.songs.map { it.id }, next.songs.map { it.id })
        assertEquals("the saved copy keeps yesterday's tracks", frozen, w.repository.detail("playlist", playlist)!!.tracks.map { it.id })
        assertFalse("the new edition starts unliked", next.id in w.settings.likedPlaylists.first())
        assertFalse(mix.id in w.settings.likedPlaylists.first())
        assertEquals(1, w.store.playlists().size)

        assertEquals(true, w.made.toggleLike(next.id))
        assertEquals(2, w.store.playlists().size)
        assertEquals(false, w.made.toggleLike(next.id))
        assertEquals("unliking removes only its own copy", listOf("Copy of ${mix.slot}"), w.store.playlists().map { it.title })
        assertFalse(next.id in w.settings.likedPlaylists.first())
        assertTrue(w.settings.mixSnapshots.first().isEmpty())
    }

    @Test fun everyKindComesFromTheOneLibraryAndOnlyTheCurrentPartShows() = runBlocking {
        val w = World()
        w.made.refresh()
        val mixes = w.made.mixes.value
        assertEquals(setOf(MixKind.TIME, MixKind.DAILY, MixKind.RECOMMENDED, MixKind.DISCOVER), mixes.mapTo(HashSet()) { it.kind } + MixKind.RECOMMENDED)
        assertEquals(listOf(DayPart.EVENING), mixes.filter { it.kind == MixKind.TIME }.map { it.part })
        assertTrue(mixes.first { it.kind == MixKind.DISCOVER }.songs.all { it.artist.startsWith("Newcomer") })

        // the morning mix was built with the rest and only has to be shown
        time += 12 * MixClock.HOUR
        val before = w.source.asked
        w.made.refresh()
        assertEquals(listOf(DayPart.MORNING), w.made.mixes.value.filter { it.kind == MixKind.TIME }.map { it.part })
        assertTrue(w.made.mixes.value.first { it.kind == MixKind.TIME }.songs.all { it.artist.startsWith("Calm") })
    }

    @Test fun eachMixWearsAPictureOfOneOfItsArtists() = runBlocking {
        val w = World()
        w.made.refresh()
        val mixes = w.made.mixes.value
        val daily = mixes.filter { it.kind == MixKind.DAILY }
        assertEquals(2, daily.size)
        daily.forEach { mix ->
            assertTrue(mix.art, mix.art.startsWith("https://faces.invalid/"))
            assertTrue("the picture is of an artist in the mix", mix.songs.any { it.artist.replace(" ", "") in mix.art })
            assertEquals(MIX_COVER_PREFIX + mix.slot + "|" + mix.art, mix.coverUrl)
        }
        val shown = mixes.filter { it.kind != MixKind.DISCOVER }.map { it.art }
        assertEquals("no two tiles on the row share a face: $shown", shown.size, shown.distinct().size)
        val discover = mixes.first { it.kind == MixKind.DISCOVER }
        assertTrue("an artist the source has no picture of falls back to the track's art", discover.art in discover.songs.map { it.artworkUrl })

        val restarted = w.service()
        restarted.refresh()
        assertEquals(mixes.map { it.coverUrl }, restarted.mixes.value.map { it.coverUrl })
    }

    @Test fun theCacheOpensHomeWithoutAskingTheLibraryAgain() = runBlocking {
        val w = World()
        w.made.refresh()
        val built = w.made.mixes.value.map { it.id to it.songs.map { s -> s.id } }
        assertTrue(built.isNotEmpty())
        val asked = w.source.asked

        w.made.refresh()
        val restarted = w.service()
        restarted.refresh()
        assertEquals(built, restarted.mixes.value.map { it.id to it.songs.map { s -> s.id } })
        assertEquals(asked, w.source.asked)

        restarted.invalidate()
        restarted.refresh()
        assertTrue("a library change builds again", w.source.asked > asked)
        assertEquals("and the same day gives the same mixes", built, restarted.mixes.value.map { it.id to it.songs.map { s -> s.id } })
    }

    @Test fun aMixOpensLikeAnyCollection() = runBlocking {
        val w = World()
        w.made.refresh()
        val mix = w.made.mixes.value.first()
        val page = w.repository.detail(MIX_KIND, mix.id)!!
        assertEquals("Mix ${mix.slot}", page.info.title)
        assertEquals(mix.coverUrl, page.info.artUrl)
        assertEquals(mix.songs.map { it.id }, page.tracks.map { it.id })
        assertEquals(mix.songs.map { it.id }, w.repository.collectionTracks(MIX_KIND, mix.id).map { it.id })
        assertEquals("an edition that was replaced opens the current one", mix.songs.size, w.repository.detail(MIX_KIND, "${mix.slot}@1")!!.tracks.size)
        assertNull(w.repository.detail(MIX_KIND, "daily-99@1"))
        assertNull("only the edition on the page can be liked", w.made.toggleLike("${mix.slot}@1"))
        assertNull(w.made.toggleLike("daily-99@1"))
        assertTrue(w.store.playlists().isEmpty())
    }

    @Test fun switchedOffNothingIsBuilt() = runBlocking {
        val w = World(emptySet())
        w.made.refresh()
        assertTrue(w.made.mixes.value.isEmpty())
        assertEquals(0, w.source.asked)

        w.kinds.value = setOf(MixKind.DAILY)
        w.made.refresh()
        assertTrue(w.made.mixes.value.isNotEmpty())
        assertTrue(w.made.mixes.value.all { it.kind == MixKind.DAILY })

        w.kinds.value = setOf(MixKind.DAILY, MixKind.DISCOVER)
        w.made.refresh()
        assertTrue("a kind switched on later is built on the next look", w.made.mixes.value.any { it.kind == MixKind.DISCOVER })
    }

    @Test fun theHomeSwitchesChooseTheKinds() {
        assertEquals(MixKind.entries.toSet(), mixKinds(emptySet()))
        assertEquals(emptySet<MixKind>(), mixKinds(setOf(HomeSection.MIXES)))
        assertEquals(setOf(MixKind.TIME, MixKind.RECOMMENDED), mixKinds(setOf(HomeSection.MIX_DAILY, HomeSection.MIX_DISCOVER, HomeSection.HERO)))
    }
}
