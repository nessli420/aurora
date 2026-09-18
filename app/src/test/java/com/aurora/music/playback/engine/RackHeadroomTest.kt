package com.aurora.music.playback.engine

import com.aurora.music.data.*
import com.aurora.music.playback.ImpulseResponse
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.*

class RackHeadroomTest {
    private fun node(kind: RackNodeKind, audio: AudioPrefs = AudioPrefs()) =
        ProcessingRackNode(UUID.randomUUID().toString(), kind.name, kind, audio = audio)
    private fun rack(vararg nodes: ProcessingRackNode) = ProcessingRack(enabled = true, nodes = nodes.toList(), autoHeadroom = true)

    @Test fun unityAndCutsDoNotRaiseOrReduceInput() {
        assertEquals(0.0, RackHeadroomAnalyzer.analyze(rack(), 48_000, null).attenuationDb, 0.0)
        assertEquals(0.0, RackHeadroomAnalyzer.analyze(rack(node(RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -6f))), 96_000, null).attenuationDb, 0.0)
    }

    @Test fun combinedGainStereoCrossfeedAndFirAreIncluded() {
        val graph = rack(node(RackNodeKind.GAIN, AudioPrefs(dspPreampDb = 6f)),
            node(RackNodeKind.STEREO, AudioPrefs(dspWidth = 2f)),
            node(RackNodeKind.CROSSFEED, AudioPrefs(dspCrossfeed = 1f)), node(RackNodeKind.CONVOLUTION))
        val impulse = ImpulseResponse(floatArrayOf(1f, -1f), floatArrayOf(.5f), 48_000)
        val estimate = RackHeadroomAnalyzer.analyze(graph, 48_000, impulse)
        assertEquals(6 + 20 * log10(2.0 * 1.5 * 2), estimate.estimatedBoostDb, 1e-8)
        assertEquals(-estimate.estimatedBoostDb - 1, estimate.attenuationDb, 1e-8)
        assertTrue(estimate.firBound)
        val bypass = graph.copy(nodes = graph.nodes.map { it.copy(bypass = true) })
        assertEquals(0.0, RackHeadroomAnalyzer.analyze(bypass, 48_000, impulse).attenuationDb, 0.0)
    }

    @Test fun highQBoostCompensationMatchesProcessedToneAtBothRates() {
        for (rate in listOf(48_000, 96_000)) {
            val eq = node(RackNodeKind.EQ, AudioPrefs(dspGraphicBands = emptyList(), dspParametric = listOf(ParamBand(3000f, 12f, 40f))))
            val graph = ProductionSerialRack.compile(rack(eq), rate, null)
            assertEquals(-13.0, requireNotNull(graph.headroom).attenuationDb, .001)
            var outputPeak = 0.0
            repeat(400) { part ->
                val input = AudioBlock(graph.format, 256).apply {
                    begin(256)
                    for (i in 0 until 256) {
                        val sample = .5 * sin(2 * PI * 3000 * (part * 256 + i) / rate)
                        samples[i * 2] = sample; samples[i * 2 + 1] = sample
                    }
                }
                assertTrue(graph.queueInput(input))
                val output = requireNotNull(graph.getOutput())
                if (part > 300) outputPeak = max(outputPeak, output.samples.take(output.sampleCount).maxOf(::abs))
            }
            assertEquals(.5 * 10.0.pow(-1.0 / 20), outputPeak, 1e-6)
        }
    }

    @Test fun wetChannelSelectionAndNonlinearLimitAreExplicit() {
        val eq = node(RackNodeKind.EQ, AudioPrefs(dspGraphicBands = emptyList(), dspParametric = listOf(ParamBand(1000f, 6f, 1f))))
            .copy(eqChannel = RackEqChannel.LEFT, wet = .5f)
        val expected = 20 * log10(.5 + .5 * 10.0.pow(.3))
        assertEquals(expected, RackHeadroomAnalyzer.analyze(rack(eq), 48_000, null).estimatedBoostDb, .001)
        val sat = node(RackNodeKind.SATURATION, AudioPrefs(dspSaturation = .5f))
        assertTrue(RackHeadroomAnalyzer.analyze(rack(eq, sat), 48_000, null).nonlinear)
        assertNull(ProductionSerialRack.compile(rack(eq).copy(autoHeadroom = false), 48_000, null).headroom)
    }

    @Test fun stackedExtremeBoostsNeverYieldNanAttenuation() {
        val stages = Array(4) { node(RackNodeKind.EQ, AudioPrefs(dspGraphicBands = emptyList(),
            dspParametric = List(64) { ParamBand(1000f, 30f, 1f) })) }
        val result = RackHeadroomAnalyzer.analyze(rack(*stages), 48_000, null)
        assertTrue(result.estimatedBoostDb.isFinite())
        assertEquals(7680.0, result.estimatedBoostDb, .001)
        assertTrue(result.attenuationDb.isFinite())
        assertEquals(0.0, result.gain, 0.0)
    }
}
