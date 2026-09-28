package com.aurora.music.localization

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppStrings {
    private val systemLocale: Locale = Locale.getDefault()
    private val selected = MutableStateFlow("")
    @Volatile private var loaded: StringTable? = null

    val languageTag: StateFlow<String> = selected.asStateFlow()

    val locale: Locale get() = table.locale

    internal val table: StringTable
        get() = loaded ?: synchronized(this) { loaded ?: StringTable(localeOf(selected.value)).also { loaded = it } }

    fun setLocale(tag: String) = synchronized(this) {
        loaded = null
        Locale.setDefault(localeOf(tag))
        selected.value = tag
    }

    private fun localeOf(tag: String): Locale = if (tag.isBlank()) systemLocale else Locale.forLanguageTag(tag)
}

fun appString(id: Int, vararg args: Any?): String = AppStrings.table.let { table ->
    val text = table.string(id)
    if (args.isEmpty()) text else String.format(table.locale, text, *args)
}

fun appPlural(id: Int, count: Int): String =
    AppStrings.table.let { table -> String.format(table.locale, table.plural(id, count), count) }
