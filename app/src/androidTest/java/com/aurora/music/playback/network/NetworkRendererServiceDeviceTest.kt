package com.aurora.music.playback.network

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.playback.PlaybackService
import com.aurora.music.playback.PrecisionPlaybackDeviceTest
import com.aurora.music.playback.network.endpoint.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.net.URI
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt

@UnstableApi
class NetworkRendererServiceDeviceTest {
    @Test fun pairedEndpointFeedsExistingServiceAndKeepsCachedAudioAfterControllerLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as AuroraApplication).container
        val manager = container.networkOutput
        assumeFalse("Keep active receiver sessions untouched", manager.state.value.receiverEnabled)
        assumeFalse("Keep active network output untouched", manager.state.value.receiverName != null)
        val fixture = PrecisionPlaybackDeviceTest()
        val store = container.settingsStore
        val preferences = runBlocking { store.exportPrefs() }
        val media = ScopedMediaServer()
        var controller: MediaController? = null
        var paired: PairedRenderer? = null
        var client: RendererClient? = null
        var foreground = false
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val cacheDirectory = File(context.cacheDir, "renderer-media")
        val preservedCache = File(context.cacheDir, "renderer-preserved-${UUID.randomUUID()}")
        var cachePreserved = false
        var cacheRestoreComplete = false
        var originalQueue: List<MediaItem>? = null
        var originalIndex = 0
        var originalPosition = 0L
        var originalRepeat = Player.REPEAT_MODE_OFF
        var originalShuffle = false
        var originalPlaying = false
        try {
            runBlocking { store.setBitPerfectUsb(false); store.setSkipSilence(false); store.setCrossfade(0) }
            foreground = true
            fixture.keepTargetForegroundForAudioFocus()
            val session = fixture.main {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }.get(12, TimeUnit.SECONDS)
            controller = session
            fixture.main {
                originalQueue = (0 until session.mediaItemCount).map(session::getMediaItemAt)
                originalIndex = session.currentMediaItemIndex
                originalPosition = session.currentPosition
                originalRepeat = session.repeatMode
                originalShuffle = session.shuffleModeEnabled
                originalPlaying = session.playWhenReady
                session.pause(); session.stop()
            }
            preservedCache.mkdirs()
            cacheDirectory.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(preservedCache, it.name)) }
            cachePreserved = true
            cacheDirectory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            setShuffle(fixture, session, false)
            fixture.main { session.pause(); session.stop(); session.clearMediaItems(); session.repeatMode = Player.REPEAT_MODE_OFF }
            fixture.main { manager.enableReceiver(true) }
            fixture.await("renderer enabled") { manager.state.value.receiverEnabled && manager.state.value.receiverAddress != null }
            val advertised = requireNotNull(manager.state.value.receiverAddress)
            val address = advertised.copy(address = "https://127.0.0.1:${URI(advertised.address).port}")
            fixture.main { manager.beginPairing() }
            fixture.await("pairing code displayed") { manager.state.value.pairing != null }
            val trust = RendererClient(address).use { it.pair("Service test controller", manager.state.value.pairing!!.code) }
            paired = trust
            val initial = RendererClient(trust).also { client = it }
            val track = fixture.wav("network-renderer", 48_000, 48_000 * 20) { frame, channel ->
                (sin(2 * PI * 440 * frame / 48_000) * if (channel == 0) 4096 else 2048).toInt()
            }
            media.start()
            exerciseSenderAdapter(fixture, session, trust, media)
            val grant = media.grant(track, "audio/wav", 60_000)
            initial.command(RendererCommand.Queue(listOf(
                EndpointTrack("network-one", "Network one", "Fixture", durationMs = 20_000, sourceUrl = grant.url("127.0.0.1", media.port)),
                EndpointTrack("network-two", "Network two", "Fixture", durationMs = 20_000, sourceUrl = grant.url("127.0.0.1", media.port), rgTrack = -6f, rgAlbum = -3f),
            ), positionMs = 1_000))
            fixture.await("cached renderer playback", session) { fixture.main { session.isPlaying && session.currentPosition > 1_200 } }
            fixture.main {
                assertEquals(2, session.mediaItemCount)
                assertEquals("Network one", session.currentMediaItem?.mediaMetadata?.title?.toString())
                assertEquals(-6f, session.getMediaItemAt(1).mediaMetadata.extras!!.getFloat("rgTrack"), 0f)
                assertEquals(-3f, session.getMediaItemAt(1).mediaMetadata.extras!!.getFloat("rgAlbum"), 0f)
            }
            fixture.main {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val backgroundPosition = fixture.main { session.currentPosition }
            fixture.await("receiver plays in background", session) {
                val notification = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
                    .any { it.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0 }
                notification && fixture.main { session.isPlaying && session.currentPosition > backgroundPosition + 500 }
            }
            assertTrue(media.stats.bytesServed >= track.length() * 2)
            initial.command(RendererCommand.Seek(19_000, 0))
            media.close()
            track.delete()
            initial.close()
            client = null
            fixture.await("cached queue advances after controller and source loss", session) {
                fixture.main { session.isPlaying && session.currentMediaItemIndex == 1 && session.currentPosition > 200 }
            }
            val connected = RendererClient(trust).also { client = it }
            assertTrue(connected.status().playing)
            connected.command(RendererCommand.Playing(false))
            fixture.await("remote pause", session) { fixture.main { !session.playWhenReady && !session.isPlaying } }
            connected.command(RendererCommand.Seek(4_000, 1))
            fixture.await("cached seek after source server loss", session) {
                fixture.main { session.currentMediaItemIndex == 1 && session.currentPosition in 3_950..4_050 }
            }
            connected.command(RendererCommand.Volume(0.35f))
            assertTrue(connected.status().volume in 0.2f..0.4f)
            connected.command(RendererCommand.Playing(true))
            fixture.await("cached resume", session) { fixture.main { session.isPlaying && session.currentPosition > 4_200 } }
            val route = connected.status().route
            assertEquals(route.id, connected.command(RendererCommand.Route(route.id)).route.id)
            assertThrows(RendererException::class.java) { connected.command(RendererCommand.Route("missing-device")) }
            assertEquals(Player.REPEAT_MODE_ALL, connected.command(RendererCommand.Repeat(Player.REPEAT_MODE_ALL)).repeatMode)
            connected.command(RendererCommand.Seek(19_200, 1))
            fixture.await("cached queue repeats without source", session) {
                fixture.main { session.isPlaying && session.currentMediaItemIndex == 0 && session.currentPosition in 100..2_000 }
            }
            connected.command(RendererCommand.Repeat(Player.REPEAT_MODE_ONE))
            connected.command(RendererCommand.Seek(19_200, 0))
            fixture.await("cached track repeats without source", session) {
                fixture.main { session.isPlaying && session.currentMediaItemIndex == 0 && session.currentPosition in 100..2_000 }
            }
            connected.command(RendererCommand.Playing(false))
            connected.command(RendererCommand.Repeat(Player.REPEAT_MODE_OFF))
            val beforeReorder = connected.status().positionMs
            val reordered = connected.command(RendererCommand.Reorder(listOf("network-two", "network-one")))
            assertEquals(listOf("network-two", "network-one"), reordered.queue.map { it.id })
            assertEquals(1, reordered.queueIndex)
            assertTrue(kotlin.math.abs(beforeReorder - reordered.positionMs) <= 100)
            assertEquals("queue_changed", assertThrows(RendererException::class.java) {
                connected.command(RendererCommand.Reorder(listOf("unknown")))
            }.code)
            val removed = connected.command(RendererCommand.Reorder(listOf("network-two")))
            assertEquals(listOf("network-two"), removed.queue.map { it.id })
            assertEquals(0, removed.queueIndex)
            assertEquals(1, cacheDirectory.listFiles().orEmpty().size)
            val cached = cacheDirectory.listFiles().orEmpty().single()
            ScopedMediaServer().use { failingReplacement ->
                failingReplacement.start()
                val cachedGrant = failingReplacement.grant(cached, "audio/wav", 60_000)
                val request = RendererCommand.Queue(listOf(
                    EndpointTrack("replacement-one", "Replacement one", sourceUrl = cachedGrant.url("127.0.0.1", failingReplacement.port)),
                    EndpointTrack("replacement-two", "Replacement two", sourceUrl = "http://127.0.0.1:${failingReplacement.port}/media/${"z".repeat(43)}"),
                ))
                assertEquals("source_unavailable", assertThrows(RendererException::class.java) { connected.command(request) }.code)
                assertEquals(listOf("network-two"), connected.status().queue.map { it.id })
                assertTrue(cached.isFile)
                assertEquals(1, cacheDirectory.listFiles().orEmpty().size)
            }
            connected.command(RendererCommand.Seek(19_200, 0))
            connected.command(RendererCommand.Playing(true))
            fixture.await("remote queue reaches end", session) { fixture.main { session.playbackState == Player.STATE_ENDED } }
            val transferStarted = CountDownLatch(1)
            val releaseTransfer = CountDownLatch(1)
            val slowSource = BoundedHttpServer(handler = {
                HttpResponse(200, mapOf("Content-Type" to "audio/wav"), bodyLength = 65_536, writeBody = { output ->
                    output.write(ByteArray(64)); output.flush()
                    transferStarted.countDown()
                    releaseTransfer.await(10, TimeUnit.SECONDS)
                    output.write(ByteArray(65_536 - 64))
                })
            })
            val worker = Executors.newSingleThreadExecutor()
            try {
                slowSource.start()
                val pending = worker.submit<String> {
                    try {
                        connected.command(RendererCommand.Queue(listOf(EndpointTrack("revoked", "Must not play",
                            sourceUrl = "http://127.0.0.1:${slowSource.port}/media/${"b".repeat(43)}"))))
                        "accepted"
                    } catch (error: RendererException) { error.code }
                }
                assertTrue("renderer began caching audio", transferStarted.await(10, TimeUnit.SECONDS))
                fixture.main { manager.revokeController(trust.clientId) }
                releaseTransfer.countDown()
                assertEquals("unauthorized", pending.get(10, TimeUnit.SECONDS))
                fixture.main { assertEquals("Network two", session.currentMediaItem?.mediaMetadata?.title?.toString()) }
            } finally {
                releaseTransfer.countDown()
                slowSource.close()
                worker.shutdownNow()
            }
            assertEquals("unauthorized", assertThrows(RendererException::class.java) { connected.status() }.code)
            connected.close()
            fixture.main { manager.beginPairing() }
            fixture.await("new pairing code displayed") { manager.state.value.pairing != null }
            val replacementTrust = RendererClient(address).use { it.pair("Replacement test controller", manager.state.value.pairing!!.code) }
            paired = replacementTrust
            val replacementClient = RendererClient(replacementTrust).also { client = it }
            assertTrue(replacementClient.command(RendererCommand.Reorder(emptyList())).queue.isEmpty())
            fixture.main { assertEquals(0, session.mediaItemCount); assertFalse(session.isPlaying) }
            assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
            val localTrack = fixture.wav("renderer-to-local", 48_000, 48_000 * 8) { frame, _ ->
                (sin(2 * PI * 440 * frame / 48_000) * 2048).toInt()
            }
            fixture.main {
                session.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("manual-local")
                    .setUri(localTrack.toURI().toString()).build())
                session.prepare(); session.play()
            }
            fixture.await("local queue replaces renderer queue", session) { fixture.main { session.isPlaying } }
            assertEquals("queue_changed", assertThrows(RendererException::class.java) { replacementClient.command(RendererCommand.Stop) }.code)
            assertEquals("queue_changed", assertThrows(RendererException::class.java) { replacementClient.command(RendererCommand.Seek(0, 0)) }.code)
            fixture.main { assertTrue(session.isPlaying); assertEquals("manual-local", session.currentMediaItem?.mediaId) }
            val localReplacementStarted = CountDownLatch(1)
            val finishLocalReplacement = CountDownLatch(1)
            val replacementSource = BoundedHttpServer(handler = {
                HttpResponse(200, mapOf("Content-Type" to "audio/wav"), bodyLength = 65_536, writeBody = { output ->
                    output.write(ByteArray(64)); output.flush()
                    localReplacementStarted.countDown()
                    finishLocalReplacement.await(10, TimeUnit.SECONDS)
                    output.write(ByteArray(65_536 - 64))
                })
            })
            val replacementWorker = Executors.newSingleThreadExecutor()
            try {
                replacementSource.start()
                val pending = replacementWorker.submit<String> {
                    try {
                        replacementClient.command(RendererCommand.Queue(listOf(EndpointTrack("stale", "Must not replace local audio",
                            sourceUrl = "http://127.0.0.1:${replacementSource.port}/media/${"c".repeat(43)}"))))
                        "accepted"
                    } catch (error: RendererException) { error.code }
                }
                assertTrue(localReplacementStarted.await(10, TimeUnit.SECONDS))
                fixture.main {
                    session.setMediaItem(MediaItem.Builder().setMediaId("manual-new").setUri(localTrack.toURI().toString()).build())
                    session.prepare(); session.play()
                }
                finishLocalReplacement.countDown()
                assertEquals("queue_changed", pending.get(10, TimeUnit.SECONDS))
                assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
            } finally {
                finishLocalReplacement.countDown(); replacementSource.close(); replacementWorker.shutdownNow()
            }
            fixture.await("manual replacement remains playing", session) { fixture.main { session.isPlaying } }
            fixture.main { manager.enableReceiver(false) }
            fixture.await("renderer disabled") { !manager.state.value.receiverEnabled }
            fixture.main {
                assertEquals(1, session.mediaItemCount)
                assertEquals("manual-new", session.currentMediaItem?.mediaId)
                assertTrue(session.isPlaying)
            }
        } finally {
            client?.close()
            media.close()
            fixture.main {
                paired?.let { manager.revokeController(it.clientId) }
                manager.enableReceiver(false)
                controller?.let { it.pause(); it.stop(); it.clearMediaItems() }
            }
            try {
                paired?.let { created ->
                    val trustStore = RendererTrustStore(context)
                    trustStore.saveControllers(trustStore.controllers().filterNot { it.controller.id == created.clientId })
                }
                if (cachePreserved) {
                    cacheDirectory.mkdirs()
                    cacheDirectory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                    preservedCache.listFiles()?.forEach { it.copyTo(File(cacheDirectory, it.name), overwrite = true) }
                    cacheRestoreComplete = true
                }
                runBlocking { store.restoreBackupPrefs(preferences.copy(booleans = preferences.booleans + ("private_session" to true))).getOrThrow() }
                controller?.let { session ->
                    fixture.main {
                        val queue = originalQueue.orEmpty()
                        session.repeatMode = originalRepeat
                        if (queue.isNotEmpty()) {
                            session.setMediaItems(queue, originalIndex.coerceIn(queue.indices), originalPosition.coerceAtLeast(0))
                            session.prepare()
                        }
                    }
                    setShuffle(fixture, session, originalShuffle, originalQueue.orEmpty().map { it.mediaId })
                    fixture.main { session.playWhenReady = originalPlaying; session.release() }
                }
            } finally {
                if (!cachePreserved || cacheRestoreComplete) preservedCache.deleteRecursively()
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
                try { if (foreground) fixture.removeFixturesAndFinishActivity() }
                finally { runBlocking { store.restoreBackupPrefs(preferences).getOrThrow() } }
            }
        }
    }

    private fun exerciseSenderAdapter(
        fixture: PrecisionPlaybackDeviceTest,
        session: MediaController,
        paired: PairedRenderer,
        media: ScopedMediaServer,
    ) {
        val files = listOf(440, 660).map { frequency ->
            fixture.wav("renderer-sender-$frequency", 48_000, 48_000 * 3) { frame, _ ->
                (sin(2 * PI * frequency * frame / 48_000) * 2048).toInt()
            }
        }
        val items = listOf("Sender first", "Sender second").map { title ->
            MediaItem.Builder().setMediaId("shared-source-id").setUri("aurora-test://shared-source")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Fixture").build()).build()
        }
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sender = fixture.main {
            NetworkQueuePlayer(AuroraNetworkReceiver(paired), { item ->
                val file = files[if (item.mediaMetadata.title.toString() == "Sender first") 0 else 1]
                val grant = media.grant(file, "audio/wav", 60_000)
                NetworkMedia(grant.url("127.0.0.1", media.port), "audio/wav", 3_000, "Original audio", file.length()) {
                    media.revoke(grant.token)
                }
            }) { _, _, error -> error?.let(failures::add) }
        }
        try {
            fixture.main { sender.setMediaItems(items); sender.repeatMode = Player.REPEAT_MODE_ALL; sender.play() }
            fixture.await("actual sender loads cached renderer queue", session) {
                fixture.main { sender.playbackState == Player.STATE_READY && session.isPlaying && session.mediaItemCount == 2 } &&
                    media.stats.activeGrants == 0
            }
            fixture.main {
                assertEquals(listOf("Sender first", "Sender second"), (0 until session.mediaItemCount).map {
                    session.getMediaItemAt(it).mediaMetadata.title.toString()
                })
                assertEquals(2, (0 until session.mediaItemCount).map { session.getMediaItemAt(it).mediaId }.distinct().size)
                assertEquals(listOf("shared-source-id", "shared-source-id"), (0 until sender.mediaItemCount).map {
                    sender.getMediaItemAt(it).mediaId
                })
            }
            assertTrue(media.stats.bytesServed >= files.sumOf { it.length() })
            fixture.main { sender.pause() }
            fixture.await("actual sender pauses receiver", session) { fixture.main { !session.playWhenReady && !session.isPlaying } }
            fixture.main { sender.seekTo(0, 2_500); sender.play() }
            fixture.await("sender polls autonomous cached next track", session) {
                fixture.main { sender.currentMediaItemIndex == 1 && session.currentMediaItemIndex == 1 && session.isPlaying }
            }
            RendererClient(paired).use { control ->
                control.command(RendererCommand.Repeat(Player.REPEAT_MODE_ONE))
                fixture.await("sender polls receiver repeat mode", session) { fixture.main { sender.repeatMode == Player.REPEAT_MODE_ONE } }
                fixture.main { sender.repeatMode = Player.REPEAT_MODE_ALL; sender.pause() }
                fixture.await("sender applies repeat and pause", session) {
                    fixture.main { !session.playWhenReady && session.repeatMode == Player.REPEAT_MODE_ALL }
                }
                fixture.main { sender.seekTo(1, 1_200) }
                fixture.await("actual sender seeks cached track", session) {
                    fixture.main { session.currentMediaItemIndex == 1 && session.currentPosition in 1_150..1_250 }
                }
                control.command(RendererCommand.Volume(0.4f))
                val volume = control.status()
                assertTrue(volume.volumeSteps > 1)
                val initialStep = (volume.volume * volume.volumeSteps).roundToInt()
                fixture.await("sender polls device volume", session) {
                    fixture.main { sender.deviceInfo.maxVolume == volume.volumeSteps && sender.deviceVolume == initialStep }
                }
                fixture.main { sender.increaseDeviceVolume(0) }
                fixture.await("sender raises renderer volume by one step", session) {
                    (control.status().volume * volume.volumeSteps).roundToInt() == initialStep + 1
                }
                fixture.await("sender polls raised volume", session) { fixture.main { sender.deviceVolume == initialStep + 1 } }
                fixture.main { sender.decreaseDeviceVolume(0) }
                fixture.await("sender lowers renderer volume by one step", session) {
                    (control.status().volume * volume.volumeSteps).roundToInt() == initialStep
                }
            }
            assertEquals(0, media.stats.activeGrants)
            fixture.main { sender.stop() }
            fixture.await("actual sender stops receiver", session) {
                fixture.main { !session.playWhenReady && session.playbackState == Player.STATE_IDLE }
            }
            assertTrue(failures.joinToString(), failures.isEmpty())
        } finally {
            runBlocking { sender.shutdown() }
            fixture.main { sender.release() }
        }
    }

    private fun setShuffle(fixture: PrecisionPlaybackDeviceTest, session: MediaController, enabled: Boolean, order: List<String>? = null) {
        val extras = android.os.Bundle().apply {
            putInt("target", if (enabled) 1 else 0)
            if (enabled && order != null) putStringArrayList("order", ArrayList(order))
        }
        fixture.main {
            session.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_SHUFFLE, extras), android.os.Bundle.EMPTY)
        }.get(10, TimeUnit.SECONDS)
    }
}

