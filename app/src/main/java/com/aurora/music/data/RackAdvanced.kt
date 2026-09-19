package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

enum class RackChannel { STEREO, LEFT, RIGHT, MID, SIDE, ENCODE_MS, DECODE_MS }
data class RackInput(val source: String = INPUT, val channel: RackChannel = RackChannel.STEREO, val gainDb: Double = 0.0) {
    companion object { const val INPUT = "input" }
}
data class RackUtility(val ll: Double = 1.0, val lr: Double = 0.0, val rl: Double = 0.0, val rr: Double = 1.0,
    val dcBlock: Boolean = false, val monoBassHz: Double = 0.0, val delayMs: Double = 0.0)
enum class RackDetector { PEAK, RMS }
data class RackDynamics(val thresholdDb: Double = -24.0, val ratio: Double = 2.0, val attackMs: Double = 10.0,
    val releaseMs: Double = 200.0, val kneeDb: Double = 6.0, val makeupDb: Double = 0.0,
    val rangeDb: Double = 12.0, val detector: RackDetector = RackDetector.RMS,
    val solo: Boolean = false, val mute: Boolean = false)
data class RackDynamicEq(val frequencyHz: Double = 1000.0, val q: Double = 1.0,
    val detectorHz: Double = 1000.0, val detectorQ: Double = 1.0,
    val upward: Boolean = false, val dynamics: RackDynamics = RackDynamics())
data class RackMultiband(val lowHz: Double = 200.0, val highHz: Double = 2500.0,
    val bands: List<RackDynamics> = List(3) { RackDynamics() })
data class RackLoudness(val referenceVolume: Double = 0.8, val bassCapDb: Double = 8.0,
    val trebleCapDb: Double = 3.0, val strength: Double = 1.0)

