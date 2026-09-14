package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Uses the real DataStore, retaining the user's accounts, queue and pre-existing presets. */
class ProcessingPresetDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = (context.applicationContext as AuroraApplication).container.settingsStore

    @Test fun presetsRoundTripCompleteAudioAndPreserveOtherPreferences() = runBlocking {
        val originalPreferences = store.exportPrefs()
        val originalAudio = store.audioPrefs.first()
        val originalPlayback = store.playbackPrefs.first()
        val originalProfile = store.activeEqProfile.first()
        val originalSwitch = store.autoEqAutoSwitch.first()
        val originalPresets = store.processingPresetLibrary.first().presets
        val session = store.session.first()
        val accounts = store.savedSessions.first()
        val ui = store.uiPrefs.first()
        val alarm = store.alarmPrefs.first()
        val bindings = store.eqBindings.first()
        val created = mutableListOf<String>()
        val prefix = "R1 test ${UUID.randomUUID()}"
        try {
            store.setAutoEqAutoSwitch(false)
            val wanted = AudioPrefs(
                eqEnabled = true, eqPreset = -1, eqBands = listOf(-200, 100, 0, -50, 150),
                bassBoost = 123, virtualizer = 234, loudnessGain = 100, replayGain = 2,
                dspMode = DspMode.CUSTOM, dspGraphicBands = listOf(-1f, 0f, 1f),
                dspParametric = listOf(ParamBand(850f, -2.25f, 0.8f, BandType.LOW_SHELF)),
                dspPreampDb = -5f, dspBalance = -0.2f, dspWidth = 0.8f, dspCrossfeed = 0.2f,
                dspLimiterEnabled = true, dspLimiterCeilingDb = -1f,
                dspCompEnabled = true, dspCompThreshDb = -21f, dspCompRatio = 3f,
                dspConvEnabled = false, dspConvIrPath = "", dspConvIrName = "", dspConvMakeupDb = -2f,
                dspGraphicLayout = 1, dspSaturation = 0.1f,
                dspDelayLeftMs = 0.5f, dspDelayRightMs = 1.5f,
                dspTrimLeftDb = -1f, dspTrimRightDb = -2f,
            )
            writeAudio(wanted)
            store.setMono(!originalPlayback.monoAudio)
            store.setSkipSilence(!originalPlayback.skipSilence)
            store.setPreferHighRes(!originalPlayback.preferHighRes)
            store.setActiveEqProfile("R1 fixture correction")
            val targetPlayback = store.playbackPrefs.first()
            val preset = store.saveProcessingPreset(prefix).getOrThrow().also { created += it.id }
            assertEquals(wanted, preset.audio)
            writeAudio(AudioPrefs())
            restorePlayback(originalPlayback)
            store.setActiveEqProfile("")
            val beforeApply = store.processingSettings.first()
            val wantedSettings = ProcessingSettings(wanted, targetPlayback, preset.rack)
            val observations = mutableListOf<ProcessingSettings>()
            val observing = CompletableDeferred<Unit>()
            val observedTarget = CompletableDeferred<Unit>()
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                store.processingSettings.distinctUntilChanged().collect {
                    observations += it
                    observing.complete(Unit)
                    if (it == wantedSettings) observedTarget.complete(Unit)
                }
            }
            try {
                withTimeout(5_000) { observing.await() }
                store.applyProcessingPreset(preset.id).getOrThrow()
                withTimeout(5_000) { observedTarget.await() }
            } finally { watcher.cancelAndJoin() }
            assertTrue("Observers must not see new DSP with old playback settings", observations.all { it == beforeApply || it == wantedSettings })
            assertEquals(targetPlayback, store.playbackPrefs.first())
            assertEquals("R1 fixture correction", store.activeEqProfile.first())
            assertFalse(store.autoEqAutoSwitch.first())
            store.renameProcessingPreset(preset.id, "$prefix renamed").getOrThrow()
            val renamed = store.processingPresetLibrary.first().presets.first { it.id == preset.id }
            assertEquals("$prefix renamed", renamed.name)
            assertEquals(preset.audio, renamed.audio)
            val duplicate = store.duplicateProcessingPreset(preset.id, "$prefix copy").getOrThrow().also { created += it.id }
            assertNotEquals(preset.id, duplicate.id)
            assertEquals(preset.audio, duplicate.audio)
            assertEquals(preset.playback, duplicate.playback)
            store.deleteProcessingPreset(preset.id).getOrThrow()
            assertTrue(store.processingPresetLibrary.first().presets.any { it.id == duplicate.id })
            assertEquals(session, store.session.first())
            assertEquals(accounts, store.savedSessions.first())
            assertEquals(ui, store.uiPrefs.first())
            assertEquals(alarm, store.alarmPrefs.first())
            assertEquals(bindings, store.eqBindings.first())
        } finally {
            writeAudio(originalAudio)
            restorePlayback(originalPlayback)
            store.setActiveEqProfile(originalProfile)
            created.forEach { store.deleteProcessingPreset(it) }
            store.setAutoEqAutoSwitch(originalSwitch)
            store.restoreBackupPrefs(originalPreferences).getOrThrow()
        }
        assertEquals(originalPresets, store.processingPresetLibrary.first().presets)
    }

    @Test fun convolutionSnapshotsSurviveReplacementAndRejectMissingAssetsWithoutWrites() = runBlocking {
        val originalPreferences = store.exportPrefs()
        val originalAudio = store.audioPrefs.first()
        val originalPlayback = store.playbackPrefs.first()
        val originalProfile = store.activeEqProfile.first()
        val originalSwitch = store.autoEqAutoSwitch.first()
        val originalPresets = store.processingPresetLibrary.first().presets
        val created = mutableListOf<String>()
        val file = File(context.cacheDir, "r1-ir-${UUID.randomUUID()}.wav")
        var savedAsset: File? = null
        var originalAsset: ByteArray? = null
        try {
            store.setAutoEqAutoSwitch(false)
            val originalBytes = impulseWav(1000)
            file.writeBytes(originalBytes)
            writeAudio(originalAudio.copy(dspConvEnabled = true, dspConvIrPath = file.absolutePath, dspConvIrName = "R1 impulse.wav"))
            val preset = store.saveProcessingPreset("R1 IR ${UUID.randomUUID()}").getOrThrow().also { created += it.id }
            val asset = File(preset.audio.dspConvIrPath)
            savedAsset = asset
            originalAsset = asset.readBytes()
            assertNotEquals(file.absolutePath, asset.absolutePath)
            assertArrayEquals(originalBytes, asset.readBytes())
            file.writeBytes(impulseWav(2000))
            store.applyProcessingPreset(preset.id).getOrThrow()
            assertArrayEquals(originalBytes, asset.readBytes())
            assertEquals(asset.absolutePath, store.audioPrefs.first().dspConvIrPath)
            // Missing IR must reject the whole operation, including mode/mono/output settings.
            writeAudio(AudioPrefs())
            val before = store.exportPrefs()
            assertTrue(asset.delete())
            assertTrue(store.applyProcessingPreset(preset.id).isFailure)
            assertEquals(before, store.exportPrefs())
            asset.writeBytes(originalBytes)
            asset.writeBytes(impulseWav(3000))
            assertTrue("Changed dependency must not silently load different correction", store.applyProcessingPreset(preset.id).isFailure)
            assertEquals(before, store.exportPrefs())
        } finally {
            if (savedAsset != null && originalAsset != null) savedAsset.writeBytes(originalAsset)
            writeAudio(originalAudio)
            restorePlayback(originalPlayback)
            store.setActiveEqProfile(originalProfile)
            created.forEach { store.deleteProcessingPreset(it) }
            store.setAutoEqAutoSwitch(originalSwitch)
            store.restoreBackupPrefs(originalPreferences).getOrThrow()
            file.delete()
            // Save allocates a new UUID-named copy; delete our fixture only after restoring settings.
            savedAsset?.takeIf { candidate ->
                candidate.absolutePath != originalAudio.dspConvIrPath &&
                    originalPresets.none { it.audio.dspConvIrPath == candidate.absolutePath }
            }?.delete()
        }
        assertEquals(originalPresets, store.processingPresetLibrary.first().presets)
    }

    private suspend fun restorePlayback(p: PlaybackPrefs) {
        store.setSkipSilence(p.skipSilence)
        store.setCrossfade(p.crossfadeSec)
        store.setCrossfadeCurve(p.crossfadeCurve)
        store.setCrossfadeHeadroom(p.crossfadeHeadroom)
        store.setGapless(p.gapless)
        store.setDefaultSpeed(p.defaultSpeed)
        store.setMono(p.monoAudio)
        store.setPreferHighRes(p.preferHighRes)
        store.setBitPerfectUsb(p.bitPerfectUsb)
        store.setIndependentOutput(p.independentOutput)
    }

    /** A restricted preference import avoids writing authentication or account data during cleanup. */
    private suspend fun writeAudio(p: AudioPrefs) = store.importPrefs(PrefsBackup(
        strings = mapOf(
            "eq_bands" to p.eqBands.joinToString(","),
            "dsp_graphic" to p.dspGraphicBands.joinToString(","),
            "dsp_parametric" to p.dspParametric.joinToString(";") { "${it.freqHz}:${it.gainDb}:${it.q}:${it.type}" },
            "dsp_conv_path" to p.dspConvIrPath, "dsp_conv_name" to p.dspConvIrName,
        ),
        ints = mapOf("eq_preset" to p.eqPreset, "bass_boost" to p.bassBoost,
            "virtualizer" to p.virtualizer, "loudness_gain" to p.loudnessGain,
            "replay_gain" to p.replayGain, "dsp_mode" to p.dspMode, "dsp_graphic_layout" to p.dspGraphicLayout),
        booleans = mapOf("eq_enabled" to p.eqEnabled, "dsp_limiter" to p.dspLimiterEnabled,
            "dsp_comp" to p.dspCompEnabled, "dsp_conv_enabled" to p.dspConvEnabled),
        floats = mapOf("dsp_preamp" to p.dspPreampDb, "dsp_balance" to p.dspBalance,
            "dsp_width" to p.dspWidth, "dsp_crossfeed" to p.dspCrossfeed,
            "dsp_ceiling" to p.dspLimiterCeilingDb, "dsp_comp_thresh" to p.dspCompThreshDb,
            "dsp_comp_ratio" to p.dspCompRatio, "dsp_conv_makeup" to p.dspConvMakeupDb,
            "dsp_saturation" to p.dspSaturation, "dsp_delay_l" to p.dspDelayLeftMs,
            "dsp_delay_r" to p.dspDelayRightMs, "dsp_trim_l" to p.dspTrimLeftDb, "dsp_trim_r" to p.dspTrimRightDb),
    ))

    private fun impulseWav(value: Short): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(2); putInt(48_000); putInt(192_000); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(4); putShort(value); putShort(value)
    }.array()
}
