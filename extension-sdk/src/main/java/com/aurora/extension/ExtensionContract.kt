package com.aurora.extension

import org.json.JSONArray
import org.json.JSONObject

object ExtensionContract {
    const val ACTION = "com.aurora.extension.SERVICE"
    const val API = 1
    const val MAX_MESSAGE_BYTES = 196_608
    const val REQUEST = 1
    const val RESPONSE = 2
    const val DESCRIBE = "describe"
    const val AUDIO = "audio"
    const val MEDIA = "media"
    const val METADATA = "metadata"
}

interface AudioPlugin {
    fun audio(settings: JSONObject): JSONArray
}

interface MediaProviderPlugin {
    fun media(offset: Int, count: Int, settings: JSONObject): JSONObject
}

interface MetadataPlugin {
    fun metadata(query: JSONObject, settings: JSONObject): JSONArray
}

interface AuroraExtension {
    fun describe(): JSONObject
}
