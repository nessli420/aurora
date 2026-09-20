package com.aurora.music.extensions

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.util.UnstableApi
import com.aurora.music.AuroraApplication
import com.aurora.music.data.PrefsBackup
import com.aurora.music.playback.PrecisionPlaybackDeviceTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class ExtensionRaceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private val helper = PrecisionPlaybackDeviceTest()
    private val scopes = mutableListOf<CoroutineScope>()
    private var original: PrefsBackup? = null
    private var originalCache = emptyMap<File, ByteArray?>()
    private var component = ""
    private lateinit var seeded: ExtensionManager
    private lateinit var sentinel: Uri
    private var foreground = false

    @Before fun prepare() = runBlocking {
        original = container.settingsStore.exportPrefs()
        foreground = true
        helper.keepTargetForegroundForAudioFocus()
        seeded = newManager(context)
        withTimeout(3_000) { seeded.entries.first { entries -> entries.any { it.descriptor?.packageName == "com.aurora.extension.example" } } }
        component = requireNotNull(seeded.entries.value.firstOrNull { it.descriptor?.packageName == "com.aurora.extension.example" }) {
            "Install the extension example before running this test."
        }.component
        val index = File(context.filesDir, "extension-index/${ExtensionManager.key(component)}.json")
        originalCache = listOf(index, File(index.path + ".bak"), File(index.path + ".new")).associateWith { it.takeIf(File::exists)?.readBytes() }
        seeded.setEnabled(component, true).getOrThrow()
        val track = seeded.loadTracks(component, force = true).single()
        sentinel = Uri.parse(seeded.song(component, track).streamUrl)
    }

    @After fun restore() {
        scopes.forEach { it.cancel() }
        try { if (foreground) helper.removeFixturesAndFinishActivity() }
        finally {
            original?.let { saved -> runBlocking {
                container.settingsStore.restoreBackupPrefs(saved).getOrThrow()
                container.settingsStore.setExtensionGrants(saved.strings[ExtensionCodec.PREFERENCE_KEY] ?: ExtensionCodec.encode(emptyList()))
            } }
            originalCache.forEach { (file, bytes) ->
                if (bytes == null) file.delete() else { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
            }
        }
    }

    @Test fun disablingDuringRefreshCannotResolveTheCachedFile() = runBlocking {
        val gate = DelayedConnectionContext(context)
        val isolated = newManager(gate)
        awaitEnabled(isolated)
        val result = async(Dispatchers.IO) { runCatching { isolated.resolve(sentinel) } }
        try {
            withTimeout(3_000) { gate.connected.await() }
            seeded.setEnabled(component, false).getOrThrow()
            withTimeout(3_000) { isolated.entries.first { entries -> entries.any { it.component == component && !it.enabled } } }
            gate.release()
            val resolved = withTimeout(3_000) { result.await() }
            assertTrue("Revocation must win over the cached fallback", resolved.isFailure)
            assertTrue(resolved.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, gate.bindings.get())
            assertEquals(1, gate.unbindings.get())
            assertEquals(1, isolated.loadTracks(component).size)
        } finally { gate.release(); result.cancelAndJoin() }
    }

    @Test fun aSettingsEditRejectsTheInflightCatalogBeforeCommit() = runBlocking {
        val gate = DelayedConnectionContext(context)
        val isolated = newManager(gate)
        awaitEnabled(isolated)
        val file = File(context.filesDir, "extension-index/${ExtensionManager.key(component)}.json")
        val cachedBefore = file.readBytes()
        val previous = requireNotNull(seeded.entries.value.first { it.component == component }.grant).settings.getValue("gainDb")
        val next = if (previous == -12.0) -6.0 else -12.0
        val result = async(Dispatchers.IO) { runCatching { isolated.loadTracks(component, force = true) } }
        try {
            withTimeout(3_000) { gate.connected.await() }
            seeded.updateSetting(component, "gainDb", next).getOrThrow()
            withTimeout(3_000) { isolated.entries.first { entries -> entries.any {
                it.component == component && it.grant?.settings?.get("gainDb") == next
            } } }
            gate.release()
            val loaded = withTimeout(3_000) { result.await() }
            assertTrue("Catalog requests cannot cross settings revisions", loaded.isFailure)
            assertTrue(loaded.exceptionOrNull()?.message.orEmpty().contains("changed"))
            assertArrayEquals(cachedBefore, file.readBytes())
            assertEquals(1, gate.bindings.get())
            assertEquals(1, gate.unbindings.get())
            assertEquals(1, isolated.loadTracks(component, force = true).size)
            assertEquals(2, gate.bindings.get())
            assertEquals(2, gate.unbindings.get())
        } finally { gate.release(); result.cancelAndJoin() }
    }

    private fun newManager(managerContext: Context): ExtensionManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return ExtensionManager(managerContext, container.settingsStore, scope)
    }
    private suspend fun awaitEnabled(manager: ExtensionManager) {
        withTimeout(3_000) { manager.entries.first { entries -> entries.any { it.component == component && it.enabled } } }
    }

    private class DelayedConnectionContext(base: Context) : ContextWrapper(base) {
        val connected = CompletableDeferred<Unit>()
        val bindings = AtomicInteger()
        val unbindings = AtomicInteger()
        private val connections = IdentityHashMap<ServiceConnection, ServiceConnection>()
        private var callback: (() -> Unit)? = null
        private var released = false
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bindings.incrementAndGet()
            val proxy = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    synchronized(connections) {
                        if (released) connection.onServiceConnected(name, binder)
                        else {
                            callback = { connection.onServiceConnected(name, binder) }
                            connected.complete(Unit)
                        }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) = connection.onServiceDisconnected(name)
                override fun onNullBinding(name: ComponentName) = connection.onNullBinding(name)
                override fun onBindingDied(name: ComponentName) = connection.onBindingDied(name)
            }
            synchronized(connections) { connections[connection] = proxy }
            return super.bindService(service, proxy, flags)
        }
        override fun unbindService(connection: ServiceConnection) {
            val proxy = synchronized(connections) { connections.remove(connection) }
            if (proxy != null) { unbindings.incrementAndGet(); super.unbindService(proxy) }
        }
        fun release() {
            val pending = synchronized(connections) { released = true; callback.also { callback = null } }
            if (pending != null) InstrumentationRegistry.getInstrumentation().runOnMainSync { pending() }
        }
    }
}
