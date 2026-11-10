package com.aurora.music.data

import com.aurora.music.data.remote.SoundCloudClient
import com.aurora.music.data.remote.SoundCloudTransport
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLikesTest {
    private val dir = java.nio.file.Files.createTempDirectory("aurora-guest-likes-").toFile()
    private val guest = Session.guest(ServerType.SOUNDCLOUD, "Guest")
    private val account = Session(SoundCloudClient.WEB, "listener", "", "opaque-reference", ServerType.SOUNDCLOUD, "42")
    private val tracks = SoundCloudTransport { path ->
        val id = path.removePrefix("/tracks/").toIntOrNull() ?: error(path)
        JsonParser.parseString("""{"id":$id,"kind":"track","title":"Track $id","duration":180000,"user":{"id":7,"username":"Artist"}}""")
    }

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test fun aGuestLikesSongsOnThisDeviceAndKeepsThemAcrossRestarts() = runBlocking {
        val backend = SoundCloudBackend(guest, tracks, guestLikes = GuestLikes(dir))
        assertFalse(backend.signedIn)
        assertTrue(backend.likesLocally("9"))
        // a song left in the queue from another source is not this one's to keep
        assertFalse(backend.likesLocally("4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(backend.setStarred("9", true, "song"))
        assertTrue(backend.setStarred("5", true, "song"))
        // albums, artists and playlists still need the account
        assertFalse(backend.setStarred("3", true, "playlist"))

        val restarted = SoundCloudBackend(guest, tracks, guestLikes = GuestLikes(dir))
        assertEquals(listOf("5", "9"), restarted.starredSongs().map { it.id })
        assertTrue(restarted.starredSongs().all { it.liked && it.title.startsWith("Track") })
        assertEquals(setOf("5", "9"), restarted.starredIds())
        assertEquals(setOf("9"), restarted.likedSongIds(listOf("9", "1")))
        assertEquals(2, restarted.starredCount())

        assertTrue(restarted.setStarred("9", false, "song"))
        assertEquals(listOf("5"), GuestLikes(dir).of(ServerType.SOUNDCLOUD).map { it.id })
    }

    @Test fun aSignedInSourceNeverLikesLocally() {
        assertFalse(SoundCloudBackend(account, tracks, guestLikes = GuestLikes(dir)).likesLocally("9"))
        assertFalse(SoundCloudBackend(guest, tracks).likesLocally("9"))
    }

    @Test fun likesWaitForTheAccountThatTookTheGuestsPlace() = runBlocking {
        val likes = GuestLikes(dir)
        SoundCloudBackend(guest, tracks, guestLikes = likes).setStarred("9", true, "song")
        assertNull(likes.waitingFor(listOf(guest)))
        assertNull(likes.waitingFor(listOf(Session.guest(ServerType.YOUTUBE_MUSIC, "Guest"))))
        assertEquals(account to 1, likes.waitingFor(listOf(guest, account)))
    }

    @Test fun movingHandsEveryLikeToTheAccountAndClearsTheDevice() = runBlocking {
        val likes = GuestLikes(dir)
        val asGuest = SoundCloudBackend(guest, tracks, guestLikes = likes)
        asGuest.setStarred("9", true, "song")
        asGuest.setStarred("5", true, "song")
        val pending = SoundCloudPendingLikes(dir)
        val signedIn = SoundCloudBackend(account, tracks, pending = pending, guestLikes = likes)

        assertEquals(2, likes.moveTo(signedIn, pauseMs = 0))
        assertEquals(0, likes.count(ServerType.SOUNDCLOUD))
        // oldest first, so the account lists them in the order they were liked
        assertEquals(listOf("track:9", "track:5"), pending.of("42").keys.toList())
        assertTrue(pending.of("42").values.all { it.liked })
    }

    @Test fun aGuestCannotBeMovedInto() = runBlocking {
        val likes = GuestLikes(dir)
        val asGuest = SoundCloudBackend(guest, tracks, guestLikes = likes)
        asGuest.setStarred("9", true, "song")
        assertEquals(0, likes.moveTo(asGuest, pauseMs = 0))
        assertEquals(1, likes.count(ServerType.SOUNDCLOUD))
    }
}
