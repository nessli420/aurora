package com.aurora.music.data.tuning

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ParamBand
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackCodec
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackEqChannel
import com.aurora.music.data.RackNodeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TuningRackPlanTest {
    private fun node(kind: RackNodeKind = RackNodeKind.GAIN, bands: List<ParamBand> = emptyList()) =
        ProcessingRackNode(UUID.randomUUID().toString(), "Existing", kind, audio = AudioPrefs(dspParametric = bands))

    @Test fun independentCorrectionUsesDistinctRoutesAndKeepsFinalLimiterLast() = runBlocking {
        val source = tuningTestProject(stereo = true)
        val project = source.copy(generatedFit = TuningFitter.fit(source))
        val input = node(); val limiter = node(RackNodeKind.LIMITER)
        val rack = ProcessingRack(enabled = false, nodes = listOf(input, limiter))
        val result = TuningRackPlan.append(rack, project)
        assertFalse(result.enabled)
        assertEquals(input, result.nodes.first())
        assertEquals(limiter, result.nodes.last())
        val added = result.nodes.subList(1, result.nodes.lastIndex)
        assertEquals(RackNodeKind.GAIN, added.first().kind)
        assertEquals(project.generatedFit!!.preampDb, added.first().audio.dspPreampDb, 0f)
        assertEquals(listOf(RackEqChannel.LEFT, RackEqChannel.RIGHT), added.filter { it.kind == RackNodeKind.EQ }.map { it.eqChannel })
        assertEquals(listOf(input, limiter), rack.nodes)
        assertEquals(result, ProcessingRackCodec.decode(ProcessingRackCodec.encode(result)).getOrThrow())
    }

    @Test fun explicitlyLinkedAverageAppliesOneBothChannelEqualizer() = runBlocking {
        val initial = tuningTestProject(stereo = true)
        val source = initial.copy(config = initial.config.copy(channelMode = TuningChannelMode.LINKED_AVERAGE))
        val project = source.copy(generatedFit = TuningFitter.fit(source))
        val result = TuningRackPlan.append(ProcessingRack(enabled = true), project)
        assertTrue(result.enabled)
        assertEquals(listOf(RackEqChannel.BOTH), result.nodes.filter { it.kind == RackNodeKind.EQ }.map { it.eqChannel })
    }

    @Test fun nodeAndBandCapacityFailuresLeaveTheOriginalGraphUnchanged() = runBlocking {
        val source = tuningTestProject(stereo = true)
        val project = source.copy(generatedFit = TuningFitter.fit(source))
        val fullNodes = ProcessingRack(nodes = List(15) { node() })
        val fullBands = ProcessingRack(nodes = List(4) { node(RackNodeKind.EQ, List(64) { ParamBand(1000f, 1f, 1f) }) })
        for (rack in listOf(fullNodes, fullBands)) {
            val before = ProcessingRackCodec.encode(rack)
            assertTrue(runCatching { TuningRackPlan.append(rack, project) }.isFailure)
            assertEquals(before, ProcessingRackCodec.encode(rack))
        }
    }

    @Test fun appendUsesTheSharedRackBudgetBeyondOneFullEqualizer() = runBlocking {
        val source = tuningTestProject(stereo = true)
        val project = source.copy(generatedFit = TuningFitter.fit(source))
        val existing = node(RackNodeKind.EQ, List(64) { ParamBand(1000f, 0f, 1f) })
        val result = TuningRackPlan.append(ProcessingRack(nodes = listOf(existing)), project)
        assertEquals(existing, result.nodes.first())
        assertTrue(result.nodes.filter { it.kind == RackNodeKind.EQ }.sumOf { it.audio.dspParametric.size } > 64)
        assertTrue(result.nodes.filter { it.kind == RackNodeKind.EQ }.all { it.audio.dspParametric.size <= 64 })
    }

    @Test fun staleOrMissingFitsAreRefusedBeforeRackMutation() = runBlocking {
        val source = tuningTestProject()
        val rack = ProcessingRack(nodes = listOf(node()))
        val before = ProcessingRackCodec.encode(rack)
        assertTrue(runCatching { TuningRackPlan.append(rack, source) }.isFailure)
        val fitted = source.copy(generatedFit = TuningFitter.fit(source))
        assertTrue(runCatching { TuningRackPlan.append(rack, fitted.copy(config = fitted.config.copy(maxBoostDb = 5.0))) }.isFailure)
        assertEquals(before, ProcessingRackCodec.encode(rack))
    }

    @Test fun flatNoCorrectionFitDoesNotAddEmptyOrGainOnlyStages() = runBlocking {
        val source = TuningProjectCodec.create("Flat").copy(measurementLeft = MeasurementTextImporter.parse("20 0\n20000 0").getOrThrow(),
            config = TuningFitConfig(normalization = TuningNormalization.NONE, smoothingOctaves = 0.0))
        val project = source.copy(generatedFit = TuningFitter.fit(source))
        assertTrue(project.generatedFit!!.channels.all { it.bands.isEmpty() })
        assertTrue(runCatching { TuningRackPlan.append(ProcessingRack(), project) }.isFailure)
    }
}
