package com.aurora.music.data

import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

data class RackSubchain(val id: String, val name: String, val rack: ProcessingRack, val impulseAssets: List<ImpulseLibraryEntry> = emptyList())

object RackSubchainCodec {
    const val PREFERENCE_KEY = "processing_subchains_v1"
    const val MAX_CHAINS = 32
    fun validate(chain: RackSubchain, requireAssets: Boolean = false): RackSubchain {
        require(UUID.fromString(chain.id).toString() == chain.id) { "Invalid subchain identifier." }
        val assets = ImpulseLibraryCodec.decodeLibrary(ImpulseLibraryCodec.encodeLibrary(chain.impulseAssets)).getOrThrow()
        if (requireAssets) require(chain.rack.nodes.mapNotNull { it.impulseId }.toSet() == assets.map { it.id }.toSet()) { "Subchain impulse assets are incomplete." }
        return chain.copy(impulseAssets = assets, name = ProcessingPresetCodec.name(chain.name), rack = ProcessingRackCodec.validate(chain.rack))
    }
    fun encode(chains: List<RackSubchain>): String {
        require(chains.size <= MAX_CHAINS && chains.map { it.id }.distinct().size == chains.size) { "Invalid subchain library." }
        return JsonObject().apply {
            addProperty("version", 1)
            add("chains", JsonArray().apply { chains.forEach { value ->
                val chain = validate(value, requireAssets = true)
                add(JsonObject().apply {
                    addProperty("id", chain.id); addProperty("name", chain.name)
                    add("rack", JsonParser.parseString(ProcessingRackCodec.encode(chain.rack)))
                    add("impulseAssets", JsonParser.parseString(ImpulseLibraryCodec.encodeLibrary(chain.impulseAssets)).asJsonObject.get("entries"))
                })
            } })
        }.toString()
    }
    fun decode(json: String?): Result<List<RackSubchain>> = runCatching {
        if (json.isNullOrBlank()) return@runCatching emptyList()
        val root = ProcessingRackCodec.strictJson(json, 8_000_000).asJsonObject
        require(root.keySet() == setOf("version", "chains") && root.get("version").asDouble == 1.0 && root.get("version").asJsonPrimitive.isNumber)
        require(root.get("chains").isJsonArray && root.getAsJsonArray("chains").size() <= MAX_CHAINS)
        val result = root.getAsJsonArray("chains").map { value ->
            val o = value.asJsonObject
            require(o.keySet() == setOf("id", "name", "rack", "impulseAssets") && o.get("id").asJsonPrimitive.isString && o.get("name").asJsonPrimitive.isString)
            validate(RackSubchain(o.get("id").asString, o.get("name").asString, ProcessingRackCodec.read(o.get("rack")),
                ImpulseLibraryCodec.decodeLibrary("{\"schemaVersion\":2,\"entries\":${o.get("impulseAssets")}}").getOrThrow()), requireAssets = true)
        }
        require(result.map { it.id }.distinct().size == result.size) { "Duplicate subchain identifiers." }
        result
    }
    fun capture(rack: ProcessingRack, selectedIds: Set<String>, name: String): RackSubchain {
        val nodes = rack.nodes.filter { it.id in selectedIds }
        require(nodes.isNotEmpty()) { "Select at least one stage." }
        val ids = nodes.map { it.id }.toSet()
        fun remap(inputs: List<RackInput>?) = inputs?.map { if (it.source in ids) it else it.copy(source = RackInput.INPUT) }
        val fragment = ProcessingRack(name = name, nodes = nodes.map { node ->
            val originalIndex = rack.nodes.indexOfFirst { it.id == node.id }
            val originalInputs = node.inputs ?: listOf(RackInput(rack.nodes.getOrNull(originalIndex - 1)?.id ?: RackInput.INPUT))
            node.copy(inputs = remap(originalInputs))
        },
            output = rack.output?.takeIf { it.all { input -> input.source in ids } })
        return validate(RackSubchain(UUID.randomUUID().toString(), name, fragment))
    }
    fun append(rack: ProcessingRack, chain: RackSubchain): ProcessingRack {
        val source = validate(chain).rack
        require(rack.output == null) { "Append subchains before configuring the output mix." }
        val ids = source.nodes.associate { it.id to UUID.randomUUID().toString() }
        val input = rack.nodes.lastOrNull()?.id ?: RackInput.INPUT
        fun remap(inputs: List<RackInput>?) = inputs?.map { it.copy(source = if (it.source == RackInput.INPUT) input else ids.getValue(it.source)) }
        val nodes = source.nodes.map { it.copy(id = ids.getValue(it.id), inputs = remap(it.inputs)) }
        return ProcessingRackCodec.validate(rack.copy(nodes = rack.nodes + nodes, output = remap(source.output)))
    }
}
