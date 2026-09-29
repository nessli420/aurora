package com.aurora.music.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.ui.graphics.vector.ImageVector
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.ui.screens.settings.SettingsDestination
import com.aurora.music.ui.screens.settings.SettingsDestinations

enum class NavPlacement { MAIN, MORE, HIDDEN }

class NavMenuItem(
    val id: String,
    val route: String,
    val icon: ImageVector,
    private val labelRes: Int = 0,
    private val destination: SettingsDestination? = null,
    val locked: Boolean = false,
    val advancedAudio: Boolean = false,
    val audio: Boolean = false,
) {
    val label: String get() = destination?.label ?: appString(labelRes)
    val topLevel: Boolean get() = route == Routes.HOME || route == Routes.SEARCH || route == Routes.LIBRARY
}

data class NavLayout(val main: List<NavMenuItem>, val more: List<NavMenuItem>, val hidden: List<NavMenuItem>) {
    fun placementOf(id: String): NavPlacement = when {
        main.any { it.id == id } -> NavPlacement.MAIN
        more.any { it.id == id } -> NavPlacement.MORE
        else -> NavPlacement.HIDDEN
    }

    fun move(id: String, to: NavPlacement): NavLayout {
        val item = NavMenu.byId(id) ?: return this
        if (item.locked && to != NavPlacement.MAIN) return this
        val m = main.filterNot { it.id == id }
        val o = more.filterNot { it.id == id }
        val h = hidden.filterNot { it.id == id }
        return when (to) {
            NavPlacement.MAIN -> NavLayout(m + item, o, h)
            NavPlacement.MORE -> NavLayout(m, o + item, h)
            NavPlacement.HIDDEN -> NavLayout(m, o, h + item)
        }
    }

    fun shift(id: String, delta: Int): NavLayout {
        fun List<NavMenuItem>.shifted(): List<NavMenuItem> {
            val i = indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in indices) return this
            return toMutableList().apply { add(j, removeAt(i)) }
        }
        return NavLayout(main.shifted(), more.shifted(), hidden)
    }

    fun visible(simpleMode: Boolean): NavLayout =
        if (!simpleMode) this else NavLayout(main.filterNot { it.advancedAudio }, more.filterNot { it.advancedAudio }, hidden)

    fun encode(): String = "main=" + main.joinToString(",") { it.id } + ";more=" + more.joinToString(",") { it.id }
}

object NavMenu {
    val items: List<NavMenuItem> = listOf(
        NavMenuItem("home", Routes.HOME, Icons.Outlined.Home, R.string.text_home_70f8bb, locked = true),
        NavMenuItem("search", Routes.SEARCH, Icons.Outlined.Search, R.string.text_search_bce064, locked = true),
        NavMenuItem("library", Routes.LIBRARY, Icons.Outlined.LibraryMusic, R.string.text_your_library_fd740f, locked = true),
        NavMenuItem("history", Routes.HISTORY, Icons.Filled.History, R.string.text_listening_history_bd9991),
        NavMenuItem("stats", Routes.STATS, Icons.Filled.Workspaces, R.string.text_listening_stats_a760b2),
        NavMenuItem("duplicates", Routes.DUPLICATES, Icons.Filled.ContentCopy, R.string.text_find_duplicates_586f31),
        NavMenuItem("playback", Routes.SETTINGS_PLAYBACK, Icons.Filled.PlayCircle, destination = SettingsDestinations.playback, audio = true),
        NavMenuItem("output", Routes.SETTINGS_OUTPUT, Icons.Filled.Devices, destination = SettingsDestinations.output, audio = true),
        NavMenuItem("equalizer", Routes.SETTINGS_EQ, Icons.Outlined.Equalizer, destination = SettingsDestinations.equalizer, audio = true),
        NavMenuItem("loudness", Routes.SETTINGS_LOUDNESS, Icons.AutoMirrored.Filled.VolumeUp, destination = SettingsDestinations.loudness, audio = true, advancedAudio = true),
        NavMenuItem("advanced_audio", Routes.SETTINGS_ADVANCED_AUDIO, Icons.Filled.Tune, destination = SettingsDestinations.advancedAudio, audio = true, advancedAudio = true),
        NavMenuItem("signal_path", Routes.SIGNAL_PATH, Icons.Filled.Route, destination = SettingsDestinations.signalPath, audio = true, advancedAudio = true),
    )

    private val defaultMain = listOf("home", "search", "library")
    private val defaultMore = listOf("history", "stats", "duplicates")

    fun byId(id: String): NavMenuItem? = items.firstOrNull { it.id == id }

    fun parse(raw: String): NavLayout {
        val parts = raw.split(';').associate { part ->
            val (key, value) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            key to value.split(',').filter { it.isNotBlank() }
        }
        val savedMain = parts["main"]
        val savedMore = parts["more"]
        val mainIds = (savedMain ?: defaultMain).filter { byId(it) != null }.distinct().toMutableList()
        val moreIds = (savedMore ?: defaultMore).filter { byId(it) != null && it !in mainIds }.distinct().toMutableList()
        items.filter { it.locked && it.id !in mainIds }.forEach { mainIds.add(0, it.id) }
        moreIds.removeAll { byId(it)?.locked == true }
        val main = mainIds.mapNotNull(::byId)
        val more = moreIds.mapNotNull(::byId)
        val hidden = items.filter { it.id !in mainIds && it.id !in moreIds }
        return NavLayout(main, more, hidden)
    }
}
