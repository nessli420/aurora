package com.aurora.music.extensions

import android.content.pm.PackageManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.extension.ExtensionContract
import com.aurora.music.AuroraApplication
import com.aurora.music.data.PrefsBackup
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.DownloadState
import com.aurora.music.data.accountKey
import com.aurora.music.playback.PrecisionPlaybackDeviceTest
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.pow

@UnstableApi
class ExtensionContractDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private val manager get() = container.extensions
    private val helper = PrecisionPlaybackDeviceTest()
    private var original: PrefsBackup? = null
    private var component = ""
    private var indexFile: File? = null
    private var indexBytes: ByteArray? = null
    private var foreground = false
    private var packageChanged = false
    private var packageState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    @Before fun prepare() {
        original = runBlocking { container.settingsStore.exportPrefs() }
        packageState = context.packageManager.getApplicationEnabledSetting(EXAMPLE_PACKAGE)
        runBlocking {
            manager.refresh()
            component = requireNotNull(manager.entries.value.firstOrNull { it.descriptor?.packageName == EXAMPLE_PACKAGE }) {
                "Install :extension-example:assembleDebug before running this test."
            }.component
        }
        indexFile = File(context.filesDir, "extension-index/${ExtensionManager.key(component)}.json")
        indexBytes = indexFile?.takeIf(File::exists)?.readBytes()
        runBlocking {
            container.settingsStore.setExtensionGrants(ExtensionCodec.encode(emptyList()))
            withTimeout(3_000) { manager.entries.first { entries -> entries.none { it.enabled } } }
        }
        foreground = true
        helper.keepTargetForegroundForAudioFocus()
    }

    @After fun restore() {
        try {
            if (packageChanged) restorePackage()
            if (foreground) helper.removeFixturesAndFinishActivity()
        } finally {
            original?.let { snapshot -> runBlocking {
                container.settingsStore.restoreBackupPrefs(snapshot).getOrThrow()
                snapshot.strings[ExtensionCodec.PREFERENCE_KEY]?.let { container.settingsStore.setExtensionGrants(it) }
                manager.refresh()
            } }
            indexFile?.let { file ->
                if (indexBytes == null) file.delete() else file.apply { parentFile?.mkdirs(); writeBytes(requireNotNull(indexBytes)) }
                File(file.path + ".bak").delete()
                File(file.path + ".new").delete()
            }
        }
    }

    @Test fun explicitConsentSettingsSnapshotsMetadataAndVersionChecks() = runBlocking {
        manager.refresh()
        val initial = requireNotNull(manager.entries.value.find { it.component == component })
        assertFalse(initial.enabled)
        assertTrue(manager.audio(component).isFailure)
        assertTrue(manager.setEnabled(component, true, initial.descriptor!!.copy(capabilities = setOf("audio"))).isFailure)
        assertFalse(manager.entries.value.first { it.component == component }.enabled)
        manager.setEnabled(component, true).getOrThrow()
        val entry = requireNotNull(manager.entries.value.find { it.component == component })
        assertTrue(entry.enabled)
        assertEquals(setOf("audio", "media", "metadata"), entry.descriptor!!.capabilities)
        val manifest = manager.loadManifest(component).getOrThrow()
        assertEquals("Apache-2.0", manifest.license)
        assertEquals("gainDb", manifest.settings.single().id)
        assertFalse(entry.descriptor.copy(api = 2).compatible)
        val unsupported = JsonObject().apply { addProperty("api", 2) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionCodec.manifest(unsupported) }
        val originalRack = manager.audio(component).getOrThrow()
        assertEquals(RackNodeKind.GAIN, originalRack.nodes.single().kind)
        assertEquals(-6f, originalRack.nodes.single().audio.dspPreampDb, 0f)
        manager.updateSetting(component, "gainDb", -12.0).getOrThrow()
        val changed = manager.audio(component).getOrThrow()
        assertEquals(-12f, changed.nodes.single().audio.dspPreampDb, 0f)
        assertEquals(-6f, originalRack.nodes.single().audio.dspPreampDb, 0f)
        assertTrue(manager.updateSetting(component, "gainDb", 999.0).isFailure)
        val matches = manager.metadata("SDK probe", "Aurora tests", "Original album")
        assertEquals(1, matches.size)
        assertEquals("SDK probe", matches.single().title)
        assertEquals("Aurora tests", matches.single().artist)
        assertEquals("SDK example suggestion", matches.single().album)
        assertEquals("2026", matches.single().year)
        assertEquals(80, matches.single().score)
        val grant = requireNotNull(manager.entries.value.find { it.component == component }?.grant)
        container.settingsStore.setExtensionGrants(ExtensionCodec.encode(listOf(grant.copy(signer = "0".repeat(64)))))
        withTimeout(3_000) { manager.entries.first { entries -> entries.any { it.component == component && !it.enabled } } }
        assertTrue(manager.audio(component).isFailure)
        assertTrue(manager.entries.value.first { it.component == component }.error.orEmpty().contains("changed"))
        manager.setEnabled(component, true).getOrThrow()
        assertTrue(manager.entries.value.first { it.component == component }.enabled)
        manager.setEnabled(component, false).getOrThrow()
        assertTrue(manager.metadata("SDK probe", "Aurora tests", "Original album").isEmpty())
    }

    @Test fun cachedLibrarySurvivesRemovalWhilePlaybackAccessAndIpcAreBounded() = runBlocking {
        manager.setEnabled(component, true).getOrThrow()
        val descriptor = requireNotNull(manager.entries.value.find { it.component == component }?.descriptor)
        val session = manager.useLibrary(component)
        val backend = manager.backend(session)
        val songs = backend.allSongs()
        assertEquals(1, songs.size)
        assertEquals("Extension test tone", songs.single().title)
        val sentinel = Uri.parse(songs.single().streamUrl)
        assertEquals("aurora-extension", sentinel.scheme)
        val content = manager.resolve(sentinel)
        assertEquals("com.aurora.extension.example.audio", content.authority)
        requireNotNull(context.contentResolver.openInputStream(content)).use { input ->
            val header = ByteArray(4)
            assertEquals(4, input.read(header))
            assertEquals("RIFF", header.toString(Charsets.US_ASCII))
        }
        val copy = File(context.cacheDir, "extension-copy-${System.nanoTime()}.wav")
        try {
            val progress = ArrayList<Float>()
            manager.copyAudio(songs.single().streamUrl, copy, progress::add)
            val original = requireNotNull(context.contentResolver.openInputStream(content)).use { it.readBytes() }
            assertArrayEquals(original, copy.readBytes())
            assertEquals(1f, progress.last(), 0f)
            assertTrue(progress.zipWithNext().all { (before, after) -> before <= after })
            manager.setEnabled(component, false).getOrThrow()
            assertThrows(IllegalArgumentException::class.java) { manager.copyAudio(songs.single().streamUrl, copy) {} }
            assertArrayEquals(original, copy.readBytes())
            manager.setEnabled(component, true).getOrThrow()
        } finally { copy.delete() }
        val client = ExtensionClient(context)
        val request = JsonObject().apply { addProperty("method", ExtensionContract.DESCRIBE) }
        val started = SystemClock.elapsedRealtime()
        try {
            client.call(descriptor.copy(uid = -1), request.deepCopy(), timeoutMs = 250)
            fail("Responses from an unexpected UID must be refused.")
        } catch (_: TimeoutCancellationException) { }
        assertTrue(SystemClock.elapsedRealtime() - started < 3_000)
        assertTrue(client.call(descriptor, request.deepCopy()).has("data"))
        try {
            client.call(descriptor, JsonObject().apply { addProperty("method", "unsupported") })
            fail("Unsupported IPC methods must fail.")
        } catch (_: java.io.IOException) { }
        try {
            client.call(descriptor, JsonObject().apply {
                addProperty("method", ExtensionContract.MEDIA); addProperty("offset", 0.5); addProperty("count", 100)
            })
            fail("Fractional protocol offsets must fail.")
        } catch (_: java.io.IOException) { }
        assertEquals(1, client.call(descriptor, JsonObject().apply {
            addProperty("method", ExtensionContract.MEDIA); addProperty("offset", 0); addProperty("count", 100)
        })["data"].asJsonObject["total"].asInt)
        manager.setEnabled(component, false).getOrThrow()
        assertEquals(songs, backend.allSongs())
        assertThrows(IllegalArgumentException::class.java) { manager.resolve(sentinel) }
        manager.setEnabled(component, true).getOrThrow()
        packageChanged = true
        shell("pm disable-user --user current $EXAMPLE_PACKAGE")
        manager.refresh()
        val removed = manager.entries.value.first { it.component == component }
        assertNull(removed.descriptor)
        assertFalse(removed.enabled)
        assertEquals(songs, backend.allSongs())
        assertThrows(IllegalArgumentException::class.java) { manager.resolve(sentinel) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restarted = ExtensionManager(context, container.settingsStore, scope)
            restarted.refresh()
            assertEquals(songs, restarted.backend(session).allSongs())
        } finally { scope.cancel() }
        restorePackage()
        manager.refresh()
        assertTrue(manager.entries.value.first { it.component == component }.enabled)
        assertEquals(content, manager.resolve(sentinel))
    }

    @Test fun cachedProviderSurvivesServiceTimeoutAndExternalCancellationUnbinds() = runBlocking {
        manager.setEnabled(component, true).getOrThrow()
        val session = manager.useLibrary(component)
        val songs = manager.backend(session).allSongs()
        val bindings = AtomicInteger()
        val unbindings = AtomicInteger()
        val unavailable = object : ContextWrapper(context) {
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                bindings.incrementAndGet()
                return true
            }
            override fun unbindService(connection: ServiceConnection) { unbindings.incrementAndGet() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val isolated = ExtensionManager(unavailable, container.settingsStore, scope)
            withTimeout(3_000) { isolated.entries.first { entries -> entries.any { it.component == component && it.enabled } } }
            val started = SystemClock.elapsedRealtime()
            val resolved = isolated.resolve(Uri.parse(songs.single().streamUrl))
            assertTrue(SystemClock.elapsedRealtime() - started in 4_000..8_000)
            requireNotNull(context.contentResolver.openInputStream(resolved)).use { input ->
                assertEquals('R'.code, input.read())
            }
            assertEquals(1, bindings.get())
            assertEquals(bindings.get(), unbindings.get())
            try {
                withContext(Dispatchers.IO) { withTimeout(100) { isolated.backend(session).allSongs() } }
                fail("External cancellation must propagate.")
            } catch (_: TimeoutCancellationException) { }
            assertEquals(2, bindings.get())
            assertEquals(bindings.get(), unbindings.get())
        } finally { scope.cancel() }
    }

    @Test fun providerAudioPlaysThroughCompiledRackPauseSeekAndEos() {
        helper.withProcessingFixture(0) { controller, _ ->
            val song = runBlocking {
                manager.setEnabled(component, true).getOrThrow()
                manager.updateSetting(component, "gainDb", -6.0).getOrThrow()
                val rack = manager.audio(component).getOrThrow()
                container.settingsStore.setProcessingRack(rack).getOrThrow()
                manager.backend(manager.useLibrary(component)).allSongs().single()
            }
            val since = System.nanoTime()
            helper.main {
                controller.setMediaItem(MediaItem.Builder().setMediaId(song.id).setUri(song.streamUrl).build())
                controller.prepare()
                controller.play()
            }
            helper.await("extension content plays through its compiled gain", controller) {
                val measurements = container.signalPath.value.measurements
                val before = measurements?.before
                val after = measurements?.after
                helper.main { controller.isPlaying } && before != null && after != null && measurements.afterAvailable &&
                    before.measuredAtNanos > since && after.measuredAtNanos > since && before.sampleRate == 48_000 &&
                    before.leftRms > .02 && after.invalidSamples == 0L && after.fullScaleSamples == 0L &&
                    abs(after.leftRms / before.leftRms - 10.0.pow(-6.0 / 20.0)) < .008
            }
            helper.main {
                assertEquals(12_000L, controller.duration)
                assertEquals(song.id, controller.currentMediaItem?.mediaId)
                controller.pause()
            }
            helper.await("extension playback pauses", controller) { helper.main { !controller.isPlaying } }
            val paused = helper.main { controller.currentPosition }
            SystemClock.sleep(160)
            helper.main {
                assertTrue(abs(controller.currentPosition - paused) < 80)
                controller.seekTo(3_000)
                controller.play()
            }
            helper.await("extension playback seeks", controller) {
                helper.main { controller.isPlaying && controller.currentPosition in 3150..6000 }
            }
            helper.main { controller.seekTo(11_300) }
            helper.await("extension playback reaches EOS", controller) { helper.main { controller.playbackState == Player.STATE_ENDED } }
        }
    }

    @Test fun downloadedOriginalPlaysWithoutExtensionDespiteStreamPriority() {
        helper.withProcessingFixture(0) { controller, _ ->
            val previousSession = runBlocking { container.settingsStore.session.first() }
            val session = runBlocking {
                manager.setEnabled(component, true).getOrThrow()
                manager.useLibrary(component)
            }
            val account = session.accountKey()
            val previousQueue = container.queueStore.get(account)
            val source = runBlocking { manager.backend(session).allSongs().single() }
            val alreadyDownloaded = container.downloadManager.isDownloaded(source.id)
            try {
                val original = requireNotNull(context.contentResolver.openInputStream(manager.resolve(Uri.parse(source.streamUrl))))
                    .use { it.readBytes() }
                val epoch = container.accountEpoch.value
                runBlocking { container.switchSession(session) }
                helper.await("extension account becomes active", controller) {
                    container.backend?.session == session && (previousSession == session || container.accountEpoch.value > epoch)
                }
                helper.main { controller.stop(); controller.clearMediaItems() }
                runBlocking {
                    container.settingsStore.setDownloadBitrate(128)
                    container.settingsStore.setPreferLocalSources(true)
                    container.settingsStore.setSourcePriority(listOf("downloaded", "local", "stream"))
                }
                container.downloadManager.downloadSong(source)
                helper.await("extension original download finishes", controller) {
                    val state = container.downloadManager.states.value[source.id]
                    assertFalse("Extension download failed", state == DownloadState.Failed)
                    container.downloadManager.get(source.id) != null
                }
                val downloaded = requireNotNull(container.downloadManager.get(source.id))
                assertEquals(session.server, downloaded.serverId)
                assertEquals("wav", downloaded.suffix)
                assertEquals(48_000, downloaded.sampleRateHz)
                assertEquals(16, downloaded.bitDepth)
                assertArrayEquals(original, File(downloaded.audioPath).readBytes())
                helper.await("enabled extension honors download priority", controller) {
                    runBlocking { requireNotNull(container.backend).songFor(source.id)?.streamUrl?.startsWith("file:") == true }
                }
                runBlocking { container.settingsStore.setSourcePriority(listOf("stream", "downloaded", "local")) }
                helper.await("enabled extension honors stream priority", controller) {
                    runBlocking { requireNotNull(container.backend).songFor(source.id)?.streamUrl == source.streamUrl }
                }
                runBlocking { manager.setEnabled(component, false).getOrThrow() }
                val disabledSong = runBlocking { requireNotNull(requireNotNull(container.backend).songFor(source.id)) }
                assertEquals(Uri.fromFile(File(downloaded.audioPath)).toString(), disabledSong.streamUrl)
                assertEquals(source.id, disabledSong.id)
                packageChanged = true
                shell("pm disable-user --user current $EXAMPLE_PACKAGE")
                runBlocking { manager.refresh() }
                val local = runBlocking { requireNotNull(requireNotNull(container.backend).songFor(source.id)) }
                assertEquals(disabledSong.streamUrl, local.streamUrl)
                assertNull(manager.entries.value.first { it.component == component }.descriptor)
                assertThrows(IllegalArgumentException::class.java) { manager.resolve(Uri.parse(source.streamUrl)) }
                val since = System.nanoTime()
                helper.main {
                    controller.setMediaItem(MediaItem.Builder().setMediaId(local.id).setUri(local.streamUrl).build())
                    controller.prepare()
                    controller.play()
                }
                helper.await("downloaded extension audio plays without its provider", controller) {
                    val before = container.signalPath.value.measurements?.before
                    helper.main { controller.isPlaying && controller.currentPosition > 150 } &&
                        before != null && before.measuredAtNanos > since && before.sampleRate == 48_000 && before.leftRms > .02
                }
                helper.main {
                    assertEquals(12_000L, controller.duration)
                    assertEquals(local.id, controller.currentMediaItem?.mediaId)
                    controller.seekTo(11_300)
                }
                helper.await("downloaded extension audio reaches EOS", controller) {
                    helper.main { controller.playbackState == Player.STATE_ENDED }
                }
            } finally {
                helper.main { controller.stop(); controller.clearMediaItems() }
                if (packageChanged) restorePackage()
                if (!alreadyDownloaded) container.downloadManager.removeDownload(source.id)
                val epoch = container.accountEpoch.value
                runBlocking {
                    if (previousSession == null) container.signOut() else container.switchSession(previousSession)
                }
                helper.await("original account restored", controller) {
                    container.backend?.session == previousSession && (previousSession == session || container.accountEpoch.value > epoch)
                }
                runBlocking { container.queueStore.restoreAccount(account, previousQueue) }
            }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun restorePackage() {
        val action = when (packageState) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default-state"
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disable"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disable-user"
            else -> "disable-until-used"
        }
        shell("pm $action --user current $EXAMPLE_PACKAGE")
        packageChanged = false
    }

    private companion object {
        const val EXAMPLE_PACKAGE = "com.aurora.extension.example"
    }
}
