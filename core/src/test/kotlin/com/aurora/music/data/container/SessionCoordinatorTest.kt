package com.aurora.music.data.container

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalCatalog
import com.aurora.music.data.LocalStore
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.accountKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SessionCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(temp.root, "aurora_settings.preferences_pb") }, temp.root, temp.root)
    }
    private val localStore by lazy { LocalStore(temp.root) }
    private val catalog = Proxy.newProxyInstance(LocalCatalog::class.java.classLoader, arrayOf(LocalCatalog::class.java)) { _, method, _ ->
        error("Unexpected ${method.name}")
    } as LocalCatalog

    private val a = Session("http://127.0.0.1:9", "alice", "salt", "token")
    private val b = Session("http://127.0.0.1:10", "bob", "salt", "token")
    private val c = Session("http://127.0.0.1:11", "carol", "salt", "token")

    private class Build(val session: Session, val unified: Boolean, val mergeKeys: Set<String>)
    private val builds = CopyOnWriteArrayList<Build>()
    private val restored = CopyOnWriteArrayList<Session>()
    private val accountChanges = CopyOnWriteArrayList<Session?>()
    private val forgotten = AtomicInteger()
    @Volatile private var backend: MediaBackend? = null

    private fun coordinator(
        supports: ((Session) -> Boolean)? = null,
        build: (Session) -> MediaBackend? = { LocalBackend(catalog, localStore, it) },
    ) = SessionCoordinator(store, store, scope, { session, unified, mergeKeys, _ -> builds += Build(session, unified, mergeKeys); build(session) },
        backend = { backend }, setBackend = { backend = it }, rememberRestored = { restored += it }, rememberApplied = {},
        onAccountChanged = { accountChanges += it }, forgetLibrary = { forgotten.incrementAndGet() }, supports = supports)

    @After fun tearDown() {
        scope.cancel()
        storeScope.cancel()
    }

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T = withTimeout(10_000) { first(predicate) }
    private suspend fun until(check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(10) }

    @Test fun restoredSessionBuildsOnceAndDoesNotBumpTheEpoch() = runBlocking {
        store.saveSession(a)
        val sessions = coordinator().also { it.start() }
        assertEquals(true, sessions.sessionReady.await { it != null })
        until { accountChanges.isNotEmpty() }
        assertEquals(listOf(a), builds.map { it.session })
        assertEquals(0, sessions.accountEpoch.value)
        assertEquals(listOf(a), restored)
        assertEquals(listOf<Session?>(a), accountChanges)
        assertEquals(a, sessions.lastSession)
        assertTrue(backend is LocalBackend)
    }

    @Test fun realAccountChangeBumpsTheEpochButTokenRefreshDoesNot() = runBlocking {
        store.saveSession(a)
        val sessions = coordinator().also { it.start() }
        until { accountChanges.size == 1 }
        val refreshed = a.copy(token = "t2")
        store.saveSession(refreshed)
        until { accountChanges.size == 2 }
        assertEquals(listOf(a, refreshed), builds.map { it.session })
        assertEquals(0, sessions.accountEpoch.value)
        store.saveSession(b)
        until { accountChanges.size == 3 }
        assertEquals(1, sessions.accountEpoch.value)
        assertEquals(b, accountChanges.last())
        assertEquals(listOf(a, refreshed, b), builds.map { it.session })
    }

    @Test fun mergeSettingChangesRebuildAndReloadWithoutRepublishing() = runBlocking {
        store.saveSession(a)
        val sessions = coordinator().also { it.start() }
        until { accountChanges.size == 1 }
        store.setUnifiedLibrary(true)
        until { forgotten.get() == 1 }
        assertEquals(2, builds.size)
        assertTrue(builds.last().unified)
        assertEquals(1, sessions.libraryReload.value)
        store.setMergeSources(setOf("k"))
        until { forgotten.get() == 2 }
        assertEquals(3, builds.size)
        assertEquals(setOf("k"), builds.last().mergeKeys)
        assertEquals(2, sessions.libraryReload.value)
        assertEquals(1, accountChanges.size)
        assertEquals(0, sessions.accountEpoch.value)
    }

    @Test fun savedSessionsRebuildOnlyAMergedLibrary() = runBlocking {
        store.saveSession(a)
        store.setUnifiedLibrary(true)
        val sessions = coordinator().also { it.start() }
        until { accountChanges.size == 1 }
        assertEquals(1, builds.size)
        assertTrue(builds.single().unified)
        store.addSavedSession(b)
        until { forgotten.get() == 1 }
        assertEquals(2, builds.size)
        assertEquals(1, sessions.libraryReload.value)
    }

    @Test fun savedSessionsDoNotRebuildAnUnmergedLibrary() = runBlocking {
        store.saveSession(a)
        val sessions = coordinator().also { it.start() }
        until { accountChanges.size == 1 }
        store.addSavedSession(b)
        // unified stays off, so only the merge-keys change may rebuild
        store.setMergeSources(setOf("k"))
        until { forgotten.get() == 1 }
        assertEquals(2, builds.size)
        assertEquals(1, sessions.libraryReload.value)
    }

    @Test fun unsupportedAccountsPublishWithANullBackend() = runBlocking {
        val spotify = Session("https://api.spotify.com", "me", "refresh", "access", ServerType.SPOTIFY)
        val sessions = coordinator(supports = { it.type != ServerType.SPOTIFY },
            build = { if (it.type == ServerType.SPOTIFY) null else LocalBackend(catalog, localStore, it) }).also { it.start() }
        assertEquals(false, sessions.sessionReady.await { it != null })
        sessions.applySession(spotify)
        assertNull(backend)
        assertEquals(false, sessions.sessionReady.value)
        assertEquals(spotify, sessions.unsupportedAccount.value)
        until { accountChanges.lastOrNull() == spotify }
        sessions.signOut()
        until { accountChanges.size == 3 }
        assertNull(sessions.unsupportedAccount.value)
        assertEquals(false, sessions.sessionReady.value)
        assertNull(store.session.first())
        assertNull(sessions.lastSession)
    }

    @Test fun withoutASupportCheckReadyFollowsTheSession() = runBlocking {
        val sessions = coordinator(build = { null }).also { it.start() }
        assertEquals(false, sessions.sessionReady.await { it != null })
        sessions.applySession(a)
        assertNull(backend)
        assertEquals(true, sessions.sessionReady.value)
        assertNull(sessions.unsupportedAccount.value)
    }

    @Test fun errorsSurfaceOnlyForTheActiveOrMergedAccount() = runBlocking {
        store.saveSession(a)
        val sessions = coordinator().also { it.start() }
        until { accountChanges.size == 1 }
        assertTrue(sessions.isActive(a))
        assertFalse(sessions.isActive(b))
        store.setUnifiedLibrary(true)
        until { builds.size == 2 }
        assertTrue(sessions.isActive(b))
        store.setMergeSources(setOf(b.accountKey()))
        until { builds.size == 3 }
        assertTrue(sessions.isActive(b))
        assertFalse(sessions.isActive(c))
    }
}
