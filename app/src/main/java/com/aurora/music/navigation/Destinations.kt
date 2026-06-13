package com.aurora.music.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val SIGN_IN = "sign_in"
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val SETTINGS_APPEARANCE = "settings_appearance"
    const val SETTINGS_PLAYBACK = "settings_playback"
    const val SETTINGS_EQ = "settings_eq"
    const val SETTINGS_STORAGE = "settings_storage"
    const val SETTINGS_GESTURES = "settings_gestures"
    const val SETTINGS_INTEGRATIONS = "settings_integrations"
    const val SETTINGS_ABOUT = "settings_about"
    const val SETTINGS_ACCOUNTS = "settings_accounts"
    const val SETTINGS_BACKUP = "settings_backup"
    const val DISCORD_LOGIN = "discord_login"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val DUPLICATES = "duplicates"
    const val DETAIL = "detail/{kind}/{id}"
    fun detail(kind: String, id: String) = "detail/$kind/$id"

    // Folder ids can contain slashes (local file paths), so they ride as encoded query params.
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
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val topLevelDestinations = listOf(
    TopLevelDestination(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    TopLevelDestination(Routes.SEARCH, "Search", Icons.Filled.Search, Icons.Outlined.Search),
    TopLevelDestination(Routes.LIBRARY, "Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
)
