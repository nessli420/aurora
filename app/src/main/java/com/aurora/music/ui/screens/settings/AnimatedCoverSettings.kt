package com.aurora.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.AnimatedCovers
import kotlinx.coroutines.launch

@Composable
internal fun AnimatedCoverSettings() {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val covers = container.animatedCovers
    val config by covers.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { runCatching { covers.setFolder(uri) } }
    }
    SettingsSectionTitle(stringResource(R.string.animated_covers_title))
    SettingsGroup {
        SettingsSwitchRow(Icons.Outlined.Animation, stringResource(R.string.animated_covers_toggle),
            stringResource(R.string.animated_covers_toggle_desc), config.enabled) {
            scope.launch { container.settingsStore.setAnimatedCoversEnabled(it) }
        }
        SettingsRowDivider()
        SettingsNavRow(Icons.Outlined.FolderOpen, stringResource(R.string.animated_covers_folder),
            value = if (config.folder.isBlank()) stringResource(R.string.animated_covers_folder_none) else AnimatedCovers.label(config.folder)) {
            pick.launch(null)
        }
        SettingsRowDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.animated_covers_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (config.folder.isNotBlank()) TextButton(onClick = { scope.launch { runCatching { covers.setFolder(null) } } }) {
                Text(stringResource(R.string.animated_covers_remove))
            }
        }
    }
}
