package com.aurora.music.ui.whatsnew

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
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
            "3.4.0",
            features = listOf(
                WhatsNewFeature(Icons.Filled.Description, R.string.whats_new_paper_title, R.string.whats_new_paper_body,
                    Routes.SETTINGS_APPEARANCE) { PaperThemePreview() },
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
