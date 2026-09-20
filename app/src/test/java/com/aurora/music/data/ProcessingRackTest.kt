package com.aurora.music.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProcessingRackTest {
    private fun node(kind: RackNodeKind = RackNodeKind.GAIN, audio: AudioPrefs = AudioPrefs()) =
        ProcessingRackNode(UUID.randomUUID().toString(), kind.name, kind, audio = audio)
    private fun fixture() = ProcessingRack(enabled = true, name = "Desk speakers", nodes = RackNodeKind.entries.take(ProcessingRackCodec.MAX_NODES).map { kind ->
        node(kind, AudioPrefs(dspMode = DspMode.CUSTOM, dspPreampDb = -4f, dspGraphicBands = listOf(1f, -2f),
            dspParametric = listOf(ParamBand(100f, -3f, 0.7f, 1)), dspWidth = 1.2f)).copy(wet = 0.75f)
    })
    private fun changed(change: (JsonObject) -> Unit): String = JsonParser.parseString(ProcessingRackCodec.encode(fixture()))
        .asJsonObject.apply(change).toString()

    @Test fun graphRoundTripPreservesOrderIdsNamesBypassWetAndFullPayloads() {
        val graph = fixture()
        assertEquals(graph, ProcessingRackCodec.decode(ProcessingRackCodec.encode(graph)).getOrThrow())
        val reversed = graph.copy(nodes = graph.nodes.reversed().mapIndexed { index, n -> n.copy(bypass = index % 2 == 0) })
        assertEquals(reversed, ProcessingRackCodec.decode(ProcessingRackCodec.encode(reversed)).getOrThrow())
    }

    @Test fun versionOneUpgradesWithoutChangingPlaybackAndVersionTwoPreservesChannels() {
        val original = fixture().let { it.copy(nodes = it.nodes.filter { node -> node.kind.ordinal < 10 }) }
        val old = JsonParser.parseString(ProcessingRackCodec.encode(original)).asJsonObject.apply {
            addProperty("schemaVersion", 1)
            remove("autoHeadroom")
            getAsJsonArray("nodes").forEach { it.asJsonObject.remove("eqChannel") }
        }
        assertEquals(original, ProcessingRackCodec.decode(old.toString()).getOrThrow())
        for (channel in RackEqChannel.entries) {
            val rack = ProcessingRack(nodes = listOf(node(RackNodeKind.EQ).copy(eqChannel = channel)))
            assertEquals(rack, ProcessingRackCodec.decode(ProcessingRackCodec.encode(rack)).getOrThrow())
        }
        assertTrue(runCatching { ProcessingRackCodec.encode(ProcessingRack(nodes = listOf(node().copy(eqChannel = RackEqChannel.LEFT)))) }.isFailure)
        assertTrue(ProcessingRackCodec.decode(changed { it.getAsJsonArray("nodes")[0].asJsonObject.addProperty("eqChannel", "MID") }).isFailure)
        old.getAsJsonArray("nodes")[0].asJsonObject.addProperty("eqChannel", "LEFT")
        assertTrue(ProcessingRackCodec.decode(old.toString()).isFailure)
    }

    @Test fun nullableEnvelopeNeverDefaultsMissingOrNullFields() {
        listOf("schemaVersion", "enabled", "name", "nodes", "autoHeadroom").forEach { key ->
            assertTrue(key, ProcessingRackCodec.decode(changed { it.remove(key) }).isFailure)
            assertTrue(key, ProcessingRackCodec.decode(changed { it.add(key, null) }).isFailure)
        }
        listOf("id", "name", "kind", "bypass", "wet", "audio", "eqChannel").forEach { key ->
            assertTrue(key, ProcessingRackCodec.decode(changed { it.getAsJsonArray("nodes")[0].asJsonObject.remove(key) }).isFailure)
        }
        val fields = JsonParser.parseString(ProcessingRackCodec.encode(fixture())).asJsonObject
            .getAsJsonArray("nodes")[0].asJsonObject.getAsJsonObject("audio").keySet().toList()
        fields.forEach { key ->
            assertTrue(key, ProcessingRackCodec.decode(changed { it.getAsJsonArray("nodes")[0].asJsonObject.getAsJsonObject("audio").remove(key) }).isFailure)
        }
    }

    @Test fun duplicateFieldsIdentifiersAndUnknownSchemaOrKindsAreRejected() {
        val graph = fixture()
        val encoded = ProcessingRackCodec.encode(graph)
        assertTrue(ProcessingRackCodec.decode(encoded.replace("\"enabled\":true", "\"enabled\":false,\"enabled\":true")).isFailure)
        assertTrue(ProcessingRackCodec.decode(changed { it.addProperty("extra", true) }).isFailure)
        assertTrue(ProcessingRackCodec.decode(changed { it.addProperty("schemaVersion", 5) }).isFailure)
        assertTrue(ProcessingRackCodec.decode(changed { it.addProperty("schemaVersion", 1.5) }).isFailure)
        assertTrue(ProcessingRackCodec.decode(changed { it.getAsJsonArray("nodes")[0].asJsonObject.addProperty("kind", "UNKNOWN") }).isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(graph.copy(nodes = listOf(graph.nodes[0], graph.nodes[0]))) }.isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(graph.copy(nodes = listOf(node().copy(id = "short-id")))) }.isFailure)
    }

    @Test fun nodeCountConvolutionCountAndParametricBudgetsAreEnforcedBeforePlayback() {
        assertEquals(16, ProcessingRackCodec.validate(ProcessingRack(nodes = List(16) { node() })).nodes.size)
        assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = List(17) { node() })) }.isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = List(5) { node(RackNodeKind.CONVOLUTION) })) }.isFailure)
        val band = ParamBand(1_000f, 1f, 1f)
        val eq = node(RackNodeKind.EQ, AudioPrefs(dspParametric = List(64) { band }))
        val graph = ProcessingRack(nodes = List(4) { eq.copy(id = UUID.randomUUID().toString()) })
        assertEquals(4, ProcessingRackCodec.validate(graph).nodes.size)
        assertTrue(runCatching { ProcessingRackCodec.validate(graph.copy(nodes = graph.nodes +
            node(RackNodeKind.EQ, AudioPrefs(dspParametric = listOf(band))))) }.isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(
            node(RackNodeKind.LEGACY_DSP, AudioPrefs(dspParametric = List(13) { band }))))) }.isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(
            node(RackNodeKind.EQ, AudioPrefs(dspGraphicBands = List(32) { 0f }))))) }.isFailure)
    }

    @Test fun invalidNumbersAndNodeLocalAssetPathsAreRejected() {
        for (wet in listOf(-0.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(node().copy(wet = wet)))) }.isFailure)
        }
        assertTrue(ProcessingRackCodec.decode(changed { it.getAsJsonArray("nodes")[0].asJsonObject.addProperty("wet", "NaN") }).isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(
            node(audio = AudioPrefs(dspConvIrPath = "/private/room.wav"))))) }.isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(
            node(audio = AudioPrefs(dspPreampDb = Float.NaN))))) }.isFailure)
    }

    @Test fun validatingAControlSnapshotDetachesMutableCallerLists() {
        val gains = mutableListOf(1f)
        val nodes = mutableListOf(node(RackNodeKind.EQ, AudioPrefs(dspGraphicBands = gains)))
        val frozen = ProcessingRackCodec.validate(ProcessingRack(nodes = nodes))
        gains[0] = 12f; nodes.clear()
        assertEquals(listOf(1f), frozen.nodes.single().audio.dspGraphicBands)
    }

    @Test fun legacyMigrationPreservesEffectiveModeMonoAndConvolutionOrder() {
        val audio = AudioPrefs(dspMode = DspMode.CUSTOM, dspWidth = 1.6f, dspPreampDb = -7f, dspConvEnabled = true,
            dspConvIrPath = "/private/room.wav", dspConvMakeupDb = -2f,
            dspParametric = List(20) { ParamBand(100f + it, 1f, 1f) }, dspGraphicBands = List(40) { 1f })
        val graph = ProcessingRack.legacy(audio, mono = true)
        assertFalse(graph.enabled)
        assertEquals(listOf(RackNodeKind.LEGACY_DSP, RackNodeKind.CONVOLUTION), graph.nodes.map { it.kind })
        assertFalse(graph.nodes[0].bypass); assertFalse(graph.nodes[1].bypass)
        assertEquals(0f, graph.nodes[0].audio.dspWidth, 0f)
        assertEquals(-7f, graph.nodes[0].audio.dspPreampDb, 0f)
        assertEquals(12, graph.nodes[0].audio.dspParametric.size)
        assertEquals(31, graph.nodes[0].audio.dspGraphicBands.size)
        assertTrue(graph.nodes.all { it.audio.dspConvIrPath.isEmpty() })
        for (mode in listOf(DspMode.OFF, DspMode.SYSTEM)) {
            val off = ProcessingRack.legacy(audio.copy(dspMode = mode, dspConvEnabled = false), mono = true)
            assertTrue(off.nodes.first().bypass)
            assertEquals(RackNodeKind.STEREO, off.nodes[1].kind)
            assertEquals(0f, off.nodes[1].audio.dspWidth, 0f)
            assertTrue(off.nodes.last().bypass)
            ProcessingRackCodec.validate(off)
        }
    }

    @Test fun recommendedGraphHasOneSharedConvolverAndAnActiveFinalLimiter() {
        val graph = ProcessingRack.recommended(AudioPrefs(dspMode = DspMode.CUSTOM, dspLimiterEnabled = false,
            dspPreampDb = -3f, dspConvEnabled = true, dspConvMakeupDb = 2f), mono = true)
        ProcessingRackCodec.validate(graph)
        assertFalse(graph.enabled)
        assertEquals(RackNodeKind.LIMITER, graph.nodes.last().kind)
        assertFalse(graph.nodes.last().bypass)
        assertEquals(1, graph.nodes.count { it.kind == RackNodeKind.CONVOLUTION })
        assertEquals(-3f, graph.nodes.first().audio.dspPreampDb, 0f)
        assertFalse(graph.requiresImpulseResponse())
        assertTrue(graph.copy(enabled = true).requiresImpulseResponse())
    }

    @Test fun versionOnePresetMigratesToDisabledLegacyGraphWithoutChangingGlobalFields() {
        val original = ProcessingPreset(UUID.randomUUID().toString(), "Old room", createdAtMs = 123,
            audio = AudioPrefs(dspMode = DspMode.OFF, dspPreampDb = -6f), playback = ProcessingPlaybackPrefs(monoAudio = true))
        val array = JsonParser.parseString(ProcessingPresetCodec.encode(listOf(original))).asJsonArray
        array[0].asJsonObject.apply {
            addProperty("schemaVersion", 1); remove("rack"); remove("rackImpulseAssets")
            getAsJsonObject("playback").apply { remove("outputRatePolicy"); remove("usbOutputMode"); remove("usbFallbackPolicy") }
        }
        val migrated = ProcessingPresetCodec.decode(array.toString()).presets.single()
        assertEquals(ProcessingPresetCodec.SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(original.audio, migrated.audio)
        assertEquals(original.playback, migrated.playback)
        assertEquals(ProcessingRack.legacy(original.audio, true), migrated.rack)
        assertEquals(migrated, ProcessingPresetCodec.decode(ProcessingPresetCodec.encode(listOf(migrated))).presets.single())
    }

    @Test fun presetGraphIsStrictAndUnknownEnvelopeFieldsDoNotSilentlyDisappear() {
        val original = ProcessingPreset(UUID.randomUUID().toString(), "Graph", createdAtMs = 1,
            audio = AudioPrefs(), playback = ProcessingPlaybackPrefs(), rack = fixture())
        assertEquals(original, ProcessingPresetCodec.decode(ProcessingPresetCodec.encode(listOf(original))).presets.single())
        val json = JsonParser.parseString(ProcessingPresetCodec.encode(listOf(original))).asJsonArray
        json[0].asJsonObject.addProperty("unknown", "unsupported")
        assertNotNull(ProcessingPresetCodec.decode(json.toString()).error)
    }
}
