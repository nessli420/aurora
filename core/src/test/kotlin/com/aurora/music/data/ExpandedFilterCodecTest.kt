package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ExpandedFilterCodecTest {
    private fun band(type: FilterType, order: Int = type.orders.first()) =
        ParamBand(1000f, if (type.hasGain) -3f else 0f, .707f, type.code, order = order,
            coefficients = if (type == FilterType.CUSTOM_BIQUAD) listOf(.5, .25, 0.0, -.2, .1) else null)
    private fun node(bands: List<ParamBand>) = ProcessingRackNode(UUID.randomUUID().toString(), "EQ", RackNodeKind.EQ,
        audio = AudioPrefs(dspGraphicBands = emptyList(), dspParametric = bands))

    @Test fun legacyIntegerBandsAndSemicolonPreferencesMigrateWithoutChangingTheirMeaning() {
        for (type in FilterType.entries.take(3)) {
            val original = band(type)
            val old = Gson().toJsonTree(original).asJsonObject.apply { remove("filterId"); remove("enabled"); remove("order") }
            assertEquals(original, ParamBandCodec.read(old))
            val text = "${original.freqHz}:${original.gainDb}:${original.q}:${original.type}"
            assertEquals(listOf(original), ParamBandCodec.decodePreference(text))
        }
        val all = FilterType.entries.map { band(it).copy(enabled = false) }
        assertEquals(all, ParamBandCodec.decodePreference(ParamBandCodec.encodePreference(all)))
    }

    @Test fun versionsOneAndTwoMigrateAndVersionThreeRetainsNewMetadata() {
        val rack = ProcessingRack(nodes = listOf(node(listOf(band(FilterType.PEAK)))))
        for (version in listOf(1, 2)) {
            val old = JsonParser.parseString(ProcessingRackCodec.encode(rack)).asJsonObject.apply {
                addProperty("schemaVersion", version); remove("autoHeadroom")
                getAsJsonArray("nodes").forEach { element ->
                    if (version == 1) element.asJsonObject.remove("eqChannel")
                    element.asJsonObject.getAsJsonObject("audio").getAsJsonArray("dspParametric").forEach {
                        it.asJsonObject.remove("filterId"); it.asJsonObject.remove("enabled"); it.asJsonObject.remove("order")
                    }
                }
            }
            assertEquals(rack, ProcessingRackCodec.decode(old.toString()).getOrThrow())
        }
        val extended = rack.copy(autoHeadroom = true, nodes = listOf(node(FilterType.entries.map { band(it).copy(enabled = false) })))
        assertEquals(extended, ProcessingRackCodec.decode(ProcessingRackCodec.encode(extended)).getOrThrow())
    }

    @Test fun allFilterFamiliesRoundTripThroughTextOrNativeJsonWithoutLosingBypassOrder() {
        val all = FilterType.entries.flatMap { type -> type.orders.map { band(type, it).copy(enabled = it % 4 != 0) } }
        val extended = ParsedEq(-6f, all)
        val encoded = RackEqTextCodec.encode(extended)
        assertTrue(encoded.contains("aurora.parametric.v1"))
        assertEquals(extended, RackEqTextCodec.parse(encoded).getOrThrow())
        val apo = extended.copy(bands = all.filter { it.type < FilterType.TILT.code })
        assertEquals(apo, RackEqTextCodec.parse(RackEqTextCodec.encode(apo)).getOrThrow())
    }

    @Test fun autoEqPeqDictionaryUsesDocumentedFilterNamesAndRejectsUnknownActiveData() {
        val source = """{"fs":48000,"filters":[{"type":"LOW_SHELF","fc":105.0,"q":0.71,"gain":-3.5},{"type":"PEAKING","fc":1000,"q":2,"gain":1}]}"""
        val parsed = RackEqTextCodec.parse(source).getOrThrow()
        assertEquals(listOf(ParamBand(105f, -3.5f, .71f, 1), ParamBand(1000f, 1f, 2f)), parsed.bands)
        assertEquals(0f, parsed.preampDb, 0f)
        assertTrue(RackEqTextCodec.parse(source.replace("LOW_SHELF", "SHELF_UNKNOWN")).isFailure)
        assertTrue(RackEqTextCodec.parse(source.replace("\"fc\":105.0", "\"fc\":105.0,\"pan\":1")).isFailure)
        assertTrue(RackEqTextCodec.parse(source.replace("\"fs\":48000", "\"fs\":48000,\"preamp\":-6")).isFailure)
    }

    @Test fun invalidStableIdsOrdersGainAndPartialMetadataAreRejected() {
        val original = band(FilterType.PEAK)
        for (invalid in listOf(original.copy(filterId = "peak.v2"), original.copy(type = 3), original.copy(order = 3),
            band(FilterType.LINKWITZ_RILEY_LOW_PASS).copy(order = 6), band(FilterType.NOTCH).copy(gainDb = 3f))) {
            assertTrue(runCatching { ParamBandCodec.validate(invalid) }.isFailure)
        }
        val json = Gson().toJsonTree(original).asJsonObject
        listOf("filterId", "enabled", "order").forEach { key ->
            assertTrue(runCatching { ParamBandCodec.read(json.deepCopy().apply { remove(key) }) }.isFailure)
            assertTrue(runCatching { ParamBandCodec.read(json.deepCopy().apply { add(key, null) }) }.isFailure)
        }
    }

    @Test fun nodeBandLimitAndTotalBandAndSectionBudgetsIncludeBypassedFilters() {
        val peak = band(FilterType.PEAK).copy(enabled = false)
        val full = ProcessingRack(nodes = List(4) { node(List(64) { peak }) })
        assertEquals(256, ProcessingRackCodec.validate(full).nodes.sumOf { it.audio.dspParametric.size })
        assertTrue(runCatching { ProcessingRackCodec.validate(full.copy(nodes = full.nodes + node(listOf(peak)))) }.isFailure)
        assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(node(List(65) { peak })))) }.isFailure)
        val cascade = band(FilterType.BUTTERWORTH_LOW_PASS, 8).copy(enabled = false)
        val maximum = ProcessingRack(nodes = List(2) { node(List(64) { cascade }) })
        ProcessingRackCodec.validate(maximum)
        assertTrue(runCatching { ProcessingRackCodec.validate(maximum.copy(nodes = maximum.nodes + node(listOf(peak)))) }.isFailure)
        val legacy = ProcessingRackNode(UUID.randomUUID().toString(), "Legacy", RackNodeKind.LEGACY_DSP)
        assertEquals(79, ProcessingRackCodec.sectionCount(legacy))
        assertTrue(runCatching { ProcessingRackCodec.validate(ProcessingRack(nodes = List(7) { legacy.copy(id = UUID.randomUUID().toString()) })) }.isFailure)
    }

    @Test fun customBiquadsRejectUnstableNonFiniteMissingAndExtraneousCoefficients() {
        val custom = band(FilterType.CUSTOM_BIQUAD)
        for (values in listOf(null, emptyList(), listOf(1.0, 0.0, 0.0, -2.0, 1.0),
            listOf(1.0, 0.0, 0.0, 0.0, -1.01), listOf(Double.NaN, 0.0, 0.0, 0.0, 0.0),
            listOf(1.0, 0.0, 0.0, 0.0, .999999999),
            listOf(1e7, 0.0, 0.0, 0.0, 0.0))) {
            assertTrue(runCatching { ParamBandCodec.validate(custom.copy(coefficients = values)) }.isFailure)
        }
        assertTrue(runCatching { ParamBandCodec.validate(band(FilterType.PEAK).copy(coefficients = custom.coefficients)) }.isFailure)
        val encoded = RackEqTextCodec.encode(ParsedEq(0f, listOf(custom)))
        assertEquals(custom, RackEqTextCodec.parse(encoded).getOrThrow().bands.single())
    }
}
