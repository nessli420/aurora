package com.aurora.music.navigation

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val SETTINGS_LANGUAGE = "settings_language"
    const val SETTINGS_LISTENING = "settings_listening"
    const val SETTINGS_EXTENSIONS = "settings_extensions"
    const val SIGN_IN = "sign_in"
    const val SETUP = "setup"
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val SETTINGS_ADVANCED_AUDIO = "settings_advanced_audio"
    const val SETTINGS_APPEARANCE = "settings_appearance"
    const val SETTINGS_PLAYBACK = "settings_playback"
    const val SETTINGS_OUTPUT = "settings_output"
    const val SETTINGS_NETWORK = "settings_network"
    const val SETTINGS_LOUDNESS = "settings_loudness"
    const val SETTINGS_ALARM = "settings_alarm"
    const val SIGNAL_PATH = "signal_path"
    const val SETTINGS_EQ = "settings_eq"
    const val SETTINGS_PROCESSING_PRESETS = "settings_processing_presets"
    const val SETTINGS_PROCESSING_RACK = "settings_processing_rack"
    const val SETTINGS_TUNING = "settings_tuning"
    const val SETTINGS_IMPULSES = "settings_impulses"
    const val SETTINGS_COMPARISON = "settings_comparison"
    const val SETTINGS_PRESET_RULES = "settings_preset_rules"
    const val SETTINGS_STORAGE = "settings_storage"
    const val SETTINGS_GESTURES = "settings_gestures"
    const val SETTINGS_INTEGRATIONS = "settings_integrations"
    const val SETTINGS_INTEGRATION_LYRICS = "settings_integration_lyrics"
    const val SETTINGS_INTEGRATION_LASTFM = "settings_integration_lastfm"
    const val SETTINGS_INTEGRATION_LISTENBRAINZ = "settings_integration_listenbrainz"
    const val SETTINGS_INTEGRATION_DISCORD = "settings_integration_discord"
    const val SETTINGS_INTEGRATION_ARTIST_INFO = "settings_integration_artist_info"
    const val SETTINGS_INTEGRATION_ACOUSTID = "settings_integration_acoustid"
    const val SETTINGS_ABOUT = "settings_about"
    const val SETTINGS_VISUALIZER = "settings_visualizer"
    const val SETTINGS_SONIC = "settings_sonic"
    const val SETTINGS_PERMISSIONS = "settings_permissions"
    const val SETTINGS_ACCOUNTS = "settings_accounts"
    const val SETTINGS_BACKUP = "settings_backup"
    const val SETTINGS_SOURCES = "settings_sources"
    const val SETTINGS_ARTIST_SEPARATORS = "settings_artist_separators"
    const val DISCORD_LOGIN = "discord_login"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val NOTIFICATIONS = "notifications"
    const val DUPLICATES = "duplicates"
    const val RADIO = "radio"
    const val PODCASTS = "podcasts"

    // feed url has slashes and a query string so ride as encoded query params
    const val PODCAST_DETAIL = "podcast?feed={feed}&title={title}&image={image}&author={author}"
    fun podcastDetail(feed: String, title: String = "", image: String = "", author: String = "") =
        "podcast?feed=${android.net.Uri.encode(feed)}&title=${android.net.Uri.encode(title)}" +
            "&image=${android.net.Uri.encode(image)}&author=${android.net.Uri.encode(author)}"
    const val DETAIL = "detail/{kind}/{id}"
    fun detail(kind: String, id: String) = "detail/$kind/$id"

    // folder ids contain slashes so ride as encoded query params
    const val FOLDERS = "folders?fid={fid}&title={title}"
    fun folders(fid: String = "", title: String = "") =
        "folders?fid=${android.net.Uri.encode(fid)}&title=${android.net.Uri.encode(title)}"

    const val SMART_EDIT = "smart_edit?id={id}"
    fun smartEdit(id: String = "") = "smart_edit?id=${android.net.Uri.encode(id)}"

    const val TAG_EDIT = "tag_edit/{songId}"
    fun tagEdit(songId: String) = "tag_edit/${android.net.Uri.encode(songId)}"
}

data class TopLevelDestination(
    val route: String,
    @androidx.annotation.StringRes private val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    val label: String get() = appString(labelRes)
}

val topLevelDestinations = listOf(
    TopLevelDestination(Routes.HOME, R.string.text_home_70f8bb, Icons.Filled.Home, Icons.Outlined.Home),
    TopLevelDestination(Routes.SEARCH, R.string.text_search_bce064, Icons.Filled.Search, Icons.Outlined.Search),
    TopLevelDestination(Routes.LIBRARY, R.string.text_library_b8100f, Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
)
