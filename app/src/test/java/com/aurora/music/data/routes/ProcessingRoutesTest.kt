package com.aurora.music.data.routes

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProcessingRoutesTest {
    private val first = RouteIdentity.key(8, "AA:BB:CC", false, true)!!
    private val second = RouteIdentity.key(8, "DD:EE:FF", false, true)!!
    private val preset = UUID.randomUUID().toString()
    private fun route(key: String = first) = ProcessingRoute(ProcessingRouteKind.ANDROID, key, "Headphones", "Confirmed")

    @Test fun stableAddressesIgnoreCaseAndSeparateIdenticalModels() {
        assertEquals(first, RouteIdentity.key(8, "aa:bb:cc", false, true))
        assertNotEquals(first, second)
        assertNotEquals(first, RouteIdentity.key(26, "AA:BB:CC", false, true))
        assertEquals(RouteIdentity.key(2, "", true, true), RouteIdentity.key(2, "changed", true, true))
    }

    @Test fun ambiguousBlankAndUsbPortAddressesCannotAutoBind() {
        assertNull(RouteIdentity.key(8, "AA:BB", false, false))
        assertNull(RouteIdentity.key(8, "", false, true))
        assertNull(RouteIdentity.key(8, "02:00:00:00:00:00", false, true))
        assertNull(RouteIdentity.key(8, "00:00:00:00:00:00", false, true))
        assertNull(RouteIdentity.key(3, "", false, true))
        assertNull(RouteIdentity.key(11, "card=2;device=0", false, true))
        assertEquals(RouteIdentity.key(11, "card=2", false, true, "1:2:serial"),
            RouteIdentity.key(11, "card=9", false, true, "1:2:serial"))
        assertNotEquals(RouteIdentity.key(11, "card=2", false, true, "1:2:serial"),
            RouteIdentity.key(11, "card=2", false, true, "1:2:other"))
    }

    @Test fun passiveHeadphoneChoiceIsExactAndPersistsAcrossReconnection() {
        val other = UUID.randomUUID().toString()
        val rules = ProcessingRouteRules(true, listOf(RoutePresetBinding(first, "DAC", "Open back", preset),
            RoutePresetBinding(first, "DAC", "Closed back", other)), mapOf(first to "Closed back"))
        val restored = ProcessingRouteCodec.decode(ProcessingRouteCodec.encode(rules)).getOrThrow()
        assertEquals(other, RouteRuleDecision.preset(RouteObservation(20, route()), restored))
        assertNull(RouteRuleDecision.preset(RouteObservation(21, route()), restored.copy(headphones = emptyMap())))
        assertNull(RouteRuleDecision.preset(RouteObservation(22, route(second)), restored))
    }

    @Test fun manualHoldSurvivesCodecAndEveryRouteGeneration() {
        val rules = ProcessingRouteRules(true, listOf(RoutePresetBinding(first, "Speaker", "", preset)), manual = setOf(first))
        val restored = ProcessingRouteCodec.decode(ProcessingRouteCodec.encode(rules)).getOrThrow()
        for (generation in 1L..5L) assertNull(RouteRuleDecision.preset(RouteObservation(generation, route()), restored))
        assertEquals(preset, RouteRuleDecision.preset(RouteObservation(6, route()), restored.copy(manual = emptySet())))
    }

    @Test fun staleRoutesAndChangedRulesCannotCommitAPreset() {
        val rules = ProcessingRouteRules(true, listOf(RoutePresetBinding(first, "Speaker", "", preset)))
        val expected = RouteObservation(10, route())
        assertTrue(RouteRuleDecision.mayApply(expected, expected, rules, preset))
        assertFalse(RouteRuleDecision.mayApply(expected, expected.copy(generation = 11), rules, preset))
        assertFalse(RouteRuleDecision.mayApply(expected, expected.copy(route = route(second)), rules, preset))
        assertFalse(RouteRuleDecision.mayApply(expected, expected, rules.copy(enabled = false), preset))
        assertFalse(RouteRuleDecision.mayApply(expected, expected, rules.copy(manual = setOf(first)), preset))
        assertFalse(RouteRuleDecision.mayApply(expected, expected, rules.copy(bindings = emptyList()), preset))
    }

    @Test fun unsupportedPathsAndUnknownIdentitiesNeverSelectRules() {
        val rules = ProcessingRouteRules(true, listOf(RoutePresetBinding(first, "Output", "", preset)))
        ProcessingRouteKind.entries.filter { it != ProcessingRouteKind.ANDROID }.forEach { kind ->
            assertNull(RouteRuleDecision.preset(RouteObservation(1, route().copy(kind = kind)), rules))
        }
        assertNull(RouteRuleDecision.preset(RouteObservation(1, route().copy(key = null)), rules))
    }

    @Test fun duplicateMalformedAndUnknownRulesAreRejected() {
        val binding = RoutePresetBinding(first, "Output", "", preset)
        assertTrue(runCatching { ProcessingRouteCodec.encode(ProcessingRouteRules(bindings = listOf(binding, binding))) }.isFailure)
        val valid = ProcessingRouteCodec.encode(ProcessingRouteRules(bindings = listOf(binding)))
        listOf(valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"enabled\":false", "\"enabled\":\"false\""),
            valid.replace("\"version\":1", "\"version\":1,\"future\":true"),
            valid.replace(first, "speaker"), valid + " {}", "null").forEach {
            assertTrue(it, ProcessingRouteCodec.decode(it).isFailure)
        }
    }

    @Test fun monitorAdvancesOnlyWhenObservedRouteChanges() {
        val monitor = ProcessingRouteMonitor()
        monitor.publish(route()); val observed = monitor.current
        monitor.publish(route()); assertEquals(observed, monitor.current)
        monitor.publish(ProcessingRoute()); monitor.publish(route())
        assertTrue(monitor.current.generation > observed.generation)
        assertEquals(observed.route, monitor.current.route)
    }
}
