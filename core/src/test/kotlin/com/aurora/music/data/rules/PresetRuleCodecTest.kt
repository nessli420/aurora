package com.aurora.music.data.rules

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingPlaybackPrefs
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PresetRuleCodecTest {
    private val rule = PresetRule(UUID.randomUUID().toString(), "Evening", UUID.randomUUID().toString(), priority = 9,
        conditions = listOf(PresetRuleCondition(RuleField.GENRE, listOf("Ambient", "Electronic")),
            PresetRuleCondition(RuleField.SAMPLE_RATE, minRateHz = 48000)))

    @Test fun emptyLegacyStateDoesNotEnableNewRules() {
        assertEquals(PresetRuleSet(), PresetRuleCodec.decode(null).getOrThrow())
        assertNull(PresetRuleSessionCodec.decode(null).getOrThrow())
    }

    @Test fun orderedRulesFlagsAndConditionsRoundTrip() {
        val set = PresetRuleSet(true, true, listOf(rule, rule.copy(id = UUID.randomUUID().toString(), match = RuleMatch.ANY)))
        assertEquals(set, PresetRuleCodec.decode(PresetRuleCodec.encode(set)).getOrThrow())
    }

    @Test fun malformedFutureDuplicateAndUnknownValuesFail() {
        val valid = PresetRuleCodec.encode(PresetRuleSet(rules = listOf(rule)))
        listOf(valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"extra\":true"),
            valid.replace("\"enabled\":false", "\"enabled\":\"false\""),
            valid.replace("\"priority\":9", "\"priority\":9.5"),
            valid.replace("\"GENRE\"", "\"LOCATION\""),
            valid.replace("\"ALL\"", "\"NOT\""),
            valid.replace("\"minRateHz\":48000", "\"minRateHz\":null"),
            valid + " {}", "null", "{broken").forEach { assertTrue(it, PresetRuleCodec.decode(it).isFailure) }
    }

    @Test fun impossibleEmptyAndExcessiveRulesFailBeforePersistence() {
        listOf(rule.copy(conditions = emptyList()), rule.copy(priority = 1001), rule.copy(name = " Bad "),
            rule.copy(conditions = listOf(PresetRuleCondition(RuleField.SAMPLE_RATE, minRateHz = 96000, maxRateHz = 48000))),
            rule.copy(conditions = listOf(PresetRuleCondition(RuleField.GENRE, listOf("")))),
            rule.copy(conditions = listOf(PresetRuleCondition(RuleField.CONTEXT, listOf("CONNECTED")))))
            .forEach { assertTrue(runCatching { PresetRuleCodec.encode(PresetRuleSet(rules = listOf(it))) }.isFailure) }
        assertTrue(runCatching { PresetRuleCodec.encode(PresetRuleSet(rules = listOf(rule, rule))) }.isFailure)
        assertTrue(runCatching { PresetRuleCodec.encode(PresetRuleSet(rules = List(65) { rule.copy(id = UUID.randomUUID().toString()) })) }.isFailure)
    }

    @Test fun recoverySnapshotsRemainDistinctAndStrict() {
        val audio = AudioPrefs(dspPreampDb = -8f)
        val baseline = ProcessingPreset(UUID.randomUUID().toString(), "Before rules", createdAtMs = 1, audio = audio,
            playback = ProcessingPlaybackPrefs(), rack = ProcessingRack.legacy(audio))
        val applied = baseline.copy(id = UUID.randomUUID().toString(), name = "Automatic", audio = audio.copy(dspPreampDb = -3f))
        val session = PresetRuleSession(baseline, applied, rule.id, rule.presetId)
        val json = PresetRuleSessionCodec.encode(session)
        assertEquals(session, PresetRuleSessionCodec.decode(json).getOrThrow())
        assertTrue(PresetRuleSessionCodec.decode(json.replace("\"version\":1", "\"version\":1,\"version\":1")).isFailure)
        assertTrue(PresetRuleSessionCodec.decode(json.replace("\"version\":1", "\"version\":2")).isFailure)
        assertTrue(PresetRuleSessionCodec.decode(json.replace("\"dspPreampDb\":-8.0", "\"dspPreampDb\":\"bad\"")).isFailure)
    }
}
