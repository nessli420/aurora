package com.aurora.music.localization

import java.io.DataInputStream
import java.util.Locale
import kotlin.math.abs

internal class StringTable(val locale: Locale) {
    private class Values(val strings: Array<String?>, val plurals: Array<Array<String?>?>)

    private val strings: Array<String?>
    private val plurals: Array<Array<String?>?>

    init {
        val chain = listOf(locale.toLanguageTag(), locale.language, "default").distinct().mapNotNull(::read)
        val fallback = chain.last()
        strings = Array(fallback.strings.size) { index -> chain.firstNotNullOfOrNull { it.strings[index] } }
        plurals = Array(fallback.plurals.size) { index -> chain.firstNotNullOfOrNull { it.plurals[index] } }
    }

    fun string(id: Int): String =
        requireNotNull(strings.getOrNull(id - STRING_BASE)) { "No string resource 0x%08x for $locale".format(id) }

    fun plural(id: Int, count: Int): String {
        val items = requireNotNull(plurals.getOrNull(id - PLURALS_BASE)) { "No plurals resource 0x%08x for $locale".format(id) }
        return requireNotNull(items[quantity(count)] ?: items[OTHER]) { "No quantity for $count in plurals 0x%08x".format(id) }
    }

    private fun quantity(count: Int): Int {
        val n = abs(count)
        return when (locale.language) {
            "ru" -> when {
                n % 10 == 1 && n % 100 != 11 -> ONE
                n % 10 in 2..4 && n % 100 !in 12..14 -> FEW
                else -> MANY
            }
            "pl" -> when {
                n == 1 -> ONE
                n % 10 in 2..4 && n % 100 !in 12..14 -> FEW
                else -> MANY
            }
            else -> if (n == 1) ONE else OTHER
        }
    }

    private fun read(tag: String): Values? = StringTable::class.java.getResourceAsStream("/strings/$tag.bin")?.let { stream ->
        DataInputStream(stream.buffered()).use { data ->
            fun text() = data.readInt().takeIf { it >= 0 }?.let { String(ByteArray(it).also(data::readFully)) }
            val strings = Array(data.readInt()) { text() }
            val plurals = Array(data.readInt()) { Array(OTHER + 1) { text() }.takeIf { items -> items.any { it != null } } }
            Values(strings, plurals)
        }
    }

    private companion object {
        const val STRING_BASE = 0x7f010000
        const val PLURALS_BASE = 0x7f020000
        const val ONE = 1
        const val FEW = 3
        const val MANY = 4
        const val OTHER = 5
    }
}
