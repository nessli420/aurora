package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.BuildConfig
import com.aurora.music.data.ServerType

@Composable
fun AboutSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    var showDstLicense by remember { mutableStateOf(false) }
    if (showDstLicense) {
        val license = remember { context.assets.open("licenses/aurora-dst-LGPL-2.1.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showDstLicense = false }, title = { Text("DST decoder license") },
            text = { Text("FFmpeg / DSD-Nexus. Copyright © 2014 Peter Ross. LGPL-2.1-or-later.\n\n$license",
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showDstLicense = false }) { Text("Close") } },
            dismissButton = { TextButton(onClick = {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/nessli420/aurora/tree/main/app/src/main/cpp/dst")))
            }) { Text("Source") } })
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("About Aurora", onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(88.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(14.dp))
            Text("Aurora", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Build ${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) " · Debug" else ""}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            AppUpdateCard(container.appUpdater)
            Spacer(Modifier.height(16.dp))
            val source = when (session?.type) {
                ServerType.JELLYFIN -> "Jellyfin"
                ServerType.SUBSONIC -> "Subsonic / OpenSubsonic"
                ServerType.SPOTIFY -> "Spotify"
                ServerType.YOUTUBE_MUSIC -> "YouTube Music"
                ServerType.LOCAL -> "On this device"
                ServerType.EXTENSION -> "Extension"
                null -> "—"
            }
            InfoRow("Music source", source)
            if (session != null && session?.type != ServerType.LOCAL) {
                InfoRow("Signed in as", session?.username ?: "—")
            }
            InfoRow("Playback engine", "AndroidX Media3 (ExoPlayer)")
            TextButton(onClick = { showDstLicense = true }) { Text("DST decoder license") }

            Spacer(Modifier.height(20.dp))
            Text(
                "Built with Jetpack Compose & Material 3.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
