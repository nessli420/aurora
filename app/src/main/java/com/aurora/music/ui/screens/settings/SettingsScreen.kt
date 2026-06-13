package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    username: String,
    server: String,
    onBack: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenGestures: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenBackup: () -> Unit,
    onLogout: () -> Unit,
) {
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.aurora.music.AuroraApplication).container
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val serverBadge = when (session?.type) {
        com.aurora.music.data.ServerType.SPOTIFY -> "SPOTIFY"
        com.aurora.music.data.ServerType.JELLYFIN -> "JELLYFIN"
        com.aurora.music.data.ServerType.LOCAL -> "LOCAL"
        else -> "NAVIDROME"
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Settings", onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onOpenProfile).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!session?.imageUrl.isNullOrBlank()) {
                            com.aurora.music.ui.components.Artwork(session!!.imageUrl, MaterialTheme.colorScheme.primary, Modifier.matchParentSize(), corner = 28.dp)
                        } else {
                            Text(username.take(2).uppercase().ifBlank { "ME" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(username.ifBlank { "Listener" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("View profile", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(serverBadge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            item { SettingsSectionTitle("Account") }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.SwitchAccount, "Servers & accounts", "Switch between saved logins", onClick = onOpenAccounts)
                }
            }

            item { SettingsSectionTitle("Audio") }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.PlayCircle, "Playback & quality", "Streaming quality, hi-res, crossfade, gapless", onClick = onOpenPlayback)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Tune, "Equalizer & effects", "EQ, AutoEQ, convolution, DSP", onClick = onOpenEq)
                }
            }

            item { SettingsSectionTitle("Library") }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Download, "Downloads & storage", "${downloads.size} downloaded · quality, offline", onClick = onOpenDownloads)
                }
            }

            item { SettingsSectionTitle("Interface") }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Palette, "Appearance", "Theme, accent, layout", onClick = onOpenAppearance)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.TouchApp, "Gestures & behaviour", "Swipe, haptics, private session", onClick = onOpenGestures)
                }
            }

            item { SettingsSectionTitle("Connections") }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Extension, "Integrations", "Last.fm, ListenBrainz, Discord, lyrics", onClick = onOpenIntegrations)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Info, "About Aurora", value = "v1.0", onClick = onOpenAbout)
                }
            }

            item { SettingsSectionTitle("Data") }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Backup, "Backup & restore", "Export or import your settings & playlists", onClick = onOpenBackup)
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onLogout).padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Log out", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
