package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.routes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OutputPresetBindingsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val key = RouteIdentity.key(8, "fixture-device-one", false, true)!!
    private val otherKey = RouteIdentity.key(8, "fixture-device-two", false, true)!!
    private val route get() = ProcessingRoute(ProcessingRouteKind.ANDROID, key, "Fixture output", "Confirmed", "8:Fixture output")

    private fun fixture(block: suspend (ProcessingPreset, ProcessingPreset) -> Unit) = runBlocking {
        val original = store.exportPrefs()
        val first = preset("First", -5f)
        val second = preset("Second", -9f)
        try {
            store.restoreBackupPrefs(original.copy(strings = original.strings + mapOf(
                "processing_presets_v1" to ProcessingPresetCodec.encode(listOf(first, second)),
                ProcessingRouteCodec.PREFERENCE_KEY to ProcessingRouteCodec.encode(ProcessingRouteRules()),
                "eq_bindings" to "[]"), booleans = original.booleans + ("autoeq_autoswitch" to false))).getOrThrow()
            store.setAutoEqAutoSwitch(false)
            store.processingRoutes.publish(route)
            block(first, second)
        } finally {
            store.processingRoutes.publish(ProcessingRoute())
            store.restoreBackupPrefs(original).getOrThrow()
            assertEquals(original, store.exportPrefs())
        }
    }

    @Test fun fullPresetIsAtomicAndStaleRoutesCannotApply() = fixture { first, _ ->
        store.bindCurrentRoute(first.id, "").getOrThrow()
        val expected = store.processingRoutes.current
        val before = store.exportPrefs()
        store.processingRoutes.publish(route.copy(key = otherKey))
        assertTrue(store.bindCurrentRoute(first.id, "", expected).isFailure)
        assertNull(store.applyRoutePreset(expected, first.id).getOrThrow())
        assertEquals(before, store.exportPrefs())
        store.processingRoutes.publish(route)
        assertNotNull(store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow())
        val processing = store.processingSettings.first()
        assertEquals(first.audio.dspPreampDb, processing.audio.dspPreampDb)
        assertEquals(first.audio.dspWidth, processing.audio.dspWidth)
        assertEquals(first.rack, processing.rack)
        assertEquals(first.playback.monoAudio, processing.playback.monoAudio)
    }

    @Test fun manualApplySurvivesReconnectAndBlocksLegacyCorrection() = fixture { first, second ->
        store.bindCurrentRoute(first.id, "").getOrThrow()
        store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow()
        store.applyProcessingPreset(second.id).getOrThrow()
        assertTrue(key in store.processingRouteRules.first().manual)
        store.processingRoutes.publish(ProcessingRoute())
        store.processingRoutes.publish(route)
        val before = store.exportPrefs()
        assertNull(store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow())
        assertEquals(before, store.exportPrefs())
        val legacy = EqBinding(key, "Fixture", "Legacy", -1f, listOf(ParamBand(1000f, 1f, 1f)))
        store.upsertEqBinding(legacy); store.setAutoEqAutoSwitch(true)
        assertFalse(store.applyEqBindingIfEnabled(legacy, store.processingRoutes.current))
        assertEquals(second.audio.dspPreampDb, store.audioPrefs.first().dspPreampDb)
        store.holdCurrentRoute(false).getOrThrow()
        assertNotNull(store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow())
        assertEquals(first.audio.dspPreampDb, store.audioPrefs.first().dspPreampDb)
    }

    @Test fun headphoneChoiceChangesOnlyItsBoundPresetAndUnboundChoiceKeepsSound() = fixture { first, second ->
        store.bindCurrentRoute(first.id, "Open back").getOrThrow()
        store.bindCurrentRoute(second.id, "Closed back").getOrThrow()
        store.applyRoutePreset(store.processingRoutes.current, second.id).getOrThrow()
        store.chooseRouteHeadphones(key, "Open back").getOrThrow()
        assertNull(store.applyRoutePreset(store.processingRoutes.current, second.id).getOrThrow())
        store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow()
        assertEquals(-5f, store.audioPrefs.first().dspPreampDb)
        store.chooseRouteHeadphones(key, "").getOrThrow()
        val before = store.exportPrefs()
        assertNull(store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow())
        assertEquals(before, store.exportPrefs())
        val saved = ProcessingRouteCodec.decode(ProcessingRouteCodec.encode(store.processingRouteRules.first())).getOrThrow()
        assertEquals("", saved.headphones[key])
    }

    @Test fun controllerPreservesManualSoundOnReconnectAndUnboundRoutes() = fixture { first, second ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            AutoEqController(context, store, scope)
            store.bindCurrentRoute(first.id, "").getOrThrow()
            withTimeout(5000) { store.audioPrefs.first { it.dspPreampDb == -5f } }
            store.applyProcessingPreset(second.id).getOrThrow()
            store.processingRoutes.publish(ProcessingRoute())
            store.processingRoutes.publish(route)
            delay(300)
            assertEquals(-9f, store.audioPrefs.first().dspPreampDb)
            store.processingRoutes.publish(route.copy(key = otherKey))
            delay(300)
            assertEquals(-9f, store.audioPrefs.first().dspPreampDb)
            store.processingRoutes.publish(route)
            store.holdCurrentRoute(false).getOrThrow()
            withTimeout(5000) { store.audioPrefs.first { it.dspPreampDb == -5f } }
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun malformedRulesRejectBackupWithoutChangingPreferences() = fixture { _, _ ->
        val before = store.exportPrefs()
        assertTrue(store.restoreBackupPrefs(before.copy(strings = before.strings +
            (ProcessingRouteCodec.PREFERENCE_KEY to "{broken"))).isFailure)
        assertEquals(before, store.exportPrefs())
        assertTrue(runCatching { BackupArchive.decodeJson(com.google.gson.Gson().toJson(AuroraBackup(prefs =
            before.copy(strings = before.strings + (ProcessingRouteCodec.PREFERENCE_KEY to "{broken"))))) }.isFailure)
    }

    @Test fun manualRackAndGainEditsSurviveReconnect() = fixture { first, _ ->
        store.bindCurrentRoute(first.id, "").getOrThrow()
        store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow()
        val manualRack = first.rack.copy(name = "Manual rack", enabled = true)
        store.setProcessingRack(manualRack).getOrThrow()
        assertTrue(key in store.processingRouteRules.first().manual)
        store.processingRoutes.publish(ProcessingRoute())
        store.processingRoutes.publish(route)
        assertNull(store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow())
        assertEquals(manualRack, store.processingSettings.first().rack)
        store.holdCurrentRoute(false).getOrThrow()
        store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow()
        store.setDspPreamp(-12f)
        assertTrue(key in store.processingRouteRules.first().manual)
        store.processingRoutes.publish(ProcessingRoute())
        store.processingRoutes.publish(route)
        assertNull(store.applyRoutePreset(store.processingRoutes.current, first.id).getOrThrow())
        assertEquals(-12f, store.audioPrefs.first().dspPreampDb)
    }

    @Test fun legacyBindingsUseConfirmedRoutesAndKeepManualEdits() = fixture { _, _ ->
        val binding = EqBinding(route.legacyKey!!, "Fixture", "Legacy", -3f, listOf(ParamBand(1000f, 1f, 1f)))
        store.upsertEqBinding(binding)
        store.setAutoEqAutoSwitch(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            AutoEqController(context, store, scope)
            withTimeout(5000) { store.audioPrefs.first { it.dspPreampDb == -3f } }
            store.setDspPreamp(-11f)
            store.processingRoutes.publish(ProcessingRoute())
            store.processingRoutes.publish(route)
            delay(300)
            assertEquals(-11f, store.audioPrefs.first().dspPreampDb)
            assertTrue(key in store.processingRouteRules.first().manual)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    private fun preset(name: String, gain: Float): ProcessingPreset {
        val audio = AudioPrefs(dspMode = DspMode.CUSTOM, dspPreampDb = gain, dspWidth = 0.8f)
        return ProcessingPreset(UUID.randomUUID().toString(), name, createdAtMs = 1, audio = audio,
            playback = ProcessingPlaybackPrefs(monoAudio = true), rack = ProcessingRack.legacy(audio, true))
    }
}
