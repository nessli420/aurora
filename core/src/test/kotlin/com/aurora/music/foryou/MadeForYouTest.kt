package com.aurora.music.foryou

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.data.DetailData
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeSection
import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalCatalog
import com.aurora.music.data.LOCAL_PLAYLIST_PREFIX
import com.aurora.music.data.LocalStore
import com.aurora.music.data.ORIGIN_MIX
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    private inner class Source(val extras: Boolean) : MediaBackend {
        var asked = 0
        var portraits = 0
        // anything that would change the listener's account
        val written = ArrayList<String>()
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
        override suspend fun search(query: String) = SearchResults(songs = if (!extras || library.none { it.artist == query }) emptyList()
            else (1..6).map { song("only-${query.replace(" ", "")}$it", query) } + song("stray-$query", "Somebody Else"))
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = catalogue
        override suspend fun createPlaylist(name: String) = true.also { written += "create $name" }
        override suspend fun createPlaylistWithId(name: String): String? = "on-the-account".also { written += "create $name" }
        override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) = true.also { written += "add to $playlistId" }
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = true.also { written += "update $id" }
        override suspend fun deletePlaylist(id: String) = true.also { written += "delete $id" }
        override suspend fun setStarred(id: String, starred: Boolean, kind: String) = true.also { written += "star $kind $id" }
        override suspend fun detail(kind: String, id: String): DetailData? = if (kind != "artist" || id.startsWith("Newcomer")) null
            else DetailData(com.aurora.music.model.DetailInfo(id, "", "https://faces.invalid/${id.replace(" ", "")}.jpg", 0, true, 0, "Artist"), emptyList()).also { portraits++ }
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

    private inner class World(kinds: Set<MixKind> = MixKind.entries.toSet(), extras: Boolean = true, alone: Boolean = false, shared: LocalStore? = null) {
        val dir: File = temp.newFolder()
        val source = Source(extras)
        // aurora's playlists outlive a change of library; everything else here belongs to one session
        val store = shared ?: LocalStore(dir)
        val merged = MergedBackend(listOf(LocalBackend(nothingOnDevice, store, Session("local", "Me", "", "local", ServerType.LOCAL)), source), source.session)
        val history = PlayHistoryStore(dir)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "mixes.preferences_pb") }, dir, dir)
        val kinds = MutableStateFlow(kinds)
        var account = "listener"
        lateinit var made: MadeForYou
        // alone: the streaming account is the whole library, with no merged library around it
        val backend: MediaBackend = if (alone) source else merged
        val repository = MusicRepository({ backend }, DownloadManager(dir, { "file://$it" }, { null }), mixes = { made.detail(it) }, ownPlaylists = store)

        fun service() = MadeForYou(repository, history, File(dir, "made-for-you.json"), { account }, this.kinds, settings.mixSnapshots,
            { id, playlist -> settings.setMixLiked(id, playlist) }, { settings.keepMixLikes(it) },
            { MixName("Mix ${it.slot}", it.artists.joinToString(), "Copy of ${it.slot}") }, now = { time }, offset = { 0 }, seed = 3, settleMs = 0)

        init {
            made = service()
            runBlocking {
                // rock in the evenings and calm in the mornings, never in the same sitting
                val songs = backend.allSongs()
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
        assertEquals(ORIGIN_MIX, w.store.playlists().single().origin)
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
        assertTrue("the account was never written to: ${w.source.written}", w.source.written.isEmpty())
    }

    @Test fun onAStreamingAccountAloneALikedMixIsStillAuroraOwnPlaylist() = runBlocking {
        val w = World(alone = true)
        w.made.refresh()
        val mix = w.made.mixes.value.first { it.kind == MixKind.DAILY }
        assertTrue(w.repository.allPlaylists().isEmpty())

        assertEquals(true, w.made.toggleLike(mix.id))
        val saved = w.store.playlists().single()
        assertEquals(ORIGIN_MIX, saved.origin)
        assertTrue(saved.id.startsWith(LOCAL_PLAYLIST_PREFIX))
        assertEquals(saved.id, w.settings.mixSnapshots.first().getValue(mix.id))
        assertTrue("nothing was created on the account: ${w.source.written}", w.source.written.isEmpty())
        assertTrue("it is in the backup with the other local playlists", saved.id in w.store.exportJson())

        val listed = w.repository.allPlaylists().single()
        assertEquals(saved.id to "Copy of ${mix.slot}", listed.id to listed.title)
        assertEquals(mix.songs.size, listed.songCount)
        val page = w.repository.detail("playlist", saved.id)!!
        assertEquals("the tracks come back under the account's own ids", mix.songs.map { it.id }, page.tracks.map { it.id })
        assertEquals(mix.songs.map { it.id }, w.repository.collectionTracks("playlist", saved.id).map { it.id })
        assertTrue(page.info.editable)

        assertTrue(w.repository.updatePlaylist(saved.id, "Kept", null))
        assertEquals("Kept", w.repository.allPlaylists().single().title)
        assertFalse("its heart has nothing to tell the account", w.repository.setStarred(saved.id, true, "playlist"))
        assertTrue(w.source.written.isEmpty())

        assertEquals(false, w.made.toggleLike(mix.id))
        assertTrue(w.store.playlists().isEmpty())
        assertTrue(w.repository.allPlaylists().isEmpty())
        assertTrue(w.source.written.isEmpty())

        // the account's own playlists still go to the account
        assertTrue(w.repository.deletePlaylist("theirs"))
        assertEquals(listOf("delete theirs"), w.source.written)
    }

    @Test fun aSavedMixFollowsTheAccountBetweenAMergedLibraryAndTheAccountAlone() = runBlocking {
        val alone = World(alone = true)
        alone.made.refresh()
        val mix = alone.made.mixes.value.first { it.kind == MixKind.DAILY }
        assertEquals(true, alone.made.toggleLike(mix.id))
        val id = alone.store.playlists().single().id

        // the same account inside a merged library: the local source lists it and every track routes back to the account
        val merged = World(shared = alone.store)
        val listed = merged.repository.allPlaylists().single()
        assertEquals("Copy of ${mix.slot}", listed.title)
        val tracks = merged.repository.detail("playlist", listed.id)!!.tracks
        assertEquals(mix.songs.map { it.title }, tracks.map { it.title })
        assertEquals(merged.backend.allSongs().filter { s -> mix.songs.any { it.title == s.title } }.map { it.id }.toSet(), tracks.map { it.id }.toSet())

        // and one saved from the merged library opens on the account alone
        merged.made.refresh()
        val other = merged.made.mixes.value.first { it.kind == MixKind.DAILY && it.slot != mix.slot }
        assertEquals(true, merged.made.toggleLike(other.id))
        val again = World(alone = true, shared = alone.store)
        val both = again.repository.allPlaylists()
        assertEquals(setOf("Copy of ${mix.slot}", "Copy of ${other.slot}"), both.map { it.title }.toSet())
        val back = again.repository.detail("playlist", both.first { it.title == "Copy of ${other.slot}" }.id)!!.tracks
        assertEquals(other.songs.map { it.title }, back.map { it.title })
        assertTrue(back.all { s -> library.any { it.id == s.id } })
        assertTrue(alone.source.written.isEmpty() && merged.source.written.isEmpty() && again.source.written.isEmpty())
        assertTrue(id.isNotEmpty())
    }

    @Test fun everyKindComesFromTheOneLibraryAndOnlyTheCurrentPartShows() = runBlocking {
        val w = World()
        w.made.refresh()
        val mixes = w.made.mixes.value
        assertEquals(MixKind.entries.toSet(), mixes.mapTo(HashSet()) { it.kind } + MixKind.RECOMMENDED)
        assertEquals(listOf(DayPart.EVENING), mixes.filter { it.kind == MixKind.TIME }.map { it.part })
        assertTrue(mixes.first { it.kind == MixKind.DISCOVER }.songs.all { it.artist.startsWith("Newcomer") || it.title.startsWith("Title only-") })

        // the morning mix was built with the rest and only has to be shown
        time += 12 * MixClock.HOUR
        val before = w.source.asked
        w.made.refresh()
        assertEquals(listOf(DayPart.MORNING), w.made.mixes.value.filter { it.kind == MixKind.TIME }.map { it.part })
        assertTrue(w.made.mixes.value.first { it.kind == MixKind.TIME }.songs.all { it.artist.startsWith("Calm") })
    }

    @Test fun aStationAsksTheSourcesForItsArtistAndWearsTheirFace() = runBlocking {
        val w = World()
        w.made.refresh()
        val stations = w.made.mixes.value.filter { it.kind == MixKind.STATION }
        assertEquals(MixBuilder.MAX_STATIONS, stations.size)
        assertEquals((1..stations.size).map { "artist-$it" }, stations.map { it.slot })
        stations.forEach { station ->
            val artist = station.artists.first()
            assertEquals("https://faces.invalid/${artist.replace(" ", "")}.jpg", station.art)
            assertEquals(artist, station.songs.first().artist)
            assertTrue("what a search of the source found is in", station.songs.any { it.title.startsWith("Title only-") && it.artist == artist })
            assertTrue("and what its radio plays next to them", station.songs.any { it.artist.startsWith("Newcomer") })
            assertTrue(station.songs.none { it.artist == "Somebody Else" })
            assertEquals("Mix ${station.slot}", w.repository.detail(MIX_KIND, station.id)!!.info.title)
        }
        assertEquals(stations.size, stations.map { it.artists.first() }.distinct().size)

        time += day
        w.made.refresh()
        val next = w.made.mixes.value.filter { it.kind == MixKind.STATION }
        assertTrue("with six artists and five stations, the one that sat out is in today", (next.map { it.artists.first() } - stations.map { it.artists.first() }.toSet()).isNotEmpty())
    }

    @Test fun eachMixWearsAPictureOfOneOfItsArtists() = runBlocking {
        val w = World(MixKind.entries.toSet() - MixKind.STATION, extras = false)
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

    @Test fun theRowIsThereFromItsTilesBeforeTheSessionOrTheTracks() = runBlocking {
        val w = World()
        w.made.refresh()
        val built = w.made.mixes.value
        val tiles = File(w.dir, "made-for-you-tiles.json")
        assertTrue(tiles.exists())
        assertFalse("the tiles hold no tracks", "streamUrl" in tiles.readText())
        assertTrue("${tiles.length()} of ${File(w.dir, "made-for-you.json").length()} bytes", tiles.length() * 10 < File(w.dir, "made-for-you.json").length())
        val asked = w.source.asked

        // a cold start: no session yet, so no account to compare with
        w.account = ""
        w.made = w.service()
        w.made.warm()
        val row = w.made.mixes.value
        assertEquals(built.map { Triple(it.id, it.coverUrl, it.artists) }, row.map { Triple(it.id, it.coverUrl, it.artists) })
        assertTrue("only the tiles were read", row.all { it.songs.isEmpty() })
        assertEquals("a tile opened this early still finds its tracks", built.first().songs.map { it.id }, w.repository.detail(MIX_KIND, row.first().id)!!.tracks.map { it.id })

        w.made.refresh()
        assertEquals(built.map { it.id to it.songs.map { s -> s.id } }, w.made.mixes.value.map { it.id to it.songs.map { s -> s.id } })
        assertEquals("nothing is built without a session", asked, w.source.asked)

        w.account = "listener"
        w.made.recheck()
        w.made.refresh()
        assertEquals(asked, w.source.asked)
        assertEquals(built.map { it.id }, w.made.mixes.value.map { it.id })

        // the session turned out to be someone else's: their own mixes replace the borrowed row
        w.account = "another listener"
        w.made.recheck()
        w.made.refresh()
        assertTrue(w.source.asked > asked)
        assertTrue(w.made.mixes.value.isNotEmpty())
    }

    @Test fun aSwitchedOffRowIsNotShownFromItsTilesEither() = runBlocking {
        val w = World()
        w.made.refresh()
        w.kinds.value = setOf(MixKind.DISCOVER)
        w.made = w.service()
        w.made.warm()
        assertTrue(w.made.mixes.value.isNotEmpty())
        assertTrue(w.made.mixes.value.all { it.kind == MixKind.DISCOVER })
        w.kinds.value = emptySet()
        val off = w.service()
        off.warm()
        assertTrue(off.mixes.value.isEmpty())
    }

    @Test fun anArtistsPictureIsAskedForOnceNotEveryDay() = runBlocking {
        val w = World()
        w.made.refresh()
        val first = w.source.portraits
        assertTrue(first > 0)
        val faces = w.made.mixes.value.filter { it.art.startsWith("https://faces.invalid/") }.associate { it.slot to it.art }

        time += day
        w.made.refresh()
        assertEquals("six artists, all pictured yesterday", first, w.source.portraits)
        assertTrue(w.made.mixes.value.filter { it.kind == MixKind.STATION }.all { it.art.startsWith("https://faces.invalid/") })
        assertTrue(faces.isNotEmpty())

        time += 20 * day
        w.made.refresh()
        assertTrue("after a few weeks the source is asked again", w.source.portraits > first)
    }

    @Test fun aQueueOnAMixIsHandedTheEditionThatReplacesIt() = runBlocking {
        val w = World()
        w.made.refresh()
        val evening = w.made.mixes.value.first { it.kind == MixKind.TIME }
        val tag = w.repository.playbackCollectionIdentity(MIX_KIND, evening.id)
        val queue = evening.songs.map { it.copy(playbackCollection = tag) }
        val playing = MutableStateFlow(queueMix(queue, 2))
        assertEquals(evening.id, playing.value)
        assertNull("nothing moves while the edition stands", withTimeoutOrNull(200) { w.made.following(playing).first() })

        time += 12 * MixClock.HOUR
        w.made.refresh()
        val next = withTimeout(5_000) { w.made.following(playing).first() }
        assertEquals(DayPart.MORNING, next.part)
        val behind = mixFollowUp(queue, 2, emptySet(), w.repository.collectionTracks(MIX_KIND, next.id).map { it.copy(playbackCollection = mixCollection(next.id)) })
        assertTrue(behind.isNotEmpty() && behind.all { it.artist.startsWith("Calm") })
        playing.value = queueMix(queue.take(3) + behind, 2)
        assertEquals(next.id, playing.value)
        assertNull(withTimeoutOrNull(200) { w.made.following(playing).first() })
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
        assertEquals(setOf(MixKind.TIME, MixKind.RECOMMENDED, MixKind.STATION), mixKinds(setOf(HomeSection.MIX_DAILY, HomeSection.MIX_DISCOVER, HomeSection.HERO)))
        assertEquals(MixKind.entries.toSet() - MixKind.STATION, mixKinds(setOf(HomeSection.MIX_STATIONS)))
    }
}