object RackAdvancedCodec {
    val nodeKeys = setOf("inputs", "utility", "dynamic", "multiband", "loudness", "impulseId", "oversampling")
    private val gson = Gson()
    private fun Double.bound(min: Double, max: Double, name: String) {
        require(isFinite() && this in min..max) { "Invalid $name." }
    }
    fun validate(rack: ProcessingRack) {
        require(rack.nodes.filter { it.kind == RackNodeKind.SATURATION && !it.bypass && it.wet > 0f && (it.oversampling ?: 1) > 1 }
            .sumOf { it.oversampling ?: 1 } <= 16) { "The rack supports up to 16× combined saturation oversampling." }
        val preceding = mutableSetOf(RackInput.INPUT)
        rack.nodes.forEach { node ->
            node.oversampling?.let { require(node.kind == RackNodeKind.SATURATION && it in listOf(1, 2, 4, 8)) { "Invalid saturation oversampling." } }
            node.inputs?.let { validateInputs(it, preceding) }
            node.impulseId?.let { require(node.kind == RackNodeKind.CONVOLUTION && UUID.fromString(it).toString() == it) { "Invalid impulse reference." } }
            node.utility?.let { u ->
                require(node.kind in listOf(RackNodeKind.UTILITY, RackNodeKind.ALIGNMENT_DELAY)) { "Unexpected utility settings." }
                listOf(u.ll, u.lr, u.rl, u.rr).forEach { it.bound(-2.0, 2.0, "matrix gain") }
                u.monoBassHz.bound(0.0, 500.0, "mono bass frequency")
                require(u.monoBassHz == 0.0 || u.monoBassHz >= 20.0) { "Mono bass must be off or at least 20 Hz." }
                u.delayMs.bound(0.0, 100.0, "alignment delay")
            }
            node.dynamic?.let { d ->
                require(node.kind == RackNodeKind.DYNAMIC_EQ) { "Unexpected dynamic EQ settings." }
                d.frequencyHz.bound(20.0, 20000.0, "dynamic EQ frequency"); d.q.bound(.1, 12.0, "dynamic EQ Q")
                d.detectorHz.bound(20.0, 20000.0, "detector frequency"); d.detectorQ.bound(.1, 12.0, "detector Q")
                validateDynamics(d.dynamics)
            }
            node.multiband?.let { m ->
                require(node.kind == RackNodeKind.MULTIBAND && m.bands.size == 3) { "Multiband requires three bands." }
                m.lowHz.bound(40.0, 2000.0, "low crossover"); m.highHz.bound(400.0, 16000.0, "high crossover")
                require(m.highHz >= m.lowHz * 2) { "Crossovers must be at least one octave apart." }
                m.bands.forEach(::validateDynamics)
            }
            node.loudness?.let { l ->
                require(node.kind == RackNodeKind.LOUDNESS) { "Unexpected loudness settings." }
                l.referenceVolume.bound(.05, 1.0, "reference volume"); l.bassCapDb.bound(0.0, 12.0, "bass cap")
                l.trebleCapDb.bound(0.0, 6.0, "treble cap"); l.strength.bound(0.0, 1.0, "loudness strength")
            }
            preceding += node.id
        }
        rack.output?.let { validateInputs(it, preceding) }
    }
    private fun validateInputs(inputs: List<RackInput>, allowed: Set<String>) {
        require(inputs.size in 1..4 && inputs.map { it.source to it.channel }.distinct().size == inputs.size) { "Use one to four distinct inputs." }
        inputs.forEach { require(it.source in allowed) { "Routing must use the input or an earlier stage; cycles are not supported." }; it.gainDb.bound(-60.0, 12.0, "route gain") }
    }
    private fun validateDynamics(d: RackDynamics) {
        d.thresholdDb.bound(-80.0, 0.0, "threshold"); d.ratio.bound(1.0, 20.0, "ratio")
        d.attackMs.bound(.1, 200.0, "attack"); d.releaseMs.bound(5.0, 3000.0, "release")
        d.kneeDb.bound(0.0, 24.0, "knee"); d.makeupDb.bound(-24.0, 12.0, "makeup")
        d.rangeDb.bound(0.0, 24.0, "dynamic range")
    }
    fun readInputs(value: JsonElement): List<RackInput> {
        require(value.isJsonArray && value.asJsonArray.size() in 1..4) { "Invalid routing inputs." }
        return value.asJsonArray.map { element ->
            val o = objectFields(element, setOf("source", "channel", "gainDb"))
            RackInput(string(o, "source"), RackChannel.valueOf(string(o, "channel")), number(o, "gainDb"))
        }
    }
    fun readNode(node: ProcessingRackNode, o: JsonObject): ProcessingRackNode = node.copy(
        inputs = o.get("inputs")?.let(::readInputs),
        oversampling = if (o.has("oversampling")) number(o, "oversampling").also { require(it == it.toInt().toDouble()) }.toInt() else null,
        impulseId = if (o.has("impulseId")) string(o, "impulseId") else null,
        utility = o.get("utility")?.let { v ->
            val u = objectFields(v, setOf("ll", "lr", "rl", "rr", "dcBlock", "monoBassHz", "delayMs"))
            RackUtility(number(u,"ll"), number(u,"lr"), number(u,"rl"), number(u,"rr"), bool(u,"dcBlock"), number(u,"monoBassHz"), number(u,"delayMs"))
        },
        dynamic = o.get("dynamic")?.let { v ->
            val d = objectFields(v, setOf("frequencyHz", "q", "detectorHz", "detectorQ", "upward", "dynamics"))
            RackDynamicEq(number(d,"frequencyHz"), number(d,"q"), number(d,"detectorHz"), number(d,"detectorQ"), bool(d,"upward"), readDynamics(d.get("dynamics")))
        },
        multiband = o.get("multiband")?.let { v ->
            val m = objectFields(v, setOf("lowHz", "highHz", "bands"))
            require(m.get("bands").isJsonArray && m.getAsJsonArray("bands").size() == 3) { "Invalid dynamics bands." }
            RackMultiband(number(m,"lowHz"), number(m,"highHz"), m.getAsJsonArray("bands").map(::readDynamics))
        },
        loudness = o.get("loudness")?.let { v ->
            val l = objectFields(v, setOf("referenceVolume", "bassCapDb", "trebleCapDb", "strength"))
            RackLoudness(number(l,"referenceVolume"), number(l,"bassCapDb"), number(l,"trebleCapDb"), number(l,"strength"))
        },
    )
    private fun readDynamics(v: JsonElement): RackDynamics {
        val d = objectFields(v, setOf("thresholdDb", "ratio", "attackMs", "releaseMs", "kneeDb", "makeupDb", "rangeDb", "detector", "solo", "mute"))
        return RackDynamics(number(d,"thresholdDb"), number(d,"ratio"), number(d,"attackMs"), number(d,"releaseMs"), number(d,"kneeDb"), number(d,"makeupDb"), number(d,"rangeDb"), RackDetector.valueOf(string(d,"detector")), bool(d,"solo"), bool(d,"mute"))
    }
    private fun objectFields(v: JsonElement, keys: Set<String>): JsonObject {
        require(v.isJsonObject && v.asJsonObject.keySet() == keys) { "Incomplete or unsupported stage settings." }
        return v.asJsonObject
    }
    private fun string(o: JsonObject, k: String): String = o.get(k).let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
    private fun bool(o: JsonObject, k: String): Boolean = o.get(k).let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean }
    private fun number(o: JsonObject, k: String): Double = o.get(k).let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite()); it.asDouble }
}

fun ProcessingRack.usesGraph(): Boolean = output != null || nodes.any { it.inputs != null || it.kind == RackNodeKind.ALIGNMENT_DELAY || (it.oversampling ?: 1) > 1 } || nodes.count { it.kind == RackNodeKind.CONVOLUTION } > 1
