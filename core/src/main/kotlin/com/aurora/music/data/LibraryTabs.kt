package com.aurora.music.data

import com.aurora.music.model.LibraryFilter

// the library tab rail in the user's order; hidden tabs keep their place so showing one puts it back
data class LibraryTabs(val order: List<LibraryFilter> = LibraryFilter.entries, val hidden: Set<LibraryFilter> = emptySet()) {
    val visible: List<LibraryFilter> get() = order.filter { it !in hidden }

    fun move(tab: LibraryFilter, by: Int): LibraryTabs {
        val from = order.indexOf(tab)
        val to = (from + by).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    // one tab always stays so the library never opens on nothing
    fun toggle(tab: LibraryFilter): LibraryTabs = when {
        tab in hidden -> copy(hidden = hidden - tab)
        visible.size > 1 -> copy(hidden = hidden + tab)
        else -> this
    }

    fun encode(): String = order.joinToString(",") { if (it in hidden) "-${it.name}" else it.name }

    companion object {
        fun decode(raw: String?): LibraryTabs {
            if (raw.isNullOrBlank()) return LibraryTabs()
            val parsed = raw.split(',').mapNotNull { token ->
                val name = token.trim().removePrefix("-")
                LibraryFilter.entries.firstOrNull { it.name == name }?.let { it to token.trim().startsWith("-") }
            }.distinctBy { it.first }
            // tabs added in later versions show up at the end
            val order = parsed.map { it.first } + LibraryFilter.entries.filter { e -> parsed.none { it.first == e } }
            val tabs = LibraryTabs(order, parsed.filter { it.second }.map { it.first }.toSet())
            return if (tabs.visible.isEmpty()) tabs.copy(hidden = emptySet()) else tabs
        }
    }
}
