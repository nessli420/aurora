package com.aurora.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.updates.AppUpdateState
import com.aurora.music.data.updates.AppUpdater
import com.aurora.music.data.updates.GitHubRelease
import com.aurora.music.data.updates.UpdateDownload

@Composable
fun AppUpdateCard(updater: AppUpdater) {
    val context = LocalContext.current
    val state by updater.state.collectAsStateWithLifecycle()
    var permissionNeeded by rememberSaveable { mutableStateOf(false) }
    var installAfterPermission by rememberSaveable { mutableStateOf(false) }
    val install = {
        try {
            updater.installerIntent()?.let(context::startActivity)
        } catch (_: Exception) {
            updater.reportInstallError()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionNeeded = !context.packageManager.canRequestPackageInstalls()
        installAfterPermission = !permissionNeeded
    }
    LaunchedEffect(installAfterPermission, state.download) {
        if (installAfterPermission && state.download == UpdateDownload.READY) {
            installAfterPermission = false
            install()
        } else if (state.download == UpdateDownload.IDLE) {
            installAfterPermission = false
        }
    }
    LaunchedEffect(updater) { updater.checkForUpdate() }
    AppUpdateCardContent(
        state = state,
        permissionNeeded = permissionNeeded,
        onCheck = { updater.checkForUpdate(force = true) },
        onDownload = updater::downloadUpdate,
        onCancel = updater::cancelDownload,
        onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) {
                permissionNeeded = false
                install()
            } else {
                permissionNeeded = true
                try {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) {
                    updater.reportInstallError()
                }
            }
        },
        onReleaseNotes = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.release?.pageUrl ?: GitHubRelease.RELEASES_URL)))
            } catch (_: Exception) {
                Toast.makeText(context, "No browser available to open GitHub.", Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@Composable
internal fun AppUpdateCardContent(
    state: AppUpdateState,
    permissionNeeded: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onReleaseNotes: () -> Unit,
) {
    val title = when {
        state.download == UpdateDownload.READY -> "Ready to install"
        state.download == UpdateDownload.VERIFYING -> "Preparing update"
        state.download == UpdateDownload.DOWNLOADING -> "Downloading update"
        state.checking -> "Checking for updates"
        state.updateAvailable -> "Update available"
        state.error != null -> "Couldn't check for updates"
        state.checked -> "You're up to date"
        else -> "App updates"
    }
    val detail = when {
        state.download == UpdateDownload.READY && permissionNeeded -> "Allow Aurora to install updates, then return here."
        state.download == UpdateDownload.READY -> "${state.release?.tag} · Your music and settings stay in place."
        state.download == UpdateDownload.VERIFYING -> "Verifying the download…"
        state.download == UpdateDownload.DOWNLOADING && state.waitingForNetwork -> "Waiting for a connection…"
        state.download == UpdateDownload.DOWNLOADING -> state.progress?.let { "${(it * 100).toInt()}% downloaded" } ?: "Starting download…"
        state.checking -> "Looking for the latest release on GitHub…"
        state.updateAvailable && state.release?.apk == null -> "${state.release?.tag} is available. See GitHub for downloads."
        state.updateAvailable -> "${state.release?.tag} is available to download."
        state.checked -> "Latest release on GitHub: ${state.release?.tag}"
        else -> "Get the latest version from GitHub."
    }
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (state.updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                UpdateDownload.DOWNLOADING -> TextButton(onClick = onCancel) { Text("Cancel download") }
                UpdateDownload.VERIFYING -> Unit
                UpdateDownload.READY -> {
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install update") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onReleaseNotes) { Text("Release notes") }
                        TextButton(onClick = onCancel) { Text("Discard") }
                    }
                }
                UpdateDownload.IDLE -> {
                    if (state.updateAvailable) {
                        Button(onClick = if (state.release?.apk != null) onDownload else onReleaseNotes,
                            enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.release?.apk != null) "Download update" else "View on GitHub")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onCheck, enabled = !state.checking) { Text("Check for updates") }
                        TextButton(onClick = onReleaseNotes) { Text("Release notes") }
                    }
                }
            }
        }
    }
}
