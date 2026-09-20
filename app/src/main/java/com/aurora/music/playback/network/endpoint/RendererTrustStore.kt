package com.aurora.music.playback.network.endpoint

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class RendererTrustStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "renderer-trust-v1.json"))

    internal fun controllers(): List<ControllerTrust> = synchronized(TRUST_LOCK) { read().get("controllers")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
        runCatching {
            val value = item.asJsonObject
            val id = value.get("id").asString
            val name = value.get("name").asString
            val hash = value.get("hash").asString
            require(id.length <= 64 && name.length <= 64 && hash.matches(Regex("[a-f0-9]{64}")))
            ControllerTrust(PairedController(id, name, value.get("pairedAtMs").asLong), hash)
        }.getOrNull()
    }.orEmpty().take(8) }

    internal fun saveControllers(controllers: List<ControllerTrust>) = synchronized(TRUST_LOCK) {
        val content = read()
        content.add("controllers", JsonArray().apply {
            controllers.forEach { item -> add(JsonObject().apply {
                addProperty("id", item.controller.id)
                addProperty("name", item.controller.name)
                addProperty("pairedAtMs", item.controller.pairedAtMs)
                addProperty("hash", item.tokenHash)
            }) }
        })
        write(content)
    }

    fun renderers(): List<PairedRenderer> = synchronized(TRUST_LOCK) { read().get("renderers")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
        runCatching {
            val value = item.asJsonObject
            val address = EndpointAddress.parse(value.get("address").asString + "#" + value.get("fingerprint").asString)
            val token = value.get("token").asString
            require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
            PairedRenderer(value.get("id").asString.take(64), value.get("name").asString.take(64),
                address.address, address.fingerprint, value.get("clientId").asString.take(64), token)
        }.getOrNull()
    }.orEmpty().take(16) }

    fun saveRenderer(renderer: PairedRenderer) = synchronized(TRUST_LOCK) {
        val values = renderers().filterNot { it.id == renderer.id } + renderer
        saveRenderers(values.takeLast(16))
    }

    fun forgetRenderer(id: String) = synchronized(TRUST_LOCK) { saveRenderers(renderers().filterNot { it.id == id }) }

    private fun saveRenderers(values: List<PairedRenderer>) {
        val content = read()
        content.add("renderers", JsonArray().apply {
            values.forEach { item -> add(JsonObject().apply {
                addProperty("id", item.id)
                addProperty("name", item.name)
                addProperty("address", item.address)
                addProperty("fingerprint", item.fingerprint)
                addProperty("clientId", item.clientId)
                addProperty("token", item.token)
            }) }
        })
        write(content)
    }

    private fun read(): JsonObject = runCatching {
        val bytes = file.openRead().use { input ->
            val data = input.readBytes()
            require(data.size <= 64 * 1024)
            data
        }
        JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    }.getOrElse { JsonObject() }

    private fun write(value: JsonObject) {
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object { private val TRUST_LOCK = Any() }
}
