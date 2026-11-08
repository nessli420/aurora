package com.aurora.music.data.container

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.JellyfinBackend
import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalCatalog
import com.aurora.music.data.LocalStore
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MergedBackend
import com.aurora.music.data.ReportingMediaBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.SoundCloudBackend
import com.aurora.music.data.SoundCloudPendingLikes
import com.aurora.music.data.SourceLocalizer
import com.aurora.music.data.SpotifyWebBackend
import com.aurora.music.data.SubsonicBackend
import com.aurora.music.data.remote.SoundCloudBrowser
import com.aurora.music.data.remote.SpotifyGraphql
import com.aurora.music.data.remote.testClientInfo
import com.aurora.music.model.Song
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BackendFactoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(temp.root, "aurora_settings.preferences_pb") }, temp.root, temp.root)
    }
    private val catalog = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, _ ->
        error("Unexpected ${method.name}")
    } as LocalCatalog
    private val localSession = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)

    private val http = AtomicInteger()
    private val signature = AtomicInteger()
    private val spotifyWeb = AtomicInteger()
    private val browser = AtomicInteger()
    private val platformCalls = CopyOnWriteArrayList<Session>()

    private val graphql = object : SpotifyGraphql {
        override suspend fun query(operation: String, variables: JsonObject): JsonObject = error("offline")
        override suspend fun spclient(path: String, body: JsonObject?): JsonObject = error("offline")
    }

    private val subsonic = Session("http://127.0.0.1:9", "alice", "salt", "token")
    private val jellyfin = Session("http://127.0.0.1:9", "bob", "", "token", ServerType.JELLYFIN, userId = "u1")
    private val soundcloud = Session("https://soundcloud.com", "listener", "", "login-1", ServerType.SOUNDCLOUD, "42")

    private fun factory(
        eligible: (Session) -> Boolean = { true },
        platform: (Session, (Song) -> Song) -> MediaBackend? = { _, _ -> null },
    ): BackendFactory {
        val dir = temp.newFolder()
        val localizer = SourceLocalizer(localSession, DownloadManager(dir, { "file://$it" }, { null }), { false }, { emptyList() }, { false }, { _, _, _ -> null })
        return BackendFactory(testClientInfo, store, localizer, catalog, LocalStore(dir), localSession, SoundCloudPendingLikes(),
            http = { http.incrementAndGet(); OkHttpClient() }, maxBitrate = { 0 }, youtubeMusicLogin = { "" },
            signatureTimestamp = { signature.incrementAndGet(); { null } },
            spotifyWeb = { spotifyWeb.incrementAndGet(); graphql }, spotifyLogin = { "" },
            soundcloudLogin = { "" }, saveSoundcloudLogin = { _, _ -> },
            soundcloudBrowser = { browser.incrementAndGet(); SoundCloudBrowser { _, _, _, _, _, _, _ -> error("offline") } },
            reportError = { _, _ -> }, eligible = eligible, platformBackend = platform)
    }

    @After fun tearDown() { storeScope.cancel() }

    @Test fun constructionTouchesNoProvider() {
        factory()
        assertEquals(listOf(0, 0, 0, 0), listOf(http.get(), signature.get(), spotifyWeb.get(), browser.get()))
    }

    @Test fun eachServerTypeBuildsItsBackend() {
        val backends = factory()
        assertTrue(backends.build(subsonic) is SubsonicBackend)
        assertTrue(backends.build(jellyfin) is JellyfinBackend)
        val plex = Session("http://127.0.0.1:9", "carol", "", "plex-token", ServerType.PLEX, userId = "1")
        val plexBackend = backends.build(plex)
        assertTrue(plexBackend is ReportingMediaBackend)
        assertEquals(plex, plexBackend!!.session)
        assertTrue(backends.build(localSession) is LocalBackend)
        val youtube = backends.build(Session.guest(ServerType.YOUTUBE_MUSIC, "Guest"))
        assertTrue(youtube is ReportingMediaBackend)
        assertEquals(1, signature.get())
        assertTrue(backends.build(Session.guest(ServerType.SOUNDCLOUD, "Guest")) is SoundCloudBackend)
        val web = Session("https://open.spotify.com", "me", SpotifyWebBackend.WEB_PLAYER, "web-1", ServerType.SPOTIFY, "me")
        assertTrue(backends.build(web) is SpotifyWebBackend)
        assertEquals(1, spotifyWeb.get())

        val oauth = Session("https://api.spotify.com", "me", "refresh", "access", ServerType.SPOTIFY)
        val extension = Session("extension", "Extension", "", "ext", ServerType.EXTENSION, "com.example")
        assertNull(backends.build(oauth))
        assertNull(backends.build(extension))
        val platform = LocalBackend(catalog, LocalStore(temp.newFolder()), localSession)
        val withPlatform = factory(platform = { session, _ -> platformCalls += session; platform })
        assertSame(platform, withPlatform.build(oauth))
        assertSame(platform, withPlatform.build(extension))
        assertEquals(listOf(oauth, extension), platformCalls)
    }

    @Test fun onlyAUnifiedMergeableSessionBuildsAMergedLibrary() {
        val backends = factory()
        assertTrue(backends.buildActive(subsonic, false, emptySet(), listOf(jellyfin)) is SubsonicBackend)
        val merged = backends.buildActive(subsonic, true, emptySet(), listOf(jellyfin))
        assertTrue(merged is MergedBackend)
        assertEquals(listOf(localSession, jellyfin, subsonic), (merged as MergedBackend).sources.map { it.session })
        val extension = Session("extension", "Extension", "", "ext", ServerType.EXTENSION, "com.example")
        assertFalse(backends.buildActive(extension, true, emptySet(), emptyList()) is MergedBackend)

        val noJellyfin = factory(eligible = { it.type != ServerType.JELLYFIN })
        assertNull(noJellyfin.buildActive(jellyfin, true, emptySet(), emptyList()))
        val withoutJellyfin = noJellyfin.buildActive(subsonic, true, emptySet(), listOf(jellyfin)) as MergedBackend
        assertEquals(listOf(localSession, subsonic), withoutJellyfin.sources.map { it.session })
    }

    @Test fun soundCloudKeepsOneClientPerLogin() {
        val backends = factory()
        backends.build(soundcloud)
        backends.build(soundcloud)
        assertEquals(1, browser.get())
        backends.forgetSoundCloud(soundcloud.token)
        backends.build(soundcloud)
        assertEquals(2, browser.get())
        backends.build(Session.guest(ServerType.SOUNDCLOUD, "Guest"))
        assertEquals(2, browser.get())
    }

    @Test fun catalogueUsesTheSavedAccountOrAGuest() = runBlocking {
        val backends = factory()
        assertTrue(backends.catalogue(ServerType.SOUNDCLOUD)!!.session.isGuest)
        store.addSavedSession(soundcloud)
        assertEquals(soundcloud, backends.catalogue(ServerType.SOUNDCLOUD)!!.session)
    }
}
