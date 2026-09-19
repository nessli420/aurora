package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.routes.*
import com.aurora.music.data.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.File
import java.io.ByteArrayInputStream
import android.content.ContextWrapper

class PresetRulesDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store by lazy { SettingsStore(context) }
    private val key = RouteIdentity.key(8, "rules-emulator-fixture", false, true)!!
    private val route = ProcessingRoute(ProcessingRouteKind.ANDROID, key, "Fixture output")
    private val playback = PresetPlaybackContext(active = true, mediaId = "fixture-one", genres = setOf("Ambient"),
        sampleRateHz = 48000, codec = "PCM", container = "wav", cast = false, androidAuto = false)

    private fun fixture(block: suspend (ProcessingPreset, ProcessingPreset, ProcessingPreset) -> Unit) = runBlocking {
        val appStore = (context.applicationContext as AuroraApplication).container.settingsStore
        val wasFrozen = appStore.presetRuleContext.current.frozen
        appStore.presetRuleContext.setFrozen(true)
        val original = store.exportPrefs()
        val baseline = preset("Baseline", -2f)
        val first = preset("Ambient", -5f)
        val second = preset("Rock", -9f)
        try {
            store.restoreBackupPrefs(original.copy(strings = (original.strings - PresetRuleSessionCodec.PREFERENCE_KEY) + mapOf(
                "processing_presets_v1" to ProcessingPresetCodec.encode(listOf(baseline, first, second)),
                PresetRuleCodec.PREFERENCE_KEY to PresetRuleCodec.encode(PresetRuleSet()),
                ProcessingRouteCodec.PREFERENCE_KEY to ProcessingRouteCodec.encode(ProcessingRouteRules()),
                "eq_bindings" to "[]"), booleans = original.booleans + ("autoeq_autoswitch" to false))).getOrThrow()
            store.processingRoutes.publish(route)
            store.presetRuleContext.publish(playback)
            store.applyProcessingPreset(baseline.id).getOrThrow()
            block(baseline, first, second)
        } finally {
            try {
                store.presetRuleContext.setFrozen(true)
                store.processingRoutes.publish(ProcessingRoute())
                store.restoreBackupPrefs(original).getOrThrow()
                assertEquals(original, store.exportPrefs())
            } finally { appStore.presetRuleContext.setFrozen(wasFrozen) }
        }
    }

    private suspend fun input(target: SettingsStore = store) = PresetRuleInput(target.presetRuleContext.current,
        target.processingRoutes.current, target.presetRules.first(), target.processingRouteRules.first())
    private suspend fun transition() = store.transitionPresetRule(input()).getOrThrow()
    private fun rule(preset: ProcessingPreset, genre: String = "Ambient", priority: Int = 0) = PresetRule(
        UUID.randomUUID().toString(), "$genre rule", preset.id, priority = priority,
        conditions = listOf(PresetRuleCondition(RuleField.GENRE, listOf(genre))))
    private suspend fun install(vararg rules: PresetRule) {
        rules.forEach { store.savePresetRule(it).getOrThrow() }
        store.setPresetRulesEnabled(true).getOrThrow()
    }

    @Test fun winnersSwitchWithoutReplacingOriginalBaselineAndRestoreWhenNoRuleMatches() = fixture { baseline, first, second ->
        install(rule(first), rule(second, "Rock", 10))
        assertEquals(RuleTransitionAction.APPLIED, transition()?.action)
        assertEquals(-5f, store.audioPrefs.first().dspPreampDb)
        store.presetRuleContext.publish(playback.copy(mediaId = "fixture-two", genres = setOf("Rock")))
        assertEquals(RuleTransitionAction.APPLIED, transition()?.action)
        assertEquals(-9f, store.audioPrefs.first().dspPreampDb)
        val saved = PresetRuleSessionCodec.decode(store.exportPrefs().strings[PresetRuleSessionCodec.PREFERENCE_KEY]).getOrThrow()!!
        assertEquals(baseline.audio, saved.baseline.audio)
        store.presetRuleContext.publish(playback.copy(mediaId = "fixture-three", genres = setOf("Jazz")))
        assertEquals(RuleTransitionAction.RESTORED, transition()?.action)
        assertEquals(baseline.audio, store.audioPrefs.first())
        assertFalse(store.exportPrefs().strings.containsKey(PresetRuleSessionCodec.PREFERENCE_KEY))
    }

    @Test fun manualEditSurvivesContextChangesAndBecomesNextBaseline() = fixture { _, first, _ ->
        install(rule(first))
        transition()
        store.setDspPreamp(-14f)
        assertTrue(store.presetRules.first().manualHold)
        assertFalse(store.exportPrefs().strings.containsKey(PresetRuleSessionCodec.PREFERENCE_KEY))
        store.presetRuleContext.publish(playback.copy(mediaId = "fixture-two"))
        transition()
        assertEquals(-14f, store.audioPrefs.first().dspPreampDb)
        store.setPresetRuleManualHold(false).getOrThrow()
        transition()
        assertEquals(-5f, store.audioPrefs.first().dspPreampDb)
        store.presetRuleContext.publish(playback.copy(genres = setOf("Jazz")))
        transition()
        assertEquals(-14f, store.audioPrefs.first().dspPreampDb)
    }

    @Test fun manualReapplyAfterDisablingRulesDiscardsRecovery() = manualReapplyAfterStoppingRules(false)

    @Test fun manualReapplyAfterRemovingEveryRuleDiscardsRecovery() = manualReapplyAfterStoppingRules(true)

    private fun manualReapplyAfterStoppingRules(remove: Boolean) = fixture { _, first, _ ->
        val selected = rule(first)
        install(selected)
        transition()
        store.presetRuleContext.setFrozen(true)
        if (remove) store.removePresetRule(selected.id).getOrThrow()
        else store.setPresetRulesEnabled(false).getOrThrow()
        assertNotNull(store.exportPrefs().strings[PresetRuleSessionCodec.PREFERENCE_KEY])
        store.applyProcessingPreset(first.id).getOrThrow()
        assertNull(store.exportPrefs().strings[PresetRuleSessionCodec.PREFERENCE_KEY])
        assertTrue(store.presetRules.first().manualHold)
        store.presetRuleContext.setFrozen(false)
        assertNull(transition())
        assertEquals(first.audio, store.audioPrefs.first())
    }

    @Test fun staleContextAndRulesCannotWritePreferences() = fixture { _, first, _ ->
        val rule = rule(first)
        install(rule)
        val old = input()
        store.presetRuleContext.publish(playback.copy(mediaId = "fixture-two", genres = setOf("Jazz")))
        val before = store.exportPrefs()
        assertNull(store.transitionPresetRule(old).getOrThrow())
        assertEquals(before, store.exportPrefs())
        val priorRules = input()
        store.savePresetRule(rule.copy(enabled = false)).getOrThrow()
        val disabled = store.exportPrefs()
        assertNull(store.transitionPresetRule(priorRules).getOrThrow())
        assertEquals(disabled, store.exportPrefs())
    }

    @Test fun frozenComparisonBlocksRulesRestoreOutputBindingsAndLegacyAutoEq() = fixture { _, first, second ->
        install(rule(first))
        transition()
        store.presetRuleContext.setFrozen(true)
        store.presetRuleContext.publish(playback.copy(genres = setOf("Jazz")))
        val frozen = store.exportPrefs()
        assertNull(transition())
        assertEquals(frozen, store.exportPrefs())
        store.bindCurrentRoute(second.id, "").getOrThrow()
        assertNull(store.applyRoutePreset(store.processingRoutes.current, second.id).getOrThrow())
        val legacy = EqBinding(key, "Fixture", "Legacy", -1f, listOf(ParamBand(1000f, 1f, 1f)))
        store.upsertEqBinding(legacy); store.setAutoEqAutoSwitch(true)
        assertFalse(store.applyEqBindingIfEnabled(legacy, store.processingRoutes.current))
        assertEquals(-5f, store.audioPrefs.first().dspPreampDb)
        store.presetRuleContext.setFrozen(false)
        assertEquals(RuleTransitionAction.RESTORED, transition()?.action)
        assertEquals(-2f, store.audioPrefs.first().dspPreampDb)
    }

    @Test fun recoverySessionRestoresAfterStoreRecreation() = fixture { baseline, first, _ ->
        install(rule(first))
        transition()
        val recreated = SettingsStore(context)
        recreated.processingRoutes.publish(route)
        recreated.presetRuleContext.publish(playback.copy(genres = setOf("Jazz")))
        assertEquals(RuleTransitionAction.RESTORED, recreated.transitionPresetRule(input(recreated)).getOrThrow()?.action)
        assertEquals(baseline.audio, recreated.audioPrefs.first())
    }

    @Test fun legacyJsonKeepsTheOriginalRuleRecoveryBaseline() = fixture { baseline, first, _ ->
        install(rule(first))
        transition()
        val root = File(context.cacheDir, "rule-backup-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try {
            val manager = BackupManager(store, LocalStore(isolated), PlayHistoryStore(isolated), isolated)
            val json = manager.export(42)
            manager.importArchive(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))).getOrThrow()
            val recovered = PresetRuleSessionCodec.decode(store.exportPrefs().strings[PresetRuleSessionCodec.PREFERENCE_KEY]).getOrThrow()!!
            assertEquals(baseline.audio, recovered.baseline.audio)
            assertEquals(first.audio, recovered.applied.audio)
            store.presetRuleContext.publish(playback.copy(genres = setOf("Jazz")))
            assertEquals(RuleTransitionAction.RESTORED, transition()?.action)
            assertEquals(baseline.audio, store.audioPrefs.first())
        } finally { root.deleteRecursively() }
    }

    @Test fun untrackedProcessingChangesArePreservedInsteadOfRestoringOverThem() = fixture { _, first, _ ->
        install(rule(first))
        transition()
        val applied = store.exportPrefs()
        store.restoreBackupPrefs(applied.copy(floats = applied.floats + ("dsp_preamp" to -17f))).getOrThrow()
        assertEquals(RuleTransitionAction.RETAINED, transition()?.action)
        assertEquals(-17f, store.audioPrefs.first().dspPreampDb)
        assertTrue(store.presetRules.first().manualHold)
        assertFalse(store.exportPrefs().strings.containsKey(PresetRuleSessionCodec.PREFERENCE_KEY))
    }

    @Test fun deletedTargetsCanBeDisabledAndPriorSoundRecovered() = fixture { baseline, first, _ ->
        install(rule(first))
        transition()
        store.deleteProcessingPreset(first.id).getOrThrow()
        assertTrue(store.transitionPresetRule(input()).isFailure)
        store.setPresetRulesEnabled(false).getOrThrow()
        assertEquals(RuleTransitionAction.RESTORED, transition()?.action)
        assertEquals(baseline.audio, store.audioPrefs.first())
    }

    @Test fun malformedRecoveryAndRulesRejectBackupAtomically() = fixture { _, _, _ ->
        val before = store.exportPrefs()
        listOf(PresetRuleCodec.PREFERENCE_KEY, PresetRuleSessionCodec.PREFERENCE_KEY).forEach { key ->
            val broken = before.copy(strings = before.strings + (key to "{broken"))
            assertTrue(store.restoreBackupPrefs(broken).isFailure)
            assertTrue(runCatching { BackupArchive.decodeJson(com.google.gson.Gson().toJson(AuroraBackup(prefs = broken))) }.isFailure)
            assertEquals(before, store.exportPrefs())
        }
    }

    @Test fun unifiedControllerRestoresBaselineAndHonorsManualOverride() = fixture { _, first, _ ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            AutoEqController(context, store, scope)
            install(rule(first))
            withTimeout(5000) { store.audioPrefs.first { it.dspPreampDb == -5f } }
            store.presetRuleContext.publish(playback.copy(genres = setOf("Jazz")))
            withTimeout(5000) { store.audioPrefs.first { it.dspPreampDb == -2f } }
            store.setDspPreamp(-13f)
            store.presetRuleContext.publish(playback)
            delay(250)
            assertEquals(-13f, store.audioPrefs.first().dspPreampDb)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    private fun preset(name: String, gain: Float): ProcessingPreset {
        val audio = AudioPrefs(dspMode = DspMode.CUSTOM, dspPreampDb = gain)
        return ProcessingPreset(UUID.randomUUID().toString(), name, createdAtMs = 1, audio = audio,
            playback = ProcessingPlaybackPrefs(), rack = ProcessingRack.legacy(audio))
    }
}
