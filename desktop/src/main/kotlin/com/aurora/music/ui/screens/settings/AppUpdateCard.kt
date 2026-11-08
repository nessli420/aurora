package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.updates.GitHubRelease
import com.aurora.music.desktop.updates.DesktopUpdateState
import com.aurora.music.desktop.updates.DesktopUpdater
import com.aurora.music.desktop.updates.UpdateDownload

@Composable
fun AppUpdateCard(updater: DesktopUpdater) {
    val uriHandler = LocalUriHandler.current
    val state by updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(updater) { updater.checkForUpdate() }
    AppUpdateCardContent(
        state = state,
        onCheck = { updater.checkForUpdate(force = true) },
        onDownload = updater::downloadUpdate,
        onCancel = updater::cancelDownload,
        onInstall = updater::install,
        onReleaseNotes = { runCatching { uriHandler.openUri(state.release?.pageUrl ?: GitHubRelease.RELEASES_URL) } },
    )
}

@Composable
internal fun AppUpdateCardContent(
    state: DesktopUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onReleaseNotes: () -> Unit,
) {
    val title = when {
        state.download == UpdateDownload.READY -> appString(R.string.text_ready_to_install_0bd33d)
        state.download == UpdateDownload.VERIFYING -> appString(R.string.text_preparing_update_4a0bb6)
        state.download == UpdateDownload.DOWNLOADING -> appString(R.string.text_downloading_update_b42347)
        state.checking -> appString(R.string.text_checking_for_updates_78948b)
        state.updateAvailable -> appString(R.string.text_update_available_21f186)
        state.error != null -> appString(R.string.text_couldn_t_check_for_updates_1c6953)
        state.checked -> appString(R.string.text_you_re_up_to_date_5fc157)
        else -> appString(R.string.text_app_updates_16213f)
    }
    val detail = when {
        state.download == UpdateDownload.READY -> appString(R.string.text_your_music_and_settings_stay_in_place_f17937, (state.release?.tag))
        state.download == UpdateDownload.VERIFYING -> appString(R.string.text_verifying_the_download_1e833e)
        state.download == UpdateDownload.DOWNLOADING -> state.progress?.let { appString(R.string.text_downloaded_a2ae75, ((it * 100).toInt())) } ?: appString(R.string.text_starting_download_43a6a6)
        state.checking -> appString(R.string.text_looking_for_the_latest_release_on_github_d9c6e9)
        state.updateAvailable && state.installer == null -> appString(R.string.text_is_available_see_github_for_downloads_b3be50, (state.release?.tag))
        state.updateAvailable -> appString(R.string.text_is_available_to_download_f68806, (state.release?.tag))
        state.checked -> appString(R.string.text_latest_release_on_github_a3fe9b, (state.release?.tag))
        else -> appString(R.string.text_get_the_latest_version_from_github_ffde96)
    }
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (state.updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.download == UpdateDownload.READY) Text(appString(R.string.update_desktop_install_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.checking || state.download == UpdateDownload.VERIFYING || state.download == UpdateDownload.DOWNLOADING) {
                val progress = state.progress
                if (state.download == UpdateDownload.DOWNLOADING && progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
            }
            when (state.download) {
                UpdateDownload.DOWNLOADING -> TextButton(onClick = onCancel) { Text(appString(R.string.text_cancel_download_67bb11)) }
                UpdateDownload.VERIFYING -> Unit
                UpdateDownload.READY -> {
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_install_update_8a3695)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onReleaseNotes) { Text(appString(R.string.text_release_notes_cd5af7)) }
                        TextButton(onClick = onCancel) { Text(appString(R.string.text_discard_36fff6)) }
                    }
                }
                UpdateDownload.IDLE -> {
                    if (state.updateAvailable) {
                        Button(onClick = if (state.installer != null) onDownload else onReleaseNotes,
                            enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.installer != null) appString(R.string.text_download_update_870d57) else appString(R.string.text_view_on_github_0c7799))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onCheck, enabled = !state.checking) { Text(appString(R.string.text_check_for_updates_736b90)) }
                        TextButton(onClick = onReleaseNotes) { Text(appString(R.string.text_release_notes_cd5af7)) }
                    }
                }
            }
        }
    }
}
