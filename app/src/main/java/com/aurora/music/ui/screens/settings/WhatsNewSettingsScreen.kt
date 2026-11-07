package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.BuildConfig
import com.aurora.music.R
import com.aurora.music.data.WhatsNew
import com.aurora.music.localization.appString
import com.aurora.music.ui.whatsnew.WhatsNewCatalog
import com.aurora.music.ui.whatsnew.WhatsNewLauncher
import kotlinx.coroutines.launch

@Composable
fun WhatsNewSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val enabled by container.settingsStore.whatsNewEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    val releases = WhatsNew.released(WhatsNewCatalog.releases, BuildConfig.VERSION_NAME) { it.version }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.whatsNew.label, onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.NewReleases, appString(R.string.whats_new_show_setting),
                        appString(R.string.whats_new_show_setting_detail), enabled) { on ->
                        scope.launch { container.settingsStore.setWhatsNewEnabled(on) }
                    }
                }
            }
            item { SettingsSectionTitle(appString(R.string.whats_new_past)) }
            item {
                SettingsGroup {
                    if (releases.isEmpty()) Text(appString(R.string.whats_new_past_empty), Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    releases.forEachIndexed { i, release ->
                        if (i > 0) SettingsRowDivider()
                        SettingsNavRow(Icons.Filled.History, appString(R.string.text_version_d2f210, WhatsNew.shortVersion(release.version)),
                            subtitle = release.features.joinToString(" · ") { appString(it.title) },
                            value = if (i == 0) appString(R.string.whats_new_current) else null) {
                            WhatsNewLauncher.open(release.version)
                        }
                    }
                }
            }
        }
    }
}
