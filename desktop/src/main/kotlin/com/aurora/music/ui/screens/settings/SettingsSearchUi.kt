package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.SettingsSearch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appString
import com.aurora.music.navigation.Routes

// one place a search can take you: a whole page or a single setting on it
internal data class SettingsSearchHit(val title: String, val subtitle: String, val page: String, val route: String?, val titleRes: Int, val isPage: Boolean)

@Composable
internal fun SettingsSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    // sits outside the settings list, so it takes the list's own side padding to line up with the groups below
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val list = settingsContentPadding(maxWidth, androidx.compose.foundation.layout.PaddingValues())
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        Row(
            Modifier.fillMaxWidth()
                .padding(start = list.calculateStartPadding(direction), end = list.calculateEndPadding(direction))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                // paper frames it in ink like the groups under it
                .paperFrame(RoundedCornerShape(50), fallback = { background(colors.surfaceContainerHigh) })
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                if (query.isEmpty()) Text(appString(R.string.settings_search_hint), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(Icons.Filled.Close, appString(R.string.settings_search_clear), tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { onQuery("") }.padding(10.dp))
            }
        }
    }
}

// every page and setting in the current language, rebuilt when the language changes
@Composable
internal fun rememberSettingsSearchHits(): List<SettingsSearchHit> {
    val locale by AppStrings.languageTag.collectAsState()
    return remember(locale) {
        val pages = SettingsDestinations.all.associate { it.route to it.label } + mapOf(
            Routes.SETTINGS_LANGUAGE to appString(R.string.language_title),
            Routes.SETTINGS_NAV_MENU to appString(R.string.nav_menu_title),
            Routes.SETTINGS_ARTIST_SEPARATORS to appString(R.string.text_artist_separators_4a3dc4),
        )
        val settingsTitle = appString(R.string.text_settings_c7f73b)
        val pageHits = SettingsDestinations.all.map { SettingsSearchHit(it.label, it.description, settingsTitle, it.route, 0, isPage = true) }
        val rowHits = SettingsSearchIndex.rows.map { row ->
            SettingsSearchHit(appString(row.title), row.subtitle?.let { appString(it) }.orEmpty(),
                row.route?.let { pages[it] } ?: settingsTitle, row.route, row.title, isPage = false)
        }
        // a row titled like its own page adds nothing to the page result
        (pageHits + rowHits).distinctBy { "${it.title.lowercase()}|${it.route}" }
    }
}

@Composable
internal fun SettingsSearchResults(query: String, hits: List<SettingsSearchHit>, onOpen: (SettingsSearchHit) -> Unit) {
    val found = remember(query, hits) {
        SettingsSearch.rank(query, hits.map { SettingsSearch.Candidate(it, it.title, it.subtitle, it.page) })
    }
    if (found.isEmpty()) {
        Text(appString(R.string.settings_search_empty, query.trim()), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp))
        return
    }
    SettingsGroup {
        found.forEachIndexed { i, hit ->
            if (i > 0) SettingsRowDivider()
            SettingsSearchRow(if (hit.isPage) Icons.Filled.Settings else Icons.Filled.Tune, hit) { onOpen(hit) }
        }
    }
}

@Composable
private fun SettingsSearchRow(icon: ImageVector, hit: SettingsSearchHit, onClick: () -> Unit) {
    // the page it lives on, then what it does
    val crumb = listOf(hit.page, hit.subtitle).filter { it.isNotBlank() }.joinToString(" › ")
    RowScaffold(icon, hit.title, crumb, onClick) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
