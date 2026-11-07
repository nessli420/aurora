package com.aurora.music.data

import java.text.Normalizer
import java.util.Locale

// finds settings by their localized title, description and page name
object SettingsSearch {
    data class Candidate<T>(val item: T, val title: String, val subtitle: String = "", val page: String = "")

    fun <T> rank(query: String, candidates: List<Candidate<T>>, limit: Int = 60): List<T> {
        val tokens = words(query)
        if (tokens.isEmpty()) return emptyList()
        val whole = normalize(query).trim()
        return candidates.mapNotNull { c -> score(tokens, whole, c)?.let { it to c } }
            .sortedWith(compareByDescending<Pair<Double, Candidate<T>>> { it.first }.thenBy { it.second.title.length })
            .take(limit).map { it.second.item }
    }

    private fun <T> score(tokens: List<String>, whole: String, c: Candidate<T>): Double? {
        val title = normalize(c.title)
        val subtitle = normalize(c.subtitle)
        val page = normalize(c.page)
        var total = 0.0
        for (token in tokens) {
            total += listOf(3.0 to title, 2.0 to subtitle, 1.0 to page).maxOf { (weight, text) -> weight * match(token, text) }
                .takeIf { it > 0 } ?: return null
        }
        if (title.startsWith(whole)) total += 5 else if (title.contains(whole)) total += 2
        return total
    }

    // 1 for the start of a word, 0.5 inside a word, which scripts without spaces rely on
    private fun match(token: String, text: String): Double = when {
        text.split(' ').any { it.startsWith(token) } -> 1.0
        (token.length >= 3 || token.any { it.code > 0x7F }) && text.contains(token) -> 0.5
        else -> 0.0
    }

    private fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotBlank() }

    internal fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(MARKS, "").lowercase(Locale.ROOT)
        .replace(SEPARATORS, " ").replace(SPACES, " ")

    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")
}
