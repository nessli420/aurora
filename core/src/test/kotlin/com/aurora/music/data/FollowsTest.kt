package com.aurora.music.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.data.remote.SpotifyGraphql
import com.aurora.music.data.remote.YouTubeMusicTransport
import com.aurora.music.data.remote.string
import com.aurora.music.model.Artist
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executors

private fun unsupportedSource(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class FollowsTest {
    private class Source(name: String, type: ServerType = ServerType.SUBSONIC, private val server: Boolean = true,
        @Volatile var followed: List<Artist> = emptyList(), private val refuse: Boolean = false) : MediaBackend by unsupportedSource() {
        override val session = Session("https://$name.invalid", name, "salt", "token", type, "user-$name")
        override val signedIn = true
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun followsOnServer(artistId: String) = server
        override suspend fun followedArtists() = followed
        override suspend fun setFollowed(artistId: String, followed: Boolean): Boolean {
            calls += "$artistId=$followed"
            return !refuse
        }
    }

    private val dir: File = Files.createTempDirectory("aurora-follows").toFile()
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val io = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    @After fun clean() { scope.cancel(); io.cancel(); executor.shutdownNow(); dir.deleteRecursively() }

    private fun store(name: String) = SettingsStore(PreferenceDataStoreFactory.create(scope = io) { File(dir, "$name.preferences_pb") }, dir, dir)
    private fun repository(backend: MediaBackend) = MusicRepository({ backend }, DownloadManager(dir, { "file://$it" }, { null }))
    private fun follows(backend: MediaBackend, settings: SettingsStore) =
        Follows(scope, repository(backend), settings.followedArtists) { a, on -> settings.setArtistFollowed(a, on) }
    private fun json(body: String): JsonObject = JsonParser.parseString(body).asJsonObject
    // follows lives on one thread, as on the main thread in the app
    private suspend fun Follows.on(block: Follows.() -> Unit) = scope.launch { block() }.join()
    private suspend fun <T> Follows.await(flow: kotlinx.coroutines.flow.StateFlow<T>, test: (T) -> Boolean): T = withTimeout(5_000) { flow.first(test) }

    @Test fun spotifyFollowsAreTheLibrarysArtistsAndFollowingAddsToIt() = runBlocking {
        val asked = mutableListOf<Pair<String, JsonObject>>()
        val session = Session(SpotifyWebBackend.SERVER, "Listener", SpotifyWebBackend.WEB_PLAYER, "reference", ServerType.SPOTIFY, "listener")
        val backend = SpotifyWebBackend(session, object : SpotifyGraphql {
            override suspend fun query(operation: String, variables: JsonObject): JsonObject {
                asked += operation to variables
                return when (operation) {
                    "libraryV3" -> json("""{"me":{"libraryV3":{"totalCount":1,"items":[{"item":{"_uri":"spotify:artist:abc","data":{"profile":{"name":"Burial"}}}}]}}}""")
                    else -> json("{}")
                }
            }
            override suspend fun spclient(path: String, body: JsonObject?): JsonObject = error("unused")
        }) { it }
        assertTrue(backend.followsOnServer("abc"))
        assertEquals(listOf("abc" to "Burial"), backend.followedArtists().map { it.id to it.name })
        assertEquals("[\"Artists\"]", asked.first().second.get("filters").toString())
        assertTrue(backend.setFollowed("def", true))
        val (op, vars) = asked.last()
        assertEquals("addToLibrary", op)
        assertEquals("[\"spotify:artist:def\"]", vars.get("libraryItemUris").toString())
    }

    @Test fun youTubeFollowsAreChannelSubscriptionsAndGuestsHaveNone() = runBlocking {
        val asked = mutableListOf<String>()
        val transport = YouTubeMusicTransport { endpoint, body ->
            asked += if (endpoint == "browse") "browse ${body.string("browseId")}" else "$endpoint ${body.get("channelIds")}"
            if (endpoint == "browse") json("""{"contents":{"items":[{"musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCabc"}},""" +
                """"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Burial"}]}}}]}}]}}""")
            else JsonObject()
        }
        val account = YouTubeMusicBackend(Session("https://music.youtube.com", "Listener", "", "reference", ServerType.YOUTUBE_MUSIC, "@listener"), transport)
        assertTrue(account.followsOnServer("UCabc"))
        assertEquals(listOf("UCabc" to "Burial"), account.followedArtists().map { it.id to it.name })
        assertTrue(account.setFollowed("UCdef", true))
        assertEquals(listOf("browse FEmusic_library_corpus_artists", "subscription/subscribe [\"UCdef\"]"), asked)
        val guest = YouTubeMusicBackend(Session.guest(ServerType.YOUTUBE_MUSIC, "Guest"), transport)
        assertFalse(guest.followsOnServer("UCabc"))
        assertTrue(guest.followedArtists().isEmpty())
    }

    @Test fun mergedFollowsCarryEachSourcesNamespaceAndRouteBackToIt() = runBlocking {
        val navidrome = Source("navidrome", followed = listOf(Artist("n1", "Burial", "", 0)))
        val device = Source("device", ServerType.LOCAL, server = false)
        val merged = MergedBackend(listOf(navidrome, device), navidrome.session)
        val ns = mergeNamespace(navidrome.session)
        assertEquals(listOf("$ns${MERGE_NAMESPACE_SEP}n1"), merged.followedArtists().map { it.id })
        assertTrue(merged.followsOnServer("$ns${MERGE_NAMESPACE_SEP}n1"))
        assertFalse(merged.followsOnServer("${mergeNamespace(device.session)}${MERGE_NAMESPACE_SEP}d1"))
        assertTrue(merged.setFollowed("$ns${MERGE_NAMESPACE_SEP}n2", true))
        assertEquals(listOf("n2=true"), navidrome.calls)
        assertTrue(device.calls.isEmpty())
        assertEquals(navidrome to "n2", merged.sourceOf("$ns${MERGE_NAMESPACE_SEP}n2"))
    }

    @Test fun aSourceWithoutServerFollowsKeepsThemPerAccountInSettingsAndBackups() = runBlocking {
        val navidrome = Source("navidrome", followed = listOf(Artist("n1", "Burial", "", 0)))
        val device = Source("device", ServerType.LOCAL, server = false)
        val merged = MergedBackend(listOf(navidrome, device), navidrome.session)
        val settings = store("a")
        val follows = follows(merged, settings)
        follows.on { refresh() }
        val deviceNs = mergeNamespace(device.session)
        val local = Artist("$deviceNs${MERGE_NAMESPACE_SEP}d1", "Four Tet", "art", 0)
        follows.on { toggle(local) }
        val ids = follows.await(follows.ids) { local.id in it && it.size == 2 }
        assertTrue("$deviceNs${MERGE_NAMESPACE_SEP}d1" in ids)
        assertEquals(setOf(local.id), follows.localIds.value)
        assertTrue(device.calls.isEmpty())
        val kept = settings.followedArtists.first().single()
        assertEquals(deviceNs, kept.account)
        assertEquals("d1", kept.id)
        assertEquals("Four Tet", kept.name)
        assertEquals("LOCAL", kept.type)

        val restored = store("b")
        restored.importPrefs(settings.exportPrefs())
        assertEquals(listOf(kept), restored.followedArtists.first())

        follows.on { toggle(local) }
        follows.await(follows.ids) { local.id !in it }
        assertTrue(settings.followedArtists.first().isEmpty())
    }

    @Test fun storedFollowsMissingFieldsStillRead() = runBlocking {
        val settings = store("c")
        val key = androidx.datastore.preferences.core.stringPreferencesKey("followed_artists")
        settings.importPrefs(PrefsBackup(mapOf(key.name to """[{"account":"s1","id":"a"},null,{"name":"no account"}]"""), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()))
        val read = settings.followedArtists.first().single()
        assertEquals("a", read.id)
        assertEquals("", read.toArtist("a").name)
    }

    @Test fun serverFollowsDedupeByNameAcrossSourcesAndARefusalFlipsBack() = runBlocking {
        val navidrome = Source("navidrome", followed = listOf(Artist("n1", "Burial", "", 0)))
        val jellyfin = Source("jellyfin", ServerType.JELLYFIN, followed = listOf(Artist("j1", "burial", "", 0)), refuse = true)
        val merged = MergedBackend(listOf(navidrome, jellyfin), navidrome.session)
        val follows = follows(merged, store("d"))
        follows.on { refresh() }
        follows.await(follows.ids) { it.size == 2 }
        follows.await(follows.followed) { it.size == 1 }
        val refused = Artist("${mergeNamespace(jellyfin.session)}${MERGE_NAMESPACE_SEP}j2", "Kode9", "", 0)
        val failed = kotlinx.coroutines.CompletableDeferred<Unit>()
        scope.launch { follows.failures.first(); failed.complete(Unit) }
        follows.on { toggle(refused) }
        withTimeout(5_000) { failed.await() }
        assertFalse(follows.isFollowed(refused.id))
        assertEquals(listOf("j2=true"), jellyfin.calls)
    }

    @Test fun followsKeptForAnAccountMoveOnceItsServiceKeepsThem() = runBlocking {
        val apple = Session("https://music.apple.com", "Listener", "", "ref", ServerType.APPLE_MUSIC, "u1")
        val guest = Session.guest(ServerType.SOUNDCLOUD, "Guest")
        val soundcloud = Session("https://soundcloud.com", "Listener", "", "ref", ServerType.SOUNDCLOUD, "42")
        val kept = listOf(
            FollowedArtist(mergeNamespace(apple), "APPLE_MUSIC", false, "100", "TV Girl", "", at = 2),
            FollowedArtist(mergeNamespace(apple), "APPLE_MUSIC", false, "101", "Earlier", "", at = 1),
            FollowedArtist(mergeNamespace(guest), "SOUNDCLOUD", true, "7", "uploader", "", at = 3),
        )
        assertNull(followsToMove(listOf(apple), kept) { false })
        val (who, waiting) = followsToMove(listOf(guest, apple, soundcloud), kept) { it.type == ServerType.APPLE_MUSIC }!!
        assertEquals(apple, who)
        assertEquals(listOf("100", "101"), waiting.map { it.id })
        assertEquals(listOf("7"), followsToMove(listOf(soundcloud), kept) { true }!!.second.map { it.id })

        val target = Source("apple", ServerType.APPLE_MUSIC)
        val forgotten = mutableListOf<String>()
        assertEquals(2, moveFollows(target, waiting) { forgotten += it.id.orEmpty() })
        assertEquals(listOf("101=true", "100=true"), target.calls)
        assertEquals(listOf("101", "100"), forgotten)
        assertEquals(0, moveFollows(Source("refusing", refuse = true), waiting) { error("kept") })
    }
}
