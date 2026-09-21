package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import com.aurora.music.R
import kotlinx.coroutines.launch

@Composable
internal fun ArtworkLookupSettings() {
    val container = (LocalContext.current.applicationContext as com.aurora.music.AuroraApplication).container
    val enabled by container.settingsStore.artworkLookupEnabled.collectAsStateWithLifecycle(initialValue = true)
    val bytes by container.artworkRepository.bytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SettingsSectionTitle(stringResource(R.string.artwork_title))
    SettingsGroup {
        SettingsSwitchRow(Icons.Outlined.Image, stringResource(R.string.artwork_lookup),
            stringResource(R.string.artwork_description), enabled) {
            scope.launch { container.settingsStore.setArtworkLookupEnabled(it) }
        }
        SettingsRowDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.artwork_provider), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.artwork_cache_usage, bytes / (1024.0 * 1024)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
            TextButton(enabled = !clearing, onClick = {
                clearing = true
                failed = false
                scope.launch {
                    failed = runCatching {
                        container.artworkRepository.clear()
                        context.imageLoader.memoryCache?.clear()
                    }.isFailure
                    clearing = false
                }
            }) { Text(stringResource(R.string.artwork_clear)) }
            if (failed) Text(stringResource(R.string.artwork_clear_failed), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
