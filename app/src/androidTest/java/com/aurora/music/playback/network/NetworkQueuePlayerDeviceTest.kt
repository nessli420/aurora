package com.aurora.music.playback.network

import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.UUID

@OptIn(UnstableApi::class)
class NetworkQueuePlayerDeviceTest {
    @Test fun scopedAudioReachesReceiverAndVolumeIsNeverResetOnLoad() = runBlocking {
        fixture { server, file ->
            val receiver = FakeReceiver()
            val player = onMain { NetworkQueuePlayer(receiver, { shared(server, file) }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItems(listOf(item("same", "First"), item("same", "Second"))); player.prepare() }
                awaitMain { player.playbackState == Player.STATE_READY }
                assertArrayEquals(file.readBytes(), receiver.captured.single())
                assertTrue(receiver.volumeWrites.isEmpty())
                awaitMain { player.isCommandAvailable(Player.COMMAND_GET_VOLUME) }
                assertEquals(.27f, onMain { player.volume }, .001f)
                onMain { player.play(); player.seekTo(3_500); player.pause() }
                awaitMain { !receiver.playing && receiver.position == 3_500L }
                onMain {
                    player.shuffle(1, listOf("same", "same"))
                    player.shuffle(0, null)
                    assertEquals("First", player.getMediaItemAt(0).mediaMetadata.title.toString())
                    assertEquals("Second", player.getMediaItemAt(1).mediaMetadata.title.toString())
                    player.setVolume(.4f)
                }
                awaitMain { receiver.volumeWrites == listOf(.4f) }
                onMain { player.seekTo(1, 0) }
                awaitMain { receiver.loaded.size == 2 && player.playbackState == Player.STATE_READY }
                assertEquals(listOf(.4f), receiver.volumeWrites)
                onMain { player.clearMediaItems() }
                awaitMain { server.stats.activeGrants == 0 && !receiver.playing }
                assertEquals(0, onMain { player.mediaItemCount })
            } finally { player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun deviceVolumeUsesReceiverStepsAndUnsupportedRoutesRemainFixed() = runBlocking {
        fixture { server, file ->
            val receiver = FakeReceiver().apply { steps = 15; level = 4f / 15 }
            val player = onMain { NetworkQueuePlayer(receiver, { shared(server, file) }) { _, _, _ -> } }
            try {
                onMain {
                    assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, player.deviceInfo.playbackType)
                    assertEquals(0, player.deviceInfo.maxVolume)
                    assertFalse(player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
                    player.increaseDeviceVolume(0)
                    player.setMediaItem(item("volume")); player.prepare()
                }
                awaitMain("observed receiver device volume") { player.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME) }
                assertTrue(receiver.volumeWrites.isEmpty())
                onMain {
                    assertEquals(15, player.deviceInfo.maxVolume)
                    assertEquals(4, player.deviceVolume)
                    player.increaseDeviceVolume(0)
                }
                awaitMain { receiver.level == 5f / 15 }
                onMain { player.decreaseDeviceVolume(0) }
                awaitMain { receiver.level == 4f / 15 }
                onMain { player.setDeviceVolume(100, 0); player.increaseDeviceVolume(0) }
                awaitMain { receiver.level == 1f && player.deviceVolume == 15 }
                onMain { player.setDeviceVolume(0, 0); player.decreaseDeviceVolume(0) }
                awaitMain { receiver.level == 0f && player.deviceVolume == 0 && player.isDeviceMuted }
                onMain { player.setDeviceVolume(7, 0); player.setDeviceMuted(true, 0) }
                awaitMain { receiver.level == 0f && player.isDeviceMuted }
                onMain { player.setDeviceMuted(false, 0) }
                awaitMain { receiver.level == 7f / 15 && player.deviceVolume == 7 && !player.isDeviceMuted }
                onMain { receiver.volumeSupported = false }
                awaitMain("fixed remote output") {
                    player.deviceInfo.maxVolume == 0 && !player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS) &&
                        !player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS) &&
                        !player.isCommandAvailable(Player.COMMAND_SET_VOLUME)
                }
                val writes = receiver.volumeWrites.size
                onMain {
                    assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, player.deviceInfo.playbackType)
                    player.setDeviceVolume(10, 0); player.increaseDeviceVolume(0); player.setDeviceMuted(true, 0); player.volume = .9f
                }
                assertEquals(writes, receiver.volumeWrites.size)
                onMain { receiver.volumeSupported = true; receiver.steps = 0 }
                awaitMain { player.isCommandAvailable(Player.COMMAND_GET_VOLUME) }
                assertFalse(onMain { player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS) })
                assertEquals(0, onMain { player.deviceInfo.maxVolume })
            } finally { player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun seekAndPauseDuringReceiverLoadUseTheLatestIntent() = runBlocking {
        fixture { server, file ->
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val receiver = FakeReceiver().apply { loadAction = { entered.complete(Unit); gate.await() } }
            val player = onMain { NetworkQueuePlayer(receiver, { shared(server, file) }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItem(item("first")); player.play() }
                entered.await()
                onMain { player.seekTo(4_200); player.pause() }
                gate.complete(Unit)
                awaitMain { player.playbackState == Player.STATE_READY }
                assertEquals(4_200L, receiver.position)
                assertFalse(receiver.playing)
                assertFalse(onMain { player.playWhenReady })
                assertEquals(listOf(4_200L), receiver.seeks)
            } finally { gate.complete(Unit); player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun staleStatusCannotUndoPauseSeekOrBufferingIntent() = runBlocking {
        fixture { server, file ->
            val receiver = FakeReceiver()
            val player = onMain { NetworkQueuePlayer(receiver, { shared(server, file) }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItem(item("first")); player.play() }
                awaitMain { player.playbackState == Player.STATE_READY && receiver.playing }
                val entered = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                onMain { receiver.statusAction = { entered.complete(Unit); gate.await() } }
                entered.await()
                onMain { player.pause(); player.seekTo(9_000) }
                gate.complete(Unit)
                awaitMain { !receiver.playing && receiver.position == 9_000L }
                assertFalse(onMain { player.playWhenReady })
                assertEquals(9_000L, onMain { player.currentPosition })
                onMain { receiver.statusAction = null; receiver.buffering = true; player.play() }
                awaitMain { player.playbackState == Player.STATE_BUFFERING }
                assertTrue(onMain { player.playWhenReady })
                onMain { receiver.buffering = false }
                awaitMain { player.playbackState == Player.STATE_READY }
            } finally { player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun canceledRenderingCannotLoadAnOldTrackOrLeakItsGrant() = runBlocking {
        fixture { server, file ->
            val receiver = FakeReceiver()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val player = onMain { NetworkQueuePlayer(receiver, { track ->
                if (track.mediaId == "old") withContext(NonCancellable) { entered.complete(Unit); gate.await() }
                shared(server, file)
            }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItem(item("old")); player.play() }
                entered.await()
                onMain { player.setMediaItem(item("new")) }
                gate.complete(Unit)
                awaitMain { player.playbackState == Player.STATE_READY && receiver.loaded.isNotEmpty() }
                assertEquals(listOf("new"), receiver.loaded)
                assertEquals(1, server.stats.activeGrants)
            } finally { gate.complete(Unit); player.shutdown(); onMain { player.release() } }
            assertEquals(0, server.stats.activeGrants)
        }
    }

    @Test fun concurrentShutdownWaitsForLateLoadThenStopsAndRevokes() = runBlocking {
        fixture { server, file ->
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val receiver = FakeReceiver().apply {
                loadAction = { withContext(NonCancellable) { entered.complete(Unit); gate.await(); playing = true } }
            }
            val player = onMain { NetworkQueuePlayer(receiver, { shared(server, file) }) { _, _, _ -> } }
            onMain { player.setMediaItem(item("first")); player.play() }
            entered.await()
            val first = async { player.shutdown() }
            val second = async { player.shutdown() }
            delay(100)
            assertFalse(first.isCompleted)
            gate.complete(Unit)
            withTimeout(4_000) { first.await(); second.await() }
            assertFalse(receiver.playing)
            assertEquals(0, server.stats.activeGrants)
            assertEquals("stop", receiver.events.last())
            onMain { player.release() }
            awaitMain { receiver.closed }
        }
    }

    @Test fun eosRepeatRecoveryAndUnsupportedVolumeKeepWorking() = runBlocking {
        fixture { server, file ->
            val receiver = FakeReceiver().apply { volumeSupported = false }
            var attempts = 0
            val player = onMain { NetworkQueuePlayer(receiver, {
                if (attempts++ == 0) throw NetworkOutputException("Test source unavailable.")
                shared(server, file)
            }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItems(listOf(item("first"), item("second"))) }
                awaitMain { player.playerError != null }
                onMain { player.prepare() }
                awaitMain { player.playbackState == Player.STATE_READY }
                assertFalse(onMain { player.isCommandAvailable(Player.COMMAND_SET_VOLUME) })
                assertTrue(receiver.volumeWrites.isEmpty())
                onMain { player.play(); player.repeatMode = Player.REPEAT_MODE_ONE; receiver.finished = true }
                awaitMain { receiver.loaded.size == 2 }
                assertEquals(listOf("first", "first"), receiver.loaded)
                onMain { player.repeatMode = Player.REPEAT_MODE_OFF; receiver.finished = true }
                awaitMain { receiver.loaded.size == 3 }
                assertEquals("second", receiver.loaded.last())
                onMain { receiver.finished = true }
                awaitMain { player.playbackState == Player.STATE_ENDED }
                onMain { player.play() }
                awaitMain { receiver.loaded.size == 4 && player.playbackState == Player.STATE_READY }
                assertEquals(0L, receiver.position)
            } finally { player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun receiverOwnsWholeQueueAndCachedControlsPreserveDuplicateOccurrences() = runBlocking {
        fixture { server, file ->
            val receiver = FakeQueueReceiver()
            var renders = 0
            val player = onMain { NetworkQueuePlayer(receiver, { renders++; shared(server, file) }) { _, _, _ -> } }
            try {
                onMain {
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    player.setMediaItems(listOf(item("same", "First"), item("same", "Second"), item("third")), 1, 2_200)
                    player.play()
                }
                awaitMain { player.playbackState == Player.STATE_READY }
                assertEquals(3, renders)
                assertEquals(1, receiver.loads)
                assertEquals(3, receiver.ids.distinct().size)
                assertEquals(3, receiver.captured.size)
                assertEquals(0, server.stats.activeGrants)
                assertEquals(1, receiver.index)
                assertEquals(2_200L, receiver.position)
                assertEquals(Player.REPEAT_MODE_ALL, receiver.repeatMode)
                onMain { receiver.index = 2; receiver.position = 700; receiver.repeatMode = Player.REPEAT_MODE_ONE }
                awaitMain("receiver advance and repeat") { player.currentMediaItemIndex == 2 && player.currentPosition in 700L..1_500L && player.repeatMode == Player.REPEAT_MODE_ONE }
                onMain { player.seekTo(0, 900); player.pause() }
                awaitMain("cached seek and pause") { receiver.index == 0 && receiver.position == 900L && !receiver.playing }
                val original = receiver.ids.toList()
                onMain {
                    player.shuffle(1, listOf("same", "same", "third"))
                    player.shuffle(0, null)
                    player.moveMediaItem(2, 0)
                }
                awaitMain("cached shuffle restore and move") { receiver.ids == listOf(original[2], original[0], original[1]) }
                assertEquals("First", onMain { player.currentMediaItem?.mediaMetadata?.title.toString() })
                onMain { player.removeMediaItem(1) }
                awaitMain("remove current occurrence") { receiver.ids == listOf(original[2], original[1]) && receiver.index == 1 && receiver.position == 0L }
                assertEquals("Second", onMain { player.currentMediaItem?.mediaMetadata?.title.toString() })
                onMain { player.repeatMode = Player.REPEAT_MODE_OFF; receiver.finished = true; receiver.playing = false }
                awaitMain("receiver queue ended") { player.playbackState == Player.STATE_ENDED }
                onMain { player.play() }
                awaitMain("restart ended receiver queue") { receiver.playing && !receiver.finished && player.playbackState == Player.STATE_READY }
                assertEquals(3, renders)
                assertEquals(1, receiver.loads)
                onMain { player.clearMediaItems() }
                awaitMain("clear cached queue") { receiver.ids.isEmpty() && !receiver.playing }
                assertEquals(0, server.stats.activeGrants)
            } finally { player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun staleOwnedStatusCannotRestoreRemovedEntriesOrOldControls() = runBlocking {
        fixture { server, file ->
            val receiver = FakeQueueReceiver()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val player = onMain { NetworkQueuePlayer(receiver, { shared(server, file) }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItems(listOf(item("first"), item("second"), item("third"))); player.play() }
                awaitMain { player.playbackState == Player.STATE_READY }
                onMain { receiver.statusAction = { entered.complete(Unit); gate.await() } }
                entered.await()
                onMain {
                    player.removeMediaItem(0)
                    player.seekTo(1, 3_500)
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    player.pause()
                }
                gate.complete(Unit)
                awaitMain { receiver.ids.size == 2 && receiver.index == 1 && receiver.position == 3_500L && !receiver.playing }
                onMain { receiver.statusAction = null }
                awaitMain { player.currentMediaItemIndex == 1 && player.currentPosition == 3_500L && player.repeatMode == Player.REPEAT_MODE_ALL }
                assertNull(onMain { player.playerError })
                assertFalse(onMain { player.playWhenReady })
                assertEquals(listOf("second", "third"), onMain { (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } })
                assertEquals(1, receiver.loads)
            } finally { gate.complete(Unit); player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun ownedQueueLimitsAndPreparationFailureReleaseEveryGrant() = runBlocking {
        fixture { server, file ->
            val receiver = FakeQueueReceiver()
            var renders = 0
            var oversized = true
            val player = onMain { NetworkQueuePlayer(receiver, {
                renders++
                if (!oversized && it.mediaId == "bad") throw NetworkOutputException("Test source unavailable.")
                shared(server, file, if (oversized) 257L * 1024 * 1024 else file.length())
            }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItems((0..100).map { item("track-$it") }); player.prepare(); player.play() }
                awaitMain { player.playerError?.message?.contains("100 tracks") == true }
                assertEquals(0, renders)
                onMain { player.setMediaItems(listOf(item("first"), item("second"))) }
                awaitMain { player.playerError?.message?.contains("512 MB") == true && server.stats.activeGrants == 0 }
                assertEquals(0, receiver.loads)
                onMain { oversized = false; player.setMediaItems(listOf(item("first"), item("bad"))) }
                awaitMain { player.playerError?.message == "Test source unavailable." && server.stats.activeGrants == 0 }
                assertEquals(0, receiver.loads)
                onMain { player.setMediaItems(listOf(item("recovered"))); player.play() }
                awaitMain { player.playbackState == Player.STATE_READY && receiver.playing && server.stats.activeGrants == 0 }
                assertEquals(1, receiver.loads)
            } finally { player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun removingAndMovingDuringRemoteReorderKeepsTheIntendedTrack() = runBlocking {
        fixture { server, file ->
            val receiver = FakeQueueReceiver()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val player = onMain { NetworkQueuePlayer(receiver, { shared(server, file) }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItems(listOf(item("first"), item("second"), item("third"))) }
                awaitMain { player.playbackState == Player.STATE_READY }
                val expected = receiver.ids[1]
                onMain {
                    receiver.reorderAction = { entered.complete(Unit); gate.await() }
                    player.removeMediaItem(0)
                }
                entered.await()
                onMain { player.moveMediaItem(0, 1); receiver.reorderAction = null }
                gate.complete(Unit)
                awaitMain { receiver.ids[receiver.index] == expected && receiver.index == 1 }
                awaitMain { player.currentMediaItemIndex == 1 && player.currentMediaItem?.mediaId == "second" }
                assertEquals(1, receiver.loads)
            } finally { gate.complete(Unit); player.shutdown(); onMain { player.release() } }
        }
    }

    @Test fun canceledWholeQueuePreparationNeverCommitsAndReleasesPartialFiles() = runBlocking {
        fixture { server, file ->
            val receiver = FakeQueueReceiver()
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val player = onMain { NetworkQueuePlayer(receiver, { track ->
                val prepared = shared(server, file)
                if (track.mediaId == "late") withContext(NonCancellable) { entered.complete(Unit); gate.await() }
                prepared
            }) { _, _, _ -> } }
            try {
                onMain { player.setMediaItems(listOf(item("old"), item("late"))); player.play() }
                entered.await()
                assertEquals(2, server.stats.activeGrants)
                onMain { player.setMediaItems(listOf(item("new"))) }
                gate.complete(Unit)
                awaitMain { player.playbackState == Player.STATE_READY && receiver.loads == 1 && server.stats.activeGrants == 0 }
                assertEquals(listOf("new"), receiver.loadedTitles)
            } finally { gate.complete(Unit); player.shutdown(); onMain { player.release() } }
        }
    }

    private class FakeQueueReceiver : NetworkReceiver {
        override val name = "Queue receiver"
        override val kind = "Aurora"
        override val host = "127.0.0.1"
        override val ownsQueue = true
        var ids = emptyList<String>()
        var index = 0
        var position = 0L
        var playing = false
        var repeatMode = Player.REPEAT_MODE_OFF
        var finished = false
        var loads = 0
        var loadedTitles = emptyList<String>()
        var statusAction: (suspend () -> Unit)? = null
        var reorderAction: (suspend () -> Unit)? = null
        val captured = mutableListOf<ByteArray>()
        override suspend fun load(item: MediaItem, media: NetworkMedia, positionMs: Long) = error("Expected whole queue")
        override suspend fun loadQueue(items: List<ReceiverQueueItem>, index: Int, positionMs: Long, repeatMode: Int) {
            for (entry in items) captured += withContext(Dispatchers.IO) {
                val connection = URL(entry.media.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 2_000; connection.readTimeout = 2_000
                try { connection.inputStream.use { it.readBytes() } } finally { connection.disconnect() }
            }
            ids = items.map { it.uid }
            loadedTitles = items.map { it.item.mediaMetadata.title.toString() }
            this.index = index; position = positionMs; this.repeatMode = repeatMode
            playing = false; finished = false; loads++
        }
        override suspend fun playing(value: Boolean) { playing = value }
        override suspend fun seek(positionMs: Long) { position = positionMs; finished = false }
        override suspend fun seekTo(positionMs: Long, index: Int?) {
            if (index != null) { check(index in ids.indices); this.index = index }
            seek(positionMs)
        }
        override suspend fun reorder(ids: List<String>) {
            check(ids.distinct().size == ids.size && ids.all { it in this.ids })
            val current = this.ids.getOrNull(index)
            this.ids = ids
            index = ids.indexOf(current).takeIf { it >= 0 } ?: index.coerceAtMost((ids.size - 1).coerceAtLeast(0))
            if (current !in ids) position = 0
            if (ids.isEmpty()) { playing = false; finished = false }
            reorderAction?.invoke()
        }
        override suspend fun repeat(mode: Int) { repeatMode = mode }
        override suspend fun volume(value: Float) = Unit
        override suspend fun status(): NetworkReceiverState {
            val state = NetworkReceiverState(playing, position, 20_000, finished, .27f, playing,
                queueIds = ids.toList(), queueIndex = index, repeatMode = repeatMode)
            statusAction?.invoke()
            return state
        }
        override suspend fun stop() { playing = false }
    }

    private class FakeReceiver : NetworkReceiver {
        override val name = "Test receiver"
        override val kind = "Test"
        override val host = "127.0.0.1"
        var volumeSupported = true
        override val supportsVolume: Boolean get() = volumeSupported
        var steps = 100
        override val volumeSteps: Int get() = steps
        var playing = false
        var position = 0L
        var finished = false
        var buffering = false
        var closed = false
        var level = .27f
        var loadAction: (suspend () -> Unit)? = null
        var statusAction: (suspend () -> Unit)? = null
        val loaded = mutableListOf<String>()
        val captured = mutableListOf<ByteArray>()
        val volumeWrites = mutableListOf<Float>()
        val seeks = mutableListOf<Long>()
        val events = mutableListOf<String>()
        override suspend fun load(item: MediaItem, media: NetworkMedia, positionMs: Long) {
            val bytes = withContext(Dispatchers.IO) {
                val connection = URL(media.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 2_000; connection.readTimeout = 2_000
                try {
                    assertEquals(media.mimeType, connection.contentType)
                    connection.inputStream.use { it.readBytes() }
                } finally { connection.disconnect() }
            }
            loadAction?.invoke()
            captured.add(bytes); loaded.add(item.mediaId); events.add("load")
            position = positionMs; finished = false
        }
        override suspend fun playing(value: Boolean) { playing = value; events.add(if (value) "play" else "pause") }
        override suspend fun seek(positionMs: Long) { position = positionMs; seeks.add(positionMs) }
        override suspend fun volume(value: Float) { check(volumeSupported); level = value; volumeWrites.add(value) }
        override suspend fun status(): NetworkReceiverState {
            val snapshot = NetworkReceiverState(playing && !buffering, position, 20_000, finished,
                level.takeIf { volumeSupported }, playing, buffering)
            statusAction?.invoke()
            return snapshot
        }
        override suspend fun stop() { playing = false; events.add("stop") }
        override fun close() { closed = true }
    }

    private fun item(id: String, title: String = id) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()

    private fun shared(server: ScopedMediaServer, file: File, sizeBytes: Long = file.length()): NetworkMedia {
        val grant = server.grant(file, "audio/wav")
        return NetworkMedia(grant.url("127.0.0.1", server.port), "audio/wav", 20_000, "Test processing", sizeBytes) { server.revoke(grant.token) }
    }

    private suspend fun fixture(block: suspend (ScopedMediaServer, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "network-player-${UUID.randomUUID()}.wav")
        file.writeBytes(ByteArray(8_192) { (it % 251).toByte() })
        try { ScopedMediaServer().use { server -> server.start(InetAddress.getByName("127.0.0.1")); block(server, file) } }
        finally { file.delete() }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    private suspend fun awaitMain(stage: String = "condition", condition: () -> Boolean) {
        try { withTimeout(6_000) { while (!onMain(condition)) delay(25) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $stage", error) }
    }
}
