package com.aurora.music.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsInputAntenna
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
    val selectedIcon: ImageVector = icon,
    // shown only while the signed-in sources can explore a streaming catalogue
    val discovery: Boolean = false,
) {
    val label: String get() = destination?.label ?: appString(labelRes)
    val shortLabel: String get() = if (route == Routes.LIBRARY) appString(R.string.text_library_b8100f) else label
    val topLevel: Boolean get() = route == Routes.HOME || route == Routes.SEARCH || route == Routes.LIBRARY
}

// main/more/hidden drive the side menu and tablet sidebar; bar is the phone tab bar
data class NavLayout(
    val main: List<NavMenuItem>,
    val more: List<NavMenuItem>,
    val hidden: List<NavMenuItem>,
    val bar: List<NavMenuItem> = NavMenu.defaultBar(),
    val barMore: Boolean = false,
) {
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
            NavPlacement.MAIN -> copy(main = m + item, more = o, hidden = h)
            NavPlacement.MORE -> copy(main = m, more = o + item, hidden = h)
            NavPlacement.HIDDEN -> copy(main = m, more = o, hidden = h + item)
        }
    }

    fun shift(id: String, delta: Int): NavLayout = copy(main = main.shifted(id, delta), more = more.shifted(id, delta))

    val tabSlots: Int get() = bar.size + if (barMore) 1 else 0
    val canAddTab: Boolean get() = tabSlots < NavMenu.MAX_TABS
    // only home is pinned here; search and library are locked to the side menu, not the tab bar
    fun canRemoveTab(item: NavMenuItem): Boolean = item.id != NavMenu.PINNED_TAB && bar.size > NavMenu.MIN_TABS

    fun addTab(id: String): NavLayout {
        val item = NavMenu.byId(id) ?: return this
        return if (!canAddTab || bar.any { it.id == id }) this else copy(bar = bar + item)
    }

    fun removeTab(id: String): NavLayout {
        val item = bar.firstOrNull { it.id == id } ?: return this
        return if (canRemoveTab(item)) copy(bar = bar - item) else this
    }

    fun shiftTab(id: String, delta: Int): NavLayout = copy(bar = bar.shifted(id, delta))

    fun withBarMore(on: Boolean): NavLayout = if (on && !barMore && !canAddTab) this else copy(barMore = on)

    fun visible(simpleMode: Boolean, discovery: Boolean = true): NavLayout {
        fun List<NavMenuItem>.shown() = filterNot { (simpleMode && it.advancedAudio) || (!discovery && it.discovery) }
        return copy(main = main.shown(), more = more.shown(), bar = bar.shown())
    }

    fun encode(): String = "main=" + main.joinToString(",") { it.id } + ";more=" + more.joinToString(",") { it.id } +
        ";hidden=" + hidden.joinToString(",") { it.id } +
        ";bar=" + bar.joinToString(",") { it.id } + ";barmore=" + if (barMore) "1" else "0"

    // the side menu and the tab bar reset on their own so one never wipes the other
    fun resetSideMenu(): NavLayout = NavMenu.parse("").copy(bar = bar, barMore = barMore)
    fun resetTabBar(): NavLayout = copy(bar = NavMenu.defaultBar(), barMore = false)
}

private fun List<NavMenuItem>.shifted(id: String, delta: Int): List<NavMenuItem> {
    val i = indexOfFirst { it.id == id }
    val j = i + delta
    if (i < 0 || j !in indices) return this
    return toMutableList().apply { add(j, removeAt(i)) }
}

