package com.aurora.extension.example

import com.aurora.extension.*
import org.json.JSONArray
import org.json.JSONObject

class ExampleExtension : AuroraExtensionService(), AudioPlugin, MediaProviderPlugin, MetadataPlugin {
    override fun describe() = JSONObject().put("name", "Aurora extension example").put("version", "1.0")
        .put("api", 1).put("license", "Apache-2.0").put("capabilities", JSONArray(listOf("audio", "media", "metadata")))
        .put("settings", JSONArray().put(JSONObject().put("id", "gainDb").put("label", "Preset gain")
            .put("minimum", -24).put("maximum", 0).put("default", -6)))

    override fun audio(settings: JSONObject) = JSONArray().put(JSONObject().put("type", "gain")
        .put("gainDb", settings.optDouble("gainDb", -6.0).coerceIn(-24.0, 0.0)))

    override fun media(offset: Int, count: Int, settings: JSONObject): JSONObject {
        val songs = JSONArray()
        if (offset == 0) songs.put(JSONObject().put("id", "tone-440").put("title", "Extension test tone")
            .put("artist", "Aurora example").put("album", "SDK example").put("durationSec", 12)
            .put("uri", "content://com.aurora.extension.example.audio/tone.wav").put("sampleRate", 48000)
            .put("bitDepth", 16).put("suffix", "wav"))
        return JSONObject().put("songs", songs).put("total", 1)
    }

    override fun metadata(query: JSONObject, settings: JSONObject) = JSONArray().put(JSONObject()
        .put("title", query.optString("title")).put("artist", query.optString("artist"))
        .put("album", "SDK example suggestion").put("year", "2026").put("score", 80))
}
