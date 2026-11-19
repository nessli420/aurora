package com.aurora.music.ui.whatsnew

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.aurora.music.R
import com.aurora.music.navigation.Routes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** One new feature in the tour. [route] adds a "Try it" button; [preview] replaces the plain icon art. */
data class WhatsNewFeature(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val route: String? = null,
    val preview: (@Composable () -> Unit)? = null,
)

data class WhatsNewRelease(val version: String, val features: List<WhatsNewFeature>, val fixes: List<Int> = emptyList())

/**
 * Release notes for the update tour, newest first. A release shows once versionName reaches its version.
 * Each feature needs its whats_new_* strings in every locale; keep the desktop catalog in step.
 */
object WhatsNewCatalog {
    val releases: List<WhatsNewRelease> = listOf(
        WhatsNewRelease(
            "4.0.0",
            features = listOf(
                WhatsNewFeature(Icons.Filled.Dashboard, R.string.whats_new_layouts_title, R.string.whats_new_layouts_body,
                    Routes.SETTINGS_APPEARANCE_LAYOUTS),
                WhatsNewFeature(Icons.Filled.AutoAwesome, R.string.whats_new_foryou_title, R.string.whats_new_foryou_body, Routes.HOME),
                WhatsNewFeature(Icons.Filled.Lightbulb, R.string.whats_new_genius_title, R.string.whats_new_genius_body,
                    Routes.SETTINGS_INTEGRATIONS),
                WhatsNewFeature(Icons.Filled.Forum, R.string.whats_new_yt_comments_title, R.string.whats_new_yt_comments_body),
                WhatsNewFeature(Icons.Filled.Folder, R.string.whats_new_spotify_folders_title, R.string.whats_new_spotify_folders_body,
                    Routes.LIBRARY),
                // Android only: desktop has no Spotify mirror
                WhatsNewFeature(Icons.Filled.Sync, R.string.whats_new_spotify_mirror_title, R.string.whats_new_spotify_mirror_body,
                    Routes.SETTINGS_PLAYBACK),
                WhatsNewFeature(Icons.Filled.Favorite, R.string.whats_new_apple_library_title, R.string.whats_new_apple_library_body),
                WhatsNewFeature(Icons.Filled.Link, R.string.whats_new_song_menu_title, R.string.whats_new_song_menu_body),
            ),
            fixes = listOf(R.string.whats_new_fix_marquee, R.string.whats_new_fix_motion, R.string.whats_new_fix_glass,
                R.string.whats_new_fix_appearance, R.string.whats_new_fix_swipe, R.string.whats_new_fix_open_player,
                R.string.whats_new_fix_back, R.string.whats_new_fix_sd_card, R.string.whats_new_fix_heart,
                R.string.whats_new_fix_soundcloud_history, R.string.whats_new_fix_source_picker, R.string.whats_new_fix_about),
        ),
        WhatsNewRelease(
            "3.4.0",
            features = listOf(
                WhatsNewFeature(Icons.Filled.Description, R.string.whats_new_paper_title, R.string.whats_new_paper_body,
                    Routes.SETTINGS_APPEARANCE_THEMES) { PaperThemePreview() },
                // Android only: desktop has no launcher icon to switch
                WhatsNewFeature(Icons.Filled.Apps, R.string.whats_new_paper_icon_title, R.string.whats_new_paper_icon_body,
                    Routes.SETTINGS_APPEARANCE_THEMES) { PaperIconPreview() },
                WhatsNewFeature(Icons.Filled.AllInclusive, R.string.whats_new_autoplay_title, R.string.whats_new_autoplay_body,
                    Routes.SETTINGS_PLAYBACK) { AutoplayPreview() },
            ),
        ),
        WhatsNewRelease(
            "3.3.1",
            features = listOf(
                WhatsNewFeature(Icons.Filled.Search, R.string.whats_new_search_title, R.string.whats_new_search_body,
                    Routes.SETTINGS) { SettingsSearchPreview() },
                WhatsNewFeature(Icons.Filled.FilterAlt, R.string.whats_new_sort_title, R.string.whats_new_sort_body,
                    Routes.LIBRARY) { TrackSortPreview() },
                WhatsNewFeature(Icons.Filled.Layers, R.string.whats_new_tabs_title, R.string.whats_new_tabs_body,
                    Routes.LIBRARY) { LibraryTabsPreview() },
                WhatsNewFeature(Icons.Filled.Radio, R.string.whats_new_dj_title, R.string.whats_new_dj_body),
                WhatsNewFeature(Icons.Filled.Download, R.string.whats_new_downloads_title, R.string.whats_new_downloads_body,
                    Routes.LIBRARY),
            ),
            fixes = listOf(R.string.whats_new_fix_hours, R.string.whats_new_fix_spoken, R.string.whats_new_fix_genres,
                R.string.whats_new_fix_update_toast),
        ),
    )
}

/** Lets the settings page replay a release's tour; AuroraApp listens. */
object WhatsNewLauncher {
    private val requests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val opened: SharedFlow<String> = requests
    fun open(version: String) { requests.tryEmit(version) }
}
