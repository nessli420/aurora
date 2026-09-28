package com.aurora.music.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProcessingPresetTest {
    private fun fixture() = ProcessingPreset(
        id = UUID.randomUUID().toString(), name = "Speakers", createdAtMs = 123456L,
        audio = AudioPrefs(
            eqEnabled = true, eqPreset = 3, eqBands = listOf(-123, 456), bassBoost = 123,
            virtualizer = 321, loudnessGain = 99, replayGain = 2, dspMode = DspMode.CUSTOM,
            dspGraphicBands = listOf(-1.25f, 2.5f),
            dspParametric = listOf(ParamBand(80f, -3.5f, 0.71f, BandType.LOW_SHELF), ParamBand(12000f, 2f, 1.4f, BandType.HIGH_SHELF)),
            dspPreampDb = -4f, dspBalance = -0.2f, dspWidth = 1.4f, dspCrossfeed = 0.3f,
            dspLimiterEnabled = false, dspLimiterCeilingDb = -1.2f, dspCompEnabled = true,
            dspCompThreshDb = -22f, dspCompRatio = 3.2f, dspConvEnabled = true,
            dspConvIrPath = "/private/ir.wav", dspConvIrName = "Room.wav", dspConvMakeupDb = -2f,
            dspGraphicLayout = 2, dspSaturation = 0.1f, dspDelayLeftMs = 3f, dspDelayRightMs = 4f,
            dspTrimLeftDb = -2f, dspTrimRightDb = -1f,
        ),
        playback = ProcessingPlaybackPrefs(true, 9, "POWER", false, false, 1.25f, true, true, true, true),
        activeEqProfile = "My room", irSha256 = "ab".repeat(32),
    )

    private fun changedJson(change: (com.google.gson.JsonObject) -> Unit): String {
        val array = JsonParser.parseString(ProcessingPresetCodec.encode(listOf(fixture()))).asJsonArray
        change(array[0].asJsonObject)
        return array.toString()
    }

    @Test fun roundTripPreservesEveryCurrentProcessingField() {
        val preset = fixture()
        val read = ProcessingPresetCodec.decode(ProcessingPresetCodec.encode(listOf(preset)))
        assertNull(read.error)
        assertEquals(listOf(preset), read.presets)
    }

    @Test fun missingEnvelopeFieldsNeverCreateGsonZeroDefaults() {
        listOf("id", "name", "schemaVersion", "createdAtMs", "audio", "playback", "activeEqProfile", "irSha256", "rack").forEach { field ->
            assertNotNull(field, ProcessingPresetCodec.decode(changedJson { it.remove(field) }).error)
        }
    }

    @Test fun missingOrNullAudioFieldsAreRejectedInsteadOfPartiallyApplied() {
        val json = JsonParser.parseString(ProcessingPresetCodec.encode(listOf(fixture()))).asJsonArray
        val fields = json[0].asJsonObject.getAsJsonObject("audio").keySet().toList()
        fields.forEach { field ->
            assertNotNull(field, ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("audio").remove(field) }).error)
            assertNotNull(field, ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("audio").add(field, null) }).error)
        }
    }

    @Test fun futureVersionsAndFractionalVersionsAreRejected() {
        assertNotNull(ProcessingPresetCodec.decode(changedJson { it.addProperty("schemaVersion", ProcessingPresetCodec.SCHEMA_VERSION + 1) }).error)
        assertNotNull(ProcessingPresetCodec.decode(changedJson { it.addProperty("schemaVersion", 1.5) }).error)
    }

    @Test fun malformedListsAndBandObjectsFailAsAWhole() {
        listOf("{broken", "null", "{}", "[null]", "[{}]").forEach { assertNotNull(ProcessingPresetCodec.decode(it).error) }
        assertNotNull(ProcessingPresetCodec.decode(changedJson {
            it.getAsJsonObject("audio").getAsJsonArray("dspParametric")[0].asJsonObject.remove("q")
        }).error)
    }

    @Test fun invalidNumbersAndWrongJsonTypesAreRejected() {
        assertNotNull(ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("audio").addProperty("dspBalance", "NaN") }).error)
        assertNotNull(ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("audio").addProperty("eqEnabled", "false") }).error)
        assertNotNull(ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("audio").addProperty("dspWidth", 20) }).error)
        assertNotNull(ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("playback").addProperty("defaultSpeed", -1) }).error)
    }

    @Test fun duplicateIdsAndUnsupportedStorageAreNotSilentlyDiscarded() {
        val p = fixture()
        assertTrue(runCatching { ProcessingPresetCodec.encode(listOf(p, p.copy(name = "Another"))) }.isFailure)
        assertNotNull(ProcessingPresetCodec.decode(changedJson { it.addProperty("id", "broken") }).error)
        assertNotNull(ProcessingPresetCodec.decode(changedJson { it.addProperty("irSha256", "bad") }).error)
    }

    @Test fun namesAreTrimmedAndBounded() {
        assertEquals("My preset", ProcessingPresetCodec.name("  My preset  "))
        listOf("", "   ", "x".repeat(81), "Bad\nname").forEach {
            assertTrue(runCatching { ProcessingPresetCodec.name(it) }.isFailure)
        }
    }

    @Test fun playbackProjectionExcludesAccountLibraryAndNetworkChoices() {
        val a = PlaybackPrefs(streamWifi = 128, streamCellular = 64, downloadBitrate = 320, scrobble = false, autoplayRadio = true)
        assertEquals(ProcessingPlaybackPrefs.from(PlaybackPrefs()), ProcessingPlaybackPrefs.from(a))
        assertNotEquals(ProcessingPlaybackPrefs.from(a), ProcessingPlaybackPrefs.from(a.copy(monoAudio = true)))
    }

    @Test fun noLibraryAndDefaultLibraryRoundTripWithoutChangingDefaults() {
        assertEquals(ProcessingPresetLibrary(), ProcessingPresetCodec.decode(null))
        val p = fixture().copy(audio = AudioPrefs(), playback = ProcessingPlaybackPrefs(), activeEqProfile = "", irSha256 = "")
        assertEquals(listOf(p), ProcessingPresetCodec.decode(ProcessingPresetCodec.encode(listOf(p))).presets)
    }

    @Test fun versionsOneTwoAndThreeMigrateOutputPolicyAndAssetList() {
        for (version in 1..3) {
            val json = changedJson {
                it.addProperty("schemaVersion", version)
                it.remove("rackImpulseAssets")
                it.getAsJsonObject("playback").remove("outputRatePolicy")
                it.getAsJsonObject("playback").remove("usbOutputMode")
                it.getAsJsonObject("playback").remove("usbFallbackPolicy")
                if (version == 1) it.remove("rack")
            }
            val decoded = ProcessingPresetCodec.decode(json)
            assertNull(decoded.error)
            assertEquals(com.aurora.music.playback.engine.OutputRatePolicy(), decoded.presets.single().playback.outputRatePolicy)
            assertTrue(decoded.presets.single().rackImpulseAssets.isEmpty())
        }
    }

    @Test fun outputPolicyIsStrictAndCapturedInPreset() {
        val policy = com.aurora.music.playback.engine.OutputRatePolicy(com.aurora.music.playback.engine.OutputRateMode.FIXED, 96_000, tpdfDither = true)
        val preset = fixture().copy(playback = fixture().playback.copy(outputRatePolicy = policy))
        assertEquals(policy, ProcessingPresetCodec.decode(ProcessingPresetCodec.encode(listOf(preset))).presets.single().playback.outputRatePolicy)
        assertNotNull(ProcessingPresetCodec.decode(changedJson {
            it.getAsJsonObject("playback").getAsJsonObject("outputRatePolicy").addProperty("mode", "unknown")
        }).error)
    }

    @Test fun oldPresetsRetainTpdfAndNewPresetsRetainNoiseShaping() {
        val policy = com.aurora.music.playback.engine.OutputRatePolicy(tpdfDither = true, noiseShaping = true)
        val preset = fixture().copy(playback = fixture().playback.copy(outputRatePolicy = policy))
        val json = ProcessingPresetCodec.encode(listOf(preset))
        assertEquals(preset, ProcessingPresetCodec.decode(json).presets.single())
        val legacy = JsonParser.parseString(json).asJsonArray.apply {
            get(0).asJsonObject.addProperty("schemaVersion", 5)
            get(0).asJsonObject.getAsJsonObject("playback").getAsJsonObject("outputRatePolicy").remove("noiseShaping")
        }
        val migrated = ProcessingPresetCodec.decode(legacy.toString())
        assertNull(migrated.error)
        assertEquals(policy.copy(noiseShaping = false), migrated.presets.single().playback.outputRatePolicy)
        legacy[0].asJsonObject.addProperty("schemaVersion", 6)
        assertNotNull(ProcessingPresetCodec.decode(legacy.toString()).error)
    }

    @Test fun versionFourPresetsMigrateUsbPolicyWithoutChangingSound() {
        val original = fixture()
        val json = JsonParser.parseString(ProcessingPresetCodec.encode(listOf(original))).asJsonArray
        json[0].asJsonObject.apply {
            addProperty("schemaVersion", 4)
            getAsJsonObject("playback").remove("usbOutputMode")
            getAsJsonObject("playback").remove("usbFallbackPolicy")
        }
        val decoded = ProcessingPresetCodec.decode(json.toString())
        assertNull(decoded.error)
        assertEquals(original, decoded.presets.single())
        assertEquals(decoded, ProcessingPresetCodec.decode(ProcessingPresetCodec.encode(decoded.presets)))
    }

    @Test fun usbPresetModesRoundTripAndMalformedModesFailBeforeGson() {
        val preset = fixture().copy(playback = fixture().playback.copy(
            usbOutputMode = UsbOutputMode.PROCESSED, usbFallbackPolicy = UsbFallbackPolicy.ANDROID))
        assertEquals(preset, ProcessingPresetCodec.decode(ProcessingPresetCodec.encode(listOf(preset))).presets.single())
        for (field in listOf("usbOutputMode", "usbFallbackPolicy")) {
            assertNotNull(ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("playback").remove(field) }).error)
            assertNotNull(ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("playback").add(field, null) }).error)
            assertNotNull(ProcessingPresetCodec.decode(changedJson { it.getAsJsonObject("playback").addProperty(field, "unknown") }).error)
        }
    }
}
