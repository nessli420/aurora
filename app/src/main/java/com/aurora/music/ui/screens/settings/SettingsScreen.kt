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
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
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
    onOpenOutput: () -> Unit,
    onOpenLoudness: () -> Unit,
    onOpenAlarm: () -> Unit,
    onOpenSignalPath: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenProcessingRack: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenImpulses: () -> Unit,
    onOpenVisualizer: () -> Unit,
    onOpenSonic: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenGestures: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenBackup: () -> Unit,
    onLogout: () -> Unit,
) {
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.aurora.music.AuroraApplication).container
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val signalPath by container.signalPath.collectAsStateWithLifecycle()
    val processingRack by container.settingsStore.processingRack.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.ProcessingRack())
    val alarmSummary = alarmSettingsSummary()
    val signalSummary = if (!signalPath.active) "Nothing playing" else buildList {
        add(signalPath.output.ifBlank { "Output unknown" })
        if (signalPath.codec.isNotBlank()) add(signalPath.codec)
        if (signalPath.sampleRateHz > 0) add("%.1f kHz source".format(signalPath.sampleRateHz / 1000f))
    }.joinToString(" · ")
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

            item { SettingsSectionTitle("Library & accounts") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.SwitchAccount, SettingsDestinations.accounts, onClick = onOpenAccounts)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.MergeType, SettingsDestinations.sources, onClick = onOpenSources)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Download, SettingsDestinations.storage, "${downloads.size} downloaded · quality and offline files", onClick = onOpenDownloads)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.AutoAwesome, SettingsDestinations.analysis, onClick = onOpenSonic)
                }
            }

            item { SettingsSectionTitle("Audio") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.PlayCircle, SettingsDestinations.playback, onClick = onOpenPlayback)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Devices, SettingsDestinations.output, onClick = onOpenOutput)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.equalizer, onClick = onOpenEq)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.processingRack,
                        if (processingRack.enabled) "${processingRack.name} · ${processingRack.nodes.size} stages"
                        else "Standard processing · arrange a custom chain", onClick = onOpenProcessingRack)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.ShowChart, SettingsDestinations.tuning, onClick = onOpenTuning)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.GraphicEq, SettingsDestinations.impulses, onClick = onOpenImpulses)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.VolumeUp, SettingsDestinations.loudness, onClick = onOpenLoudness)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath, signalSummary, onClick = onOpenSignalPath)
                }
            }

            item { SettingsSectionTitle("Timers") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Alarm, SettingsDestinations.alarm, alarmSummary, onClick = onOpenAlarm)
                }
            }

            item { SettingsSectionTitle("Appearance & controls") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Palette, SettingsDestinations.appearance, onClick = onOpenAppearance)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.GraphicEq, SettingsDestinations.visualizer, onClick = onOpenVisualizer)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.TouchApp, SettingsDestinations.gestures, onClick = onOpenGestures)
                }
            }

            item { SettingsSectionTitle("Connections") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Extension, SettingsDestinations.integrations, onClick = onOpenIntegrations)
                }
            }

            item { SettingsSectionTitle("App & data") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Lock, SettingsDestinations.permissions, onClick = onOpenPermissions)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Backup, SettingsDestinations.backup, onClick = onOpenBackup)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Info, SettingsDestinations.about, onClick = onOpenAbout)
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
