package com.aurora.music.navigation

import com.aurora.music.localization.appString
import com.aurora.music.R

import java.net.URLEncoder

object Routes {
    const val SETTINGS_LANGUAGE = "settings_language"
    const val SETTINGS_LISTENING = "settings_listening"
    const val SIGN_IN = "sign_in"
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val SETTINGS_ADVANCED_AUDIO = "settings_advanced_audio"
    const val SETTINGS_APPEARANCE = "settings_appearance"
    const val SETTINGS_NAV_MENU = "settings_nav_menu"
    const val SETTINGS_PLAYBACK = "settings_playback"
    const val SETTINGS_OUTPUT = "settings_output"
    const val SETTINGS_LOUDNESS = "settings_loudness"
    const val SIGNAL_PATH = "signal_path"
    const val SETTINGS_EQ = "settings_eq"
    const val SETTINGS_PROCESSING_PRESETS = "settings_processing_presets"
    const val SETTINGS_PROCESSING_RACK = "settings_processing_rack"
    const val SETTINGS_TUNING = "settings_tuning"
    const val SETTINGS_IMPULSES = "settings_impulses"
    const val SETTINGS_COMPARISON = "settings_comparison"
    const val SETTINGS_PRESET_RULES = "settings_preset_rules"
    const val SETTINGS_STORAGE = "settings_storage"
    const val SETTINGS_INTEGRATIONS = "settings_integrations"
    const val SETTINGS_INTEGRATION_LYRICS = "settings_integration_lyrics"
    const val SETTINGS_INTEGRATION_LASTFM = "settings_integration_lastfm"
    const val SETTINGS_INTEGRATION_LISTENBRAINZ = "settings_integration_listenbrainz"
    const val SETTINGS_INTEGRATION_ARTIST_INFO = "settings_integration_artist_info"
    const val SETTINGS_ABOUT = "settings_about"
    const val SETTINGS_VISUALIZER = "settings_visualizer"
    const val SETTINGS_SONIC = "settings_sonic"
    const val SETTINGS_ACCOUNTS = "settings_accounts"
    const val SETTINGS_BACKUP = "settings_backup"
    const val SETTINGS_SOURCES = "settings_sources"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val NOTIFICATIONS = "notifications"
    const val DUPLICATES = "duplicates"
    const val RADIO = "radio"
    const val PODCASTS = "podcasts"

    const val DETAIL = "detail/{kind}/{id}"
    fun detail(kind: String, id: String) = "detail/$kind/$id"

    // folder ids contain slashes so ride as encoded query params
    const val FOLDERS = "folders?fid={fid}&title={title}"
    fun folders(fid: String = "", title: String = "") = "folders?fid=${encode(fid)}&title=${encode(title)}"

    const val SMART_EDIT = "smart_edit?id={id}"
    fun smartEdit(id: String = "") = "smart_edit?id=${encode(id)}"

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

data class TopLevelDestination(val route: String, private val labelRes: Int) {
    val label: String get() = appString(labelRes)
}

val topLevelDestinations = listOf(
    TopLevelDestination(Routes.HOME, R.string.text_home_70f8bb),
    TopLevelDestination(Routes.SEARCH, R.string.text_search_bce064),
    TopLevelDestination(Routes.LIBRARY, R.string.text_library_b8100f),
)
