package com.aurora.music.data

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RackAdvancedCodecTest {
    private fun node(kind: RackNodeKind) = ProcessingRackNode(UUID.randomUUID().toString(), kind.name, kind)
    @Test fun graphRoundTripsAllNewSettingsAndRejectsCyclesAndUnknownFields() {
        val utility = node(RackNodeKind.UTILITY).copy(utility = RackUtility(ll = -1.0, monoBassHz = 120.0))
        val dynamic = node(RackNodeKind.DYNAMIC_EQ).copy(inputs = listOf(RackInput(utility.id, RackChannel.MID)), dynamic = RackDynamicEq())
        val multi = node(RackNodeKind.MULTIBAND).copy(multiband = RackMultiband())
        val loudness = node(RackNodeKind.LOUDNESS).copy(loudness = RackLoudness())
        val rack = ProcessingRack(nodes = listOf(utility, dynamic, multi, loudness), output = listOf(RackInput(dynamic.id), RackInput(loudness.id)))
        val json = ProcessingRackCodec.encode(rack)
        assertEquals(rack, ProcessingRackCodec.decode(json).getOrThrow())
        assertTrue(ProcessingRackCodec.decode(json.replace("\"referenceVolume\":", "\"unsupported\":")).isFailure)
        assertThrows(IllegalArgumentException::class.java) {
            ProcessingRackCodec.validate(rack.copy(nodes = listOf(utility.copy(inputs = listOf(RackInput(loudness.id))), dynamic, multi, loudness)))
        }
        assertTrue(ProcessingRackCodec.decode(json.replace("\"ratio\":2.0", "\"ratio\":null")).isFailure)
    }
    @Test fun legacyRackSchemasMigrateToSerialWithoutNewDefaultsChangingSound() {
        val rack = ProcessingRack(nodes = listOf(node(RackNodeKind.GAIN)))
        val modern = ProcessingRackCodec.encode(rack)
        for (version in 1..3) {
            var legacy = modern.replace("\"schemaVersion\":4", "\"schemaVersion\":$version")
            if (version < 3) legacy = legacy.replace(",\"autoHeadroom\":false", "")
            if (version == 1) legacy = legacy.replace(",\"eqChannel\":\"BOTH\"", "")
            assertEquals(rack, ProcessingRackCodec.decode(legacy).getOrThrow())
        }
    }
    @Test fun dynamicsAndRoutingBudgetsCannotBeBypassedWithMalformedNumbers() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(node(RackNodeKind.MULTIBAND).copy(multiband = RackMultiband(lowHz = 1000.0, highHz = 1200.0)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(node(RackNodeKind.DYNAMIC_EQ).copy(dynamic = RackDynamicEq(dynamics = RackDynamics(attackMs = Double.NaN))))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessingRackCodec.validate(ProcessingRack(nodes = listOf(node(RackNodeKind.GAIN).copy(inputs = List(5) { RackInput() }))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessingRackCodec.validate(ProcessingRack(nodes = List(5) { node(RackNodeKind.CONVOLUTION) }))
        }
    }
    @Test fun subchainsRemainIndependentAndRemapStableNodeReferencesOnAppend() {
        val first = node(RackNodeKind.GAIN)
        val second = node(RackNodeKind.DYNAMIC_EQ).copy(inputs = listOf(RackInput(first.id)), dynamic = RackDynamicEq())
        val rack = ProcessingRack(nodes = listOf(first, second))
        val chain = RackSubchainCodec.capture(rack, rack.nodes.map { it.id }.toSet(), "Saved pair")
        assertEquals(listOf(chain), RackSubchainCodec.decode(RackSubchainCodec.encode(listOf(chain))).getOrThrow())
        val appended = RackSubchainCodec.append(rack, chain)
        assertEquals(4, appended.nodes.size)
        assertEquals(4, appended.nodes.map { it.id }.distinct().size)
        assertEquals(appended.nodes[2].id, appended.nodes[3].inputs!!.single().source)
        assertEquals(2, chain.rack.nodes.size)
    }
}
