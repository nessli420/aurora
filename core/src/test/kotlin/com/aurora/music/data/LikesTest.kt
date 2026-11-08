package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class LikesTest {
    private class Account(name: String, type: ServerType, val catalogue: List<Song>, liked: List<String>, guest: Boolean = false,
        val gate: CompletableDeferred<Unit>? = null) : MediaBackend {
        override val session = if (guest) Session.guest(type, name) else Session("https://$name.invalid", name, "salt", "token", type, "user-$name")
        override val signedIn = !guest
        val liked = liked.toMutableSet()
        val calls = mutableListOf<String>()
        var refuse = false
        override suspend fun ping() = true
        override suspend fun home() = HomeData()
        override suspend fun allAlbums() = emptyList<Album>()
        override suspend fun allArtists() = emptyList<Artist>()
        override suspend fun allPlaylists() = emptyList<Playlist>()
        override suspend fun allSongs() = catalogue
        override suspend fun starredSongs() = catalogue.filter { it.id in liked }
        override suspend fun starredCount() = liked.size
        override suspend fun starredIds(): Set<String> { gate?.let { calls += "starredIds"; it.await() }; return liked.toSet() }
        override suspend fun songFor(id: String) = catalogue.firstOrNull { it.id == id }
        override suspend fun search(query: String) = SearchResults(songs = catalogue.filter { query.contains(it.title, ignoreCase = true) })
        override suspend fun scrobble(id: String) = Unit
        override suspend fun radio(seedId: String) = emptyList<Song>()
        override suspend fun createPlaylist(name: String) = false
        override suspend fun updatePlaylist(id: String, name: String?, comment: String?) = false
        override suspend fun deletePlaylist(id: String) = false
        override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean {
            calls += "${if (starred) "like" else "unlike"} $id"
            if (refuse) return false
            if (starred) liked += id else liked -= id
            return true
        }
        override suspend fun detail(kind: String, id: String): DetailData? = null
        override suspend fun serverLyrics(song: Song): Lyrics? = null
        override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean) = ""
        override fun coverArtUrl(id: String, size: Int) = ""
    }

    private fun song(id: String, title: String, artist: String, seconds: Int) = Song(id, title, artist, "Album", "", seconds)

    private val dir: File = Files.createTempDirectory("aurora-likes").toFile()
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val navidrome = Account("navidrome", ServerType.SUBSONIC, listOf(song("n1", "Blue Hour", "The Band", 200), song("n2", "Red Sky", "The Band", 180)), emptyList())
    private val spotify = Account("spotify", ServerType.SPOTIFY, listOf(song("sp1", "Blue Hour (feat. Guest)", "Band", 202), song("sp2", "Red Sky", "Band", 181)), listOf("sp1"))
    private val soundcloud = Account("soundcloud", ServerType.SOUNDCLOUD, listOf(song("sc1", "Blue Hour", "The Band", 200)), emptyList(), guest = true)
    private val merged = MergedBackend(listOf(navidrome, spotify, soundcloud), navidrome.session)
    private val repository = MusicRepository({ merged }, DownloadManager(dir, { "file://$it" }, { null }))
    private val likes = Likes(scope, repository) { _, _ -> }

    @After fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
        dir.deleteRecursively()
    }

    private fun idOf(source: MediaBackend, id: String) = "${mergeNamespace(source.session)}$MERGE_NAMESPACE_SEP$id"
    private val blue = song(idOf(navidrome, "n1"), "Blue Hour", "The Band", 200)
    private val red = song(idOf(navidrome, "n2"), "Red Sky", "The Band", 180)

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    private fun test(block: suspend () -> Unit) = runBlocking(dispatcher) { block() }

    // accounts without likes never load, since servers answer a failure with an empty list
    private suspend fun loaded(vararg accounts: Account) {
        likes.refresh()
        likes.check(listOf(blue, red))
        until { accounts.all { likes.ledger.likedOn(mergeNamespace(it.session), blue) != null } }
    }

    @Test fun aRestartedRefreshDropsTheFetchStillRunningOnTheOldLibrary() = test {
        val gate = CompletableDeferred<Unit>()
        val old = Account("old", ServerType.SUBSONIC, emptyList(), listOf("o1"), gate = gate)
        var active: MediaBackend = old
        val switching = Likes(scope, MusicRepository({ active }, DownloadManager(dir, { "file://$it" }, { null }))) { _, _ -> }
        switching.refresh()
        until { "starredIds" in old.calls }
        active = Account("new", ServerType.SUBSONIC, emptyList(), listOf("n1"))
        switching.refresh(restart = true)
        until { "n1" in switching.likedIds.value }
        gate.complete(Unit)
        delay(50)
        assertEquals(setOf("n1"), switching.likedIds.value)
    }

    @Test fun aSongLikedOnAnotherAccountIsLikedEverywhereItShows() = test {
        navidrome.liked += "n2"
        loaded(navidrome, spotify)
        until { blue.id in likes.likedIds.value }
        assertTrue(likes.ledger.likedAnywhere(blue))
        until { red.id in likes.likedIds.value }
        assertFalse(likes.ledger.likedAnywhere(song("x", "Blue Hour", "Someone Else", 200)))
        assertFalse(likes.ledger.likedAnywhere(song("y", "Blue Hour", "The Band", 260)))
    }

    @Test fun aTapUnlikesOnlyOnThePlayingSourceAndTheHeartStaysForTheOtherAccount() = test {
        navidrome.liked += "n1"
        loaded(navidrome, spotify)
        likes.toggle(blue)
        until { "unlike n1" in navidrome.calls }
        assertEquals(setOf("sp1"), spotify.liked)
        assertTrue(navidrome.liked.isEmpty())
        until { blue.id in likes.likedIds.value }

        likes.toggle(blue)
        until { "like n1" in navidrome.calls }
        assertEquals(setOf("n1"), navidrome.liked)
    }

    @Test fun theMenuListsEveryAccountAndChangesTheOneChosen() = test {
        loaded(spotify)
        val options = likes.options(blue)
        assertEquals(listOf("Navidrome", "Spotify", "SoundCloud"), options.map { it.label })
        val (own, other, guest) = options
        assertTrue(own.playing && !own.liked && own.available)
        assertTrue(other.liked && other.id == "sp1")
        assertTrue(guest.guest && !guest.available)

        val unliked = likes.set(blue, other, false)
        assertEquals(listOf("unlike sp1"), spotify.calls)
        assertFalse(unliked.first { it.key == other.key }.liked)
        until { blue.id !in likes.likedIds.value }

        val redOptions = likes.options(red)
        val twin = redOptions.first { it.key == other.key }
        assertEquals("sp2", twin.id)
        likes.set(red, twin, true)
        assertEquals(setOf("sp2"), spotify.liked)
        until { red.id in likes.likedIds.value }
    }

    @Test fun theLikedSongsPickerListsTheLibrarySourcesWithLikes() = test {
        navidrome.liked += "n2"
        loaded(navidrome, spotify)
        assertEquals(listOf("Navidrome" to 1, "Spotify" to 1), likes.likedSources().map { it.label to it.count })
    }

    @Test fun aRefusedLikeOnAnotherAccountIsTakenBack() = test {
        loaded(spotify)
        spotify.refuse = true
        val twin = likes.options(red).first { it.key == mergeNamespace(spotify.session) }
        val after = likes.set(red, twin, true)
        assertFalse(after.first { it.key == twin.key }.liked)
        assertFalse(likes.ledger.likedAnywhere(red))
        assertNull(soundcloud.calls.firstOrNull())
    }

    @Test fun signedInAccountsOutsideTheLibraryJoinOnceAndSpotifyOnlyOnce() {
        val built = mutableListOf<Session>()
        val accounts = LikeAccounts { built += it; Account(it.username, it.type, emptyList(), emptyList()) }
        val otherSpotify = Session("https://open.spotify.com", "second", "salt", "token", ServerType.SPOTIFY, "user-second")
        val jellyfin = Session("https://jelly.invalid", "jelly", "salt", "token", ServerType.JELLYFIN, "user-jelly")
        val saved = listOf(navidrome.session, otherSpotify, jellyfin, Session("x", "ext", "", "t", ServerType.EXTENSION))
        val list = accounts.of(merged, saved)
        assertEquals(listOf(navidrome, spotify, soundcloud), list.take(3))
        assertEquals(listOf(jellyfin), built)
        assertTrue(accounts.of(merged, saved) === list)
        assertEquals(listOf("navidrome", "second", "jelly"), accounts.of(navidrome, saved).map { it.session.username })
    }
}