object NavMenu {
    val items: List<NavMenuItem> = listOf(
        NavMenuItem("home", Routes.HOME, Icons.Outlined.Home, R.string.text_home_70f8bb, locked = true, selectedIcon = Icons.Filled.Home),
        NavMenuItem("search", Routes.SEARCH, Icons.Outlined.Search, R.string.text_search_bce064, locked = true, selectedIcon = Icons.Filled.Search),
        NavMenuItem("library", Routes.LIBRARY, Icons.Outlined.LibraryMusic, R.string.text_your_library_fd740f, locked = true, selectedIcon = Icons.Filled.LibraryMusic),
        NavMenuItem("explore", Routes.EXPLORE, Icons.Outlined.Explore, R.string.discovery_explore, discovery = true, selectedIcon = Icons.Filled.Explore),
        NavMenuItem("ai_dj", Routes.AI_DJ, Icons.Filled.AutoAwesome, R.string.ai_dj_title),
        NavMenuItem("radio", Routes.RADIO, Icons.Filled.Radio, R.string.text_radio_b11bf1),
        NavMenuItem("podcasts", Routes.PODCASTS, Icons.Filled.Podcasts, R.string.text_podcasts_fd52b4),
        NavMenuItem("history", Routes.HISTORY, Icons.Filled.History, R.string.text_listening_history_bd9991),
        NavMenuItem("stats", Routes.STATS, Icons.Filled.Workspaces, R.string.text_listening_stats_a760b2),
        NavMenuItem("duplicates", Routes.DUPLICATES, Icons.Filled.ContentCopy, R.string.text_find_duplicates_586f31),
        NavMenuItem("playback", Routes.SETTINGS_PLAYBACK, Icons.Filled.PlayCircle, destination = SettingsDestinations.playback, audio = true),
        NavMenuItem("output", Routes.SETTINGS_OUTPUT, Icons.Filled.Devices, destination = SettingsDestinations.output, audio = true),
        NavMenuItem("network", Routes.SETTINGS_NETWORK, Icons.Filled.SettingsInputAntenna, destination = SettingsDestinations.network, audio = true),
        NavMenuItem("equalizer", Routes.SETTINGS_EQ, Icons.Outlined.Equalizer, destination = SettingsDestinations.equalizer, audio = true),
        NavMenuItem("loudness", Routes.SETTINGS_LOUDNESS, Icons.AutoMirrored.Filled.VolumeUp, destination = SettingsDestinations.loudness, audio = true, advancedAudio = true),
        NavMenuItem("advanced_audio", Routes.SETTINGS_ADVANCED_AUDIO, Icons.Filled.Tune, destination = SettingsDestinations.advancedAudio, audio = true, advancedAudio = true),
        NavMenuItem("signal_path", Routes.SIGNAL_PATH, Icons.Filled.Route, destination = SettingsDestinations.signalPath, audio = true, advancedAudio = true),
    )

    private val defaultMain = listOf("home", "search", "library", "explore")
    private val defaultMore = listOf("ai_dj", "radio", "podcasts", "history", "stats", "duplicates")
    private val defaultTabs = listOf("home", "search", "library")
    const val MAX_TABS = 5
    const val MIN_TABS = 2
    const val PINNED_TAB = "home"

    fun byId(id: String): NavMenuItem? = items.firstOrNull { it.id == id }

    fun defaultBar(): List<NavMenuItem> = defaultTabs.mapNotNull(::byId)

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
        // layouts saved before explore existed get it next to the library; a newer layout that hid it keeps it hidden
        if (parts["hidden"] == null && "explore" !in mainIds && "explore" !in moreIds) {
            mainIds.add((mainIds.indexOf("library") + 1).coerceIn(0, mainIds.size), "explore")
        }
        val main = mainIds.mapNotNull(::byId)
        val more = moreIds.mapNotNull(::byId)
        val hidden = items.filter { it.id !in mainIds && it.id !in moreIds }
        // home anchors back navigation so it always stays a tab
        val barMore = parts["barmore"]?.firstOrNull() == "1"
        val barIds = (parts["bar"] ?: defaultTabs).filter { byId(it) != null }.distinct().toMutableList()
        if (PINNED_TAB !in barIds) barIds.add(0, PINNED_TAB)
        if (barIds.size < MIN_TABS) defaultTabs.filterNot { it in barIds }.take(MIN_TABS - barIds.size).forEach(barIds::add)
        val bar = barIds.take(if (barMore) MAX_TABS - 1 else MAX_TABS).mapNotNull(::byId)
        return NavLayout(main, more, hidden, bar, barMore)
    }
}
