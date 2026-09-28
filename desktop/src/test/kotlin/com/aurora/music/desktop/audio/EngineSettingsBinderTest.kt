package com.aurora.music.desktop.audio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aurora.music.data.DspMode
import com.aurora.music.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.pow

class EngineSettingsBinderTest {
    @Test fun storedPlaybackAndDspSettingsReachTheEngine() {
        val directory = Files.createTempDirectory("aurora-settings").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = FakeBackend()
        val engine = DesktopPlaybackEngine(backend)
        try {
            val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(directory, "aurora_settings.preferences_pb") },
                directory, directory)
            engine.bindSettings(store, scope)
            runBlocking {
                store.setCrossfade(4)
                store.setCrossfadeCurve("POWER")
                store.setReplayGain(2)
                store.setDspMode(DspMode.CUSTOM)
                store.setDspPreamp(-6f)
            }
            eventually { engine.config.crossfadeMs == 4_000 && engine.config.crossfadeCurve == "POWER" && engine.config.replayGain == 2 }
            Tracks().use { tracks ->
                val file = tracks.wav("level", 48_000, 16, 9_600) { _, _ -> 16_384 }
                EventLog(engine).use { log ->
                    engine.setQueue(listOf(tracks.song(file)))
                    log.await { EngineEvent.Ended in it }
                }
            }
            val heard = backend.last.heardFloats()
            assertEquals(0.5 * 10.0.pow(-6.0 / 20.0), heard[heard.size - 2].toDouble(), 1e-6)
            assertTrue(engine.state.value.processing.dspActive)
        } finally {
            engine.close()
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
