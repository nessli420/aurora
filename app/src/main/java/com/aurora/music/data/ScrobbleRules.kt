package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ScrobbleArtistRule(
    val sourceArtist: String? = "",
    val replacementArtist: String? = "",
)

object ScrobbleArtistRulesCodec {
    private val gson = Gson()
    private val type = object : TypeToken<List<ScrobbleArtistRule>>() {}.type

    fun encode(rules: List<ScrobbleArtistRule>): String = gson.toJson(rules)

    fun decode(raw: String?): List<ScrobbleArtistRule> = if (raw.isNullOrBlank()) emptyList() else
        gson.fromJson<List<ScrobbleArtistRule>>(raw, type).orEmpty().mapNotNull { rule ->
            val source = rule.sourceArtist.orEmpty().trim()
            val replacement = rule.replacementArtist.orEmpty().trim()
            if (source.isBlank() || replacement.isBlank()) null else ScrobbleArtistRule(source, replacement)
        }
}

fun replaceScrobbleArtist(artist: String, rules: List<ScrobbleArtistRule>): String =
    rules.firstOrNull { it.sourceArtist.orEmpty().trim().equals(artist.trim(), ignoreCase = true) }
        ?.replacementArtist.orEmpty().trim().ifBlank { artist }
