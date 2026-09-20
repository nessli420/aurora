package com.aurora.music.extensions

import com.aurora.music.data.BandType
import com.aurora.music.data.RackNodeKind
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ExtensionCodecTest {
    @Test fun protocolNumbersRequireFiniteExactIntegers() {
        val objectValue = JsonObject().apply { addProperty("count", 100) }
        assertEquals(100, ExtensionCodec.integer(objectValue, "count", 0, 100))
        for (value in listOf("1.5", "-1", "101", "1e99", "\"5\"", "null", "true")) {
            val valueObject = JsonParser.parseString("{\"count\":$value}").asJsonObject
            assertThrows(IllegalArgumentException::class.java) { ExtensionCodec.integer(valueObject, "count", 0, 100) }
        }
        val invalid = JsonObject().apply { addProperty("count", Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionCodec.number(invalid, "count", -1.0, 1.0) }
    }

    @Test fun malformedPersistedGrantsAreRejectedWithoutDefaultsOrEscalatedCapabilities() {
        val grant = ExtensionGrant("com.example.plugin/com.example.plugin.Service", "a".repeat(64), setOf("audio"), true,
            mapOf("gainDb" to -6.0))
        val valid = ExtensionCodec.encode(listOf(grant))
        assertEquals(listOf(grant), ExtensionCodec.decode(valid))
        val root = JsonParser.parseString(valid).asJsonObject
        val malformed = mutableListOf<String>()
        malformed += valid.replace("\"enabled\":true", "\"enabled\":\"true\"")
        malformed += valid.replace("\"audio\"", "\"filesystem\"")
        malformed += valid.replace("\"settings\":{\"gainDb\":-6.0}", "\"settings\":null")
        malformed += valid.replace("a".repeat(64), "broken")
        malformed += root.deepCopy().apply { getAsJsonArray("grants").add(getAsJsonArray("grants")[0].deepCopy()) }.toString()
        for (text in malformed) assertThrows(IllegalArgumentException::class.java) { ExtensionCodec.decode(text) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionCodec.objectValue("{\"api\":1,\"api\":2}") }
        assertThrows(IllegalArgumentException::class.java) { ExtensionCodec.objectValue("{\"x\":\"${"a".repeat(ExtensionCodec.MAX_BYTES)}\"}") }
    }

    @Test fun manifestRejectsUnknownApiDuplicateCapabilitiesAndInvalidSettings() {
        val manifest = JsonParser.parseString("""{"api":1,"name":"Test","version":"1.0","license":"Apache-2.0","capabilities":["audio"],"settings":[{"id":"gainDb","label":"Gain","minimum":-24,"maximum":0,"default":-6}]}""").asJsonObject
        assertEquals(-6.0, ExtensionCodec.manifest(manifest).settings.single().default, 0.0)
        for (changed in listOf(
            manifest.deepCopy().apply { addProperty("api", 2) },
            manifest.deepCopy().apply { getAsJsonArray("capabilities").add("audio") },
            manifest.deepCopy().apply { getAsJsonArray("settings")[0].asJsonObject.addProperty("default", 10) },
            manifest.deepCopy().apply { getAsJsonArray("settings").add(getAsJsonArray("settings")[0].deepCopy()) },
        )) assertThrows(IllegalArgumentException::class.java) { ExtensionCodec.manifest(changed) }
    }

    @Test fun boundedDeclarativeAudioCompilesToOwnedRackNodesAndRejectsCodeOrInvalidFilters() {
        val bands = JsonArray().apply { repeat(64) { add(JsonParser.parseString("""{"frequency":1000,"gainDb":0,"q":0.7,"shape":"peak"}""")) } }
        val equalizer = JsonObject().apply { addProperty("type", "equalizer"); add("bands", bands) }
        val stages = JsonArray().apply {
            add(JsonParser.parseString("""{"type":"gain","gainDb":-6}"""))
            add(equalizer)
            add(JsonParser.parseString("""{"type":"stereo","width":0.5,"balance":0}"""))
            add(JsonParser.parseString("""{"type":"crossfeed","amount":0.2}"""))
        }
        val rack = ExtensionCodec.compileAudio(stages, "Extension test")
        assertEquals(listOf(RackNodeKind.GAIN, RackNodeKind.EQ, RackNodeKind.STEREO, RackNodeKind.CROSSFEED), rack.nodes.map { it.kind })
        assertTrue(rack.enabled && rack.autoHeadroom)
        assertEquals(64, rack.nodes[1].audio.dspParametric.size)
        assertEquals(BandType.PEAK, rack.nodes[1].audio.dspParametric.first().type)
        equalizer.getAsJsonArray("bands")[0].asJsonObject.addProperty("gainDb", 12)
        assertEquals(0f, rack.nodes[1].audio.dspParametric.first().gainDb, 0f)
        for (payload in listOf(
            """[{"type":"native","library":"/data/plugin.so"}]""",
            """[{"type":"gain","gainDb":-6,"code":"run()"}]""",
            """[{"type":"gain","gainDb":100}]""",
            """[{"type":"equalizer","bands":[{"frequency":1000,"gainDb":0,"q":0.7,"shape":"script"}]}]""",
            """[{"type":"equalizer","bands":[]}]""",
        )) assertThrows(RuntimeException::class.java) { ExtensionCodec.compileAudio(JsonParser.parseString(payload), "Invalid") }
    }
}
