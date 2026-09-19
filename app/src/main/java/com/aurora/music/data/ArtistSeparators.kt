package com.aurora.music.data

import com.aurora.music.model.Artist
import com.aurora.music.model.Song
import com.google.gson.Gson
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class SeparatorMatch(val label: String) { OFF("Off"), SPACED("Spaced"), WORD("Whole word"), ANYWHERE("Anywhere") }

data class ArtistSeparator(val text: String? = "", val match: SeparatorMatch? = SeparatorMatch.SPACED)

data class ArtistSeparators(val rules: List<ArtistSeparator>? = defaults()) {
    fun split(credit: String): List<String> = splitter()(credit)

    internal fun splitter(): (String) -> List<String> {
        val patterns = rules.orEmpty().filter { it.match != SeparatorMatch.OFF && !it.text.isNullOrBlank() }
            .sortedByDescending { it.text.orEmpty().length }.map { rule ->
                val token = Regex.escape(rule.text.orEmpty())
                when (rule.match) {
                    SeparatorMatch.SPACED -> "(?<=\\s)$token(?=\\s)"
                    SeparatorMatch.WORD -> "(?<![\\p{L}\\p{N}_])$token(?![\\p{L}\\p{N}_])"
                    else -> token
                }
            }
        val pattern = patterns.takeIf { it.isNotEmpty() }?.let { Regex(it.joinToString("|"), RegexOption.IGNORE_CASE) }
        return { credit ->
            val names = pattern?.let { credit.split(it) } ?: listOf(credit)
            names.map(String::trim).filter(String::isNotBlank).distinctBy(::artistNameKey)
                .ifEmpty { listOf(credit.trim().ifEmpty { "Unknown artist" }) }
        }
    }

    companion object {
        fun defaults() = listOf(
            ArtistSeparator(";", SeparatorMatch.ANYWHERE), ArtistSeparator(",", SeparatorMatch.ANYWHERE),
            ArtistSeparator("/", SeparatorMatch.SPACED), ArtistSeparator("&", SeparatorMatch.SPACED),
            ArtistSeparator("feat.", SeparatorMatch.WORD), ArtistSeparator("ft.", SeparatorMatch.WORD),
            ArtistSeparator("with", SeparatorMatch.WORD),
        )
    }
}

object ArtistSeparatorsCodec {
    const val KEY = "local_artist_separators_v1"
    private val gson = Gson()
    fun decode(json: String?): ArtistSeparators = if (json.isNullOrBlank()) ArtistSeparators() else
        validate(requireNotNull(gson.fromJson(json, ArtistSeparators::class.java)))
    fun encode(value: ArtistSeparators): String = gson.toJson(validate(value))
    private fun validate(value: ArtistSeparators): ArtistSeparators {
        val rules = value.rules ?: ArtistSeparators.defaults()
        require(rules.size <= 24) { "Use up to 24 separators." }
        require(rules.all { it.text != null && it.text.trim().length in 1..32 && it.text.none(Char::isISOControl) && it.match != null }) {
            "Each separator needs text and a matching rule."
        }
        val clean = rules.map { it.copy(text = it.text!!.trim()) }
        require(clean.map { it.text!!.lowercase(Locale.ROOT) }.distinct().size == clean.size) { "Remove duplicate separators." }
        return ArtistSeparators(clean)
    }
}

internal fun artistNameKey(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
    .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

internal class LocalArtistIndex(rawSongs: List<Song>, separators: ArtistSeparators) {
    private val names = linkedMapOf<String, String>()
    private val memberships = linkedMapOf<String, MutableList<Song>>()
    private val legacyIds = mutableMapOf<String, String>()
    private val idsByName = mutableMapOf<String, String>()
    private val split = separators.splitter()
    val songs: List<Song> = rawSongs.map { song ->
        val ids = split(song.artist).map { name ->
            val key = artistNameKey(name)
            val id = idsByName.getOrPut(key) {
                "local-artist-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }
            names.putIfAbsent(id, name)
            id
        }.distinct()
        val indexed = song.copy(artistId = ids.first())
        if (song.artistId.isNotBlank()) legacyIds.putIfAbsent(song.artistId, ids.first())
        ids.forEach { memberships.getOrPut(it) { mutableListOf() }.add(indexed) }
        indexed
    }
    val artists: List<Artist> = names.map { (id, name) ->
        Artist(id, name, memberships[id].orEmpty().firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(), 0)
    }.sortedBy { artistNameKey(it.name) }
    private val artistsById = artists.associateBy { it.id }
    fun artist(id: String): Artist? = artistsById[legacyIds[id] ?: id]
    fun songsBy(id: String): List<Song> = memberships[legacyIds[id] ?: id].orEmpty().distinctBy { it.id }
}
