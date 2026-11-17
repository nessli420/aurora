package com.aurora.music.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.data.remote.SoundCloudTransport
import com.aurora.music.model.Song
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuestSourceTest {
    private val guest = Session.guest(ServerType.SOUNDCLOUD, "Guest")
    private val account = Session(guest.server, "listener", "", "opaque-reference", ServerType.SOUNDCLOUD, "42")

    private fun publicOnly(asked: MutableList<String> = mutableListOf()) = SoundCloudTransport { path ->
        asked += path
        check(!path.startsWith("/me") && !path.startsWith("/stream") && "/users/guest" !in path) { "account call $path" }
        JsonParser.parseString(when {
            path.startsWith("/mixed-selections") -> """{"collection":[{"id":"buzzing","title":"Buzzing","items":{"collection":[{"kind":"track","id":5,"title":"Song","user":{"id":7,"username":"artist"}}]}}]}"""
            else -> error(path)
        })
    }

    @Test fun guestSoundCloudBrowsesWithoutTouchingTheAccount() = runBlocking {
        val asked = mutableListOf<String>()
        val backend = SoundCloudBackend(guest, publicOnly(asked))
        assertTrue(guest.isGuest)
        assertFalse(backend.signedIn)
        assertTrue(backend.ping())
        assertEquals(listOf("Buzzing"), backend.home().sections.map { it.title })
        assertTrue(backend.allSongs().isEmpty() && backend.allPlaylists().isEmpty() && backend.allArtists().isEmpty())
        assertTrue(backend.starredIds().isEmpty())
        assertEquals(0, backend.starredCount())
        assertFalse(backend.setStarred("5", true, "song"))
        backend.scrobble("5")
        val song = Song("5", "Song", "artist", "", "", 1)
        assertTrue(backend.supportsComments(song))
        assertFalse(backend.canPostComments(song, ServerType.SOUNDCLOUD))
        assertFalse(backend.supportsReposts("5"))
        assertFalse(backend.isReposted("5"))
        assertFalse(backend.setReposted("5", true))
        assertTrue(asked.all { it.startsWith("/mixed-selections") })
    }

    @Test fun mergedLibraryAnswersPerSource() {
        val merged = MergedBackend(listOf(SoundCloudBackend(account, publicOnly()), SoundCloudBackend(guest, publicOnly())), account)
        val sep = MERGE_NAMESPACE_SEP
        assertTrue(merged.signedIn("0${sep}5"))
        assertFalse(merged.signedIn("1${sep}5"))
        assertFalse(merged.signedIn)
        assertTrue(merged.canPostComments(Song("0${sep}5", "Song", "artist", "", "", 1), ServerType.SOUNDCLOUD))
        assertFalse(merged.canPostComments(Song("1${sep}5", "Song", "artist", "", "", 1), ServerType.SOUNDCLOUD))
        // the guest's own soundcloud answers for its songs, not the account's
        assertEquals(listOf(ServerType.SOUNDCLOUD), merged.commentSources(Song("1${sep}5", "Song", "artist", "", "", 1)))
        assertTrue(merged.supportsReposts("0${sep}5"))
        assertFalse(merged.supportsReposts("1${sep}5"))
        assertFalse(merged.supportsReposts("5"))
    }

    @Test fun signingInTakesTheGuestsPlace() = runBlocking {
        val directory = Files.createTempDirectory("aurora-guest-").toFile()
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            File(directory, "aurora_settings.preferences_pb")
        }, directory, directory)
        val youtube = Session.guest(ServerType.YOUTUBE_MUSIC, "Guest")
        store.addSavedSession(guest)
        store.addSavedSession(youtube)
        store.setMergeSources(setOf(guest.accountKey(), "other"))
        store.addSavedSession(account)
        assertEquals(listOf(youtube, account), store.savedSessions.first())
        assertEquals(setOf("other", account.accountKey()), store.mergeSources.first())
        directory.deleteRecursively()
        Unit
    }
}
