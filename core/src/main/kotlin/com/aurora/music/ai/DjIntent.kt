package com.aurora.music.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class DjVocals { ANY, LESS, NONE }
enum class DjFamiliarity { FAVOURITES, MIX, DISCOVER }
enum class DjTempo { ANY, SLOW, MID, FAST }

data class DjIntent(
    val title: String = "",
    val energyStart: Int = 5,
    val energyEnd: Int = 5,
    val moods: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val decades: List<Int> = emptyList(),
    val vocals: DjVocals = DjVocals.ANY,
    val familiarity: DjFamiliarity = DjFamiliarity.MIX,
    val tempo: DjTempo = DjTempo.ANY,
    val search: List<String> = emptyList(),
    val count: Int = DEFAULT_COUNT,
) {
    fun toJson(): String = JsonObject().apply {
        addProperty("title", title)
        add("energy", JsonArray().apply { add(energyStart); add(energyEnd) })
        add("moods", strings(moods))
        add("genres", strings(genres))
        add("artists", strings(artists))
        add("avoid", strings(avoid))
        add("decades", JsonArray().apply { decades.forEach { add(it) } })
        addProperty("vocals", vocals.name.lowercase())
        addProperty("familiar", familiarity.name.lowercase())
        addProperty("tempo", tempo.name.lowercase())
        add("search", strings(search))
        addProperty("count", count)
    }.toString()

    companion object {
        const val DEFAULT_COUNT = 25
        const val MAX_COUNT = 100

        private fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }

        // model output is untrusted text, anything malformed or out of range is clamped or dropped
        fun parse(json: String): DjIntent? {
            val root = runCatching { JsonParser.parseString(json.trim()).asJsonObject }.getOrNull() ?: return null
            fun list(key: String) = (root.get(key) as? JsonArray)?.mapNotNull { e ->
                runCatching { e.asString.trim() }.getOrNull()?.takeIf { it.isNotEmpty() && it.length <= 60 }
            }?.distinctBy { it.lowercase() }?.take(8).orEmpty()
            fun word(key: String) = runCatching { root.get(key).asString.trim().uppercase() }.getOrNull()
            val energy = (root.get("energy") as? JsonArray)?.mapNotNull { runCatching { it.asInt }.getOrNull() }.orEmpty()
            return DjIntent(
                title = runCatching { root.get("title").asString.trim() }.getOrNull().orEmpty().take(60),
                energyStart = (energy.getOrNull(0) ?: 5).coerceIn(0, 10),
                energyEnd = (energy.getOrNull(1) ?: energy.getOrNull(0) ?: 5).coerceIn(0, 10),
                moods = list("moods"),
                genres = list("genres"),
                artists = list("artists"),
                avoid = list("avoid"),
                decades = (root.get("decades") as? JsonArray)?.mapNotNull { runCatching { it.asInt }.getOrNull() }
                    ?.filter { it in 1900..2090 }?.map { it / 10 * 10 }?.distinct().orEmpty(),
                vocals = DjVocals.entries.firstOrNull { it.name == word("vocals") } ?: DjVocals.ANY,
                familiarity = when (word("familiar")) { "FAVOURITES", "FAVORITES" -> DjFamiliarity.FAVOURITES; "DISCOVER" -> DjFamiliarity.DISCOVER; else -> DjFamiliarity.MIX },
                tempo = DjTempo.entries.firstOrNull { it.name == word("tempo") } ?: DjTempo.ANY,
                search = list("search").take(4),
                count = (runCatching { root.get("count").asInt }.getOrNull() ?: DEFAULT_COUNT).coerceIn(5, MAX_COUNT),
            )
        }
    }
}
