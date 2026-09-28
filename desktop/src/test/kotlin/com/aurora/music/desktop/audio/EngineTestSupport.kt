package com.aurora.music.desktop.audio

import com.aurora.music.desktop.audio.decode.wavBytes
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

internal class Tracks : AutoCloseable {
    private val directory: File = Files.createTempDirectory("aurora-engine").toFile()

    fun wav(name: String, rate: Int, bits: Int, frames: Int, float: Boolean = false, sample: (Int, Int) -> Number): File =
        File(directory, "$name.wav").apply { writeBytes(wavBytes(rate, 2, bits, frames, float, sample)) }

    fun song(file: File, id: String = file.nameWithoutExtension, rgTrack: Float = 0f, rgAlbum: Float = 0f) =
        Song(id, id, "Artist", "Album", "", 0, streamUrl = file.toPath().toUri().toString(),
            replayGainTrack = rgTrack, replayGainAlbum = rgAlbum)

    override fun close() {
        directory.deleteRecursively()
    }
}

internal class EventLog(engine: PlaybackEngine) : AutoCloseable {
    val events = CopyOnWriteArrayList<EngineEvent>()
    private val job: Job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events.collect { events += it }
    }

    inline fun <reified T : EngineEvent> all(): List<T> = events.filterIsInstance<T>()

    fun transitions() = all<EngineEvent.Transition>()

    fun await(timeoutMs: Long = 10_000, predicate: (List<EngineEvent>) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate(events)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for events: $events" }
            Thread.sleep(5)
        }
    }

    override fun close() = job.cancel()
}

internal fun PlaybackEngine.await(timeoutMs: Long = 10_000, predicate: (EngineState) -> Boolean): EngineState = runBlocking {
    try {
        withTimeout(timeoutMs) { state.first(predicate) }
    } catch (e: Exception) {
        throw AssertionError("Timed out waiting for engine state: ${state.value}", e)
    }
}

internal fun pcm16(value: Int) = value / 32_768.0f

internal fun pcm24(value: Int) = value / 8_388_608.0f
