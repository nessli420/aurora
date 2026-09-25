package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
    var showFontLicenses by remember { mutableStateOf(false) }
    if (showDstLicense) {
        val license = remember { context.assets.open("licenses/aurora-dst-LGPL-2.1.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showDstLicense = false }, title = { Text(appString(R.string.text_dst_decoder_license_1e3fba)) },
            text = { Text("FFmpeg / DSD-Nexus. Copyright © 2014 Peter Ross. LGPL-2.1-or-later.\n\n$license",
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showDstLicense = false }) { Text(appString(R.string.text_close_bbfa77)) } },
            dismissButton = { TextButton(onClick = {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/nessli420/aurora/tree/main/app/src/main/cpp/dst")))
            }) { Text(appString(R.string.text_source_6da13a)) } })
    }
    if (showFontLicenses) {
        val licenses = remember {
            listOf(
                "DM Sans" to "dmsans-OFL.txt",
                "Plus Jakarta Sans" to "plusjakartasans-OFL.txt",
                "Manrope" to "manrope-OFL.txt",
            ).map { (name, file) ->
                name to context.assets.open("font_licenses/$file").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(
            onDismissRequest = { showFontLicenses = false },
            title = { Text(appString(R.string.font_licenses_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    licenses.forEach { (name, license) ->
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(license, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontLicenses = false }) { Text(appString(R.string.text_close_bbfa77)) }
            },
        )
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_about_aurora_b4ed8c), onBack)
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
            Text(appString(R.string.text_aurora_eeee9b), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(appString(R.string.text_version_d2f210, (BuildConfig.VERSION_NAME)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(appString(R.string.text_build_007156, (BuildConfig.VERSION_CODE), (if (BuildConfig.DEBUG) " · Debug" else "")),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            AppUpdateCard(container.appUpdater)
            Spacer(Modifier.height(16.dp))
            val source = when (session?.type) {
                ServerType.JELLYFIN -> "Jellyfin"
                ServerType.PLEX -> "Plex"
                ServerType.SUBSONIC -> "Subsonic / OpenSubsonic"
                ServerType.SPOTIFY -> "Spotify"
                ServerType.YOUTUBE_MUSIC -> "YouTube Music"
                ServerType.LOCAL -> appString(R.string.text_on_this_device_a7f962)
                ServerType.EXTENSION -> appString(R.string.text_extension_659087)
                null -> "—"
            }
            InfoRow(appString(R.string.text_music_source_cb3c75), source)
            if (session != null && session?.type != ServerType.LOCAL) {
                InfoRow(appString(R.string.text_signed_in_as_a02107), session?.username ?: "—")
            }
            InfoRow(appString(R.string.text_playback_engine_0255b8), "AndroidX Media3 (ExoPlayer)")
            TextButton(onClick = { showDstLicense = true }) { Text(appString(R.string.text_dst_decoder_license_1e3fba)) }
            TextButton(onClick = { showFontLicenses = true }) { Text(appString(R.string.font_licenses_title)) }

            Spacer(Modifier.height(20.dp))
            Text(
                appString(R.string.text_built_with_jetpack_compose_material_3_6a2228),
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
