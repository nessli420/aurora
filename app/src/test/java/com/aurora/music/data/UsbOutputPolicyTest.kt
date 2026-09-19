package com.aurora.music.data

import com.aurora.music.data.rules.PresetRuleSession
import com.aurora.music.data.rules.PresetRuleSessionCodec
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UsbOutputPolicyTest {
    @Test fun legacyPreferencesDefaultToDirectAndPauseInsteadOfSpeakerFallback() {
        val backup = BackupArchive.decodeJson(Gson().toJson(AuroraBackup(prefs = PrefsBackup())))
        assertEquals(UsbOutputMode.DIRECT, UsbOutputPolicy.decodeMode(backup.prefs.strings[UsbOutputPolicy.MODE_KEY]).getOrThrow())
        assertEquals(UsbFallbackPolicy.PAUSE, UsbOutputPolicy.decodeFallback(backup.prefs.strings[UsbOutputPolicy.FALLBACK_KEY]).getOrThrow())
    }

    @Test fun portableBackupRetainsBothPoliciesAndRejectsUnknownOrWrongTypes() {
        val preferences = PrefsBackup(strings = mapOf(UsbOutputPolicy.MODE_KEY to "PROCESSED", UsbOutputPolicy.FALLBACK_KEY to "ANDROID"))
        assertEquals(preferences, BackupArchive.decodeJson(Gson().toJson(AuroraBackup(prefs = preferences))).prefs)
        for (key in listOf(UsbOutputPolicy.MODE_KEY, UsbOutputPolicy.FALLBACK_KEY)) {
            for (value in listOf("", "unknown", "direct", " PAUSE")) {
                assertTrue(runCatching { BackupArchive.decodeJson(Gson().toJson(AuroraBackup(
                    prefs = PrefsBackup(strings = mapOf(key to value))))) }.isFailure)
            }
            assertTrue(runCatching { BackupArchive.decodeJson(Gson().toJson(AuroraBackup(
                prefs = PrefsBackup(ints = mapOf(key to 1))))) }.isFailure)
        }
    }

    @Test fun legacyRuleRecoveryRetainsBaselineAndAppliedSoundWithSafeUsbDefaults() {
        val baseline = ProcessingPreset(UUID.randomUUID().toString(), "Baseline", createdAtMs = 1,
            audio = AudioPrefs(dspPreampDb = -12f), playback = ProcessingPlaybackPrefs(bitPerfectUsb = true))
        val applied = baseline.copy(id = UUID.randomUUID().toString(), name = "Rule", audio = AudioPrefs(dspPreampDb = -8f))
        val session = PresetRuleSession(baseline, applied, UUID.randomUUID().toString(), applied.id)
        val old = JsonParser.parseString(PresetRuleSessionCodec.encode(session)).asJsonObject
        for (key in listOf("baseline", "applied")) old.getAsJsonArray(key)[0].asJsonObject.apply {
            addProperty("schemaVersion", 4)
            getAsJsonObject("playback").apply { remove("usbOutputMode"); remove("usbFallbackPolicy") }
        }
        val recovered = PresetRuleSessionCodec.decode(old.toString()).getOrThrow()
        assertEquals(session, recovered)
        assertEquals(session, PresetRuleSessionCodec.decode(PresetRuleSessionCodec.encode(recovered!!)).getOrThrow())
    }

    @Test fun capabilitiesDoNotTurnAnUnknownRouteOrMixerPreferenceIntoHardwareProof() {
        assertEquals(AndroidOutputCapabilities().describe(), AndroidOutputCapabilities(reportedRates = listOf(192000),
            mixer = AndroidMixerCapability.BIT_PERFECT).describe())
        val reported = AndroidOutputCapabilities("USB audio", listOf(96000, 44100, 96000), AndroidMixerCapability.BIT_PERFECT).describe()
        assertTrue(reported.contains("Reported rates: 44.1 / 96 kHz"))
        assertTrue(reported.contains("preference available"))
        assertTrue(reported.contains("DAC format is not verified"))
        assertFalse(reported.contains("Active rate"))
    }

    @Test fun emptyRateCapabilitiesRemainDistinctFromMissingCapabilities() {
        assertTrue(AndroidOutputCapabilities("Headphones", emptyList()).describe().contains("No rate restriction reported"))
        assertTrue(AndroidOutputCapabilities("Headphones").describe().contains("Rates not reported"))
    }
}
