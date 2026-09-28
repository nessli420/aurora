package com.aurora.music.data.tuning

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackCodec
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackEqChannel
import com.aurora.music.data.RackNodeKind
import java.util.UUID

// storage applies this plan atomically against the current rack.
object TuningRackPlan {
    fun append(rack: ProcessingRack, project: TuningProject): ProcessingRack {
        val validated = TuningProjectCodec.validate(project)
        val fit = requireNotNull(validated.generatedFit) { "Generate a correction before applying this project." }
        require(fit.inputFingerprint == TuningProjectCodec.inputFingerprint(validated)) { "Inputs changed. Generate a new correction first." }
        val corrections = fit.channels.filter { it.bands.isNotEmpty() }
        require(corrections.isNotEmpty() || validated.config.hasChannelAlignment) { "This fit has no correction filters to apply." }
        val config = validated.config
        val nodes = buildList {
            if (fit.preampDb < 0f) add(ProcessingRackNode(UUID.randomUUID().toString(),
                "${validated.name.take(60)} · Headroom", RackNodeKind.GAIN,
                audio = AudioPrefs(dspPreampDb = fit.preampDb, dspLimiterEnabled = false)))
            if (config.leftTrimDb != 0.0 || config.rightTrimDb != 0.0) add(ProcessingRackNode(UUID.randomUUID().toString(),
                "${validated.name.take(60)} - Trim", RackNodeKind.STEREO,
                audio = AudioPrefs(dspTrimLeftDb = config.leftTrimDb.toFloat(), dspTrimRightDb = config.rightTrimDb.toFloat(), dspLimiterEnabled = false)))
            if (config.leftDelayMs != 0.0 || config.rightDelayMs != 0.0) add(ProcessingRackNode(UUID.randomUUID().toString(),
                "${validated.name.take(60)} - Delay", RackNodeKind.DELAY,
                audio = AudioPrefs(dspDelayLeftMs = config.leftDelayMs.toFloat(), dspDelayRightMs = config.rightDelayMs.toFloat(), dspLimiterEnabled = false)))
            corrections.forEach { channel ->
                val routing = when (channel.channel) {
                    TuningFitChannel.LEFT -> RackEqChannel.LEFT
                    TuningFitChannel.RIGHT -> RackEqChannel.RIGHT
                    TuningFitChannel.LINKED_AVERAGE -> RackEqChannel.BOTH
                }
                add(ProcessingRackNode(UUID.randomUUID().toString(), "${validated.name.take(60)} · ${routing.name.lowercase()}",
                    RackNodeKind.EQ, audio = AudioPrefs(dspParametric = channel.bands, dspLimiterEnabled = false), eqChannel = routing))
            }
        }
        val remainingBands = ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS - rack.nodes
            .filter { it.kind == RackNodeKind.EQ || it.kind == RackNodeKind.LEGACY_DSP }.sumOf { it.audio.dspParametric.size }
        val neededBands = corrections.sumOf { it.bands.size }
        require(neededBands <= remainingBands) { "Correction needs $neededBands bands; the rack has $remainingBands remaining. Reduce the fit budget or remove existing EQ bands." }
        val remainingNodes = ProcessingRackCodec.MAX_NODES - rack.nodes.size
        require(nodes.size <= remainingNodes) { "Correction needs ${nodes.size} stages; the rack has $remainingNodes remaining. Remove stages before applying." }
        val position = if (rack.nodes.lastOrNull()?.kind == RackNodeKind.LIMITER) rack.nodes.lastIndex else rack.nodes.size
        return ProcessingRackCodec.validate(rack.copy(nodes = rack.nodes.take(position) + nodes + rack.nodes.drop(position)))
    }
}
