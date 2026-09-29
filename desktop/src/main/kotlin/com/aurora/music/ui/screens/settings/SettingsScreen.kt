package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString

import com.aurora.music.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.desktop.platform.BuildInfo
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.AppStrings
import com.aurora.music.navigation.Routes
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    username: String,
    server: String,
    avatarUrl: String = "",
    onBack: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenLoudness: () -> Unit,
    onOpenAdvancedAudio: () -> Unit,
    onOpenSignalPath: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenVisualizer: () -> Unit,
    onOpenSonic: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenBackup: (() -> Unit)? = null,
    onLogout: () -> Unit,
    onReplayTour: (() -> Unit)? = null,
) {
    val container = LocalDesktopContainer.current
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    val simpleMode by container.settingsStore.simpleMode.collectAsStateWithLifecycle(initialValue = false)
    val closeToTray by container.desktopSettings.closeToTray.collectAsStateWithLifecycle(initialValue = false)
    val language by AppStrings.languageTag.collectAsState()
    val scope = rememberCoroutineScope()
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val serverBadge = when (session?.type) {
        com.aurora.music.data.ServerType.SPOTIFY -> "SPOTIFY"
        com.aurora.music.data.ServerType.YOUTUBE_MUSIC -> "YOUTUBE MUSIC"
        com.aurora.music.data.ServerType.JELLYFIN -> "JELLYFIN"
        com.aurora.music.data.ServerType.PLEX -> "PLEX"
        com.aurora.music.data.ServerType.LOCAL -> appString(R.string.text_local_9be340)
        com.aurora.music.data.ServerType.EXTENSION -> appString(R.string.text_extension_f88c4d)
        else -> "NAVIDROME"
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(
            appString(R.string.text_settings_c7f73b), onBack,
            showBack = !com.aurora.music.ui.layout.LocalWindowLayout.current.useNavigationRail,
        )
        SettingsList(contentPadding) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onOpenProfile).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarUrl.isNotBlank()) {
                            com.aurora.music.ui.components.Artwork(avatarUrl, MaterialTheme.colorScheme.primary, Modifier.matchParentSize(), corner = 28.dp)
                        } else {
                            Text(username.take(2).uppercase().ifBlank { appString(R.string.text_me_b4d362) }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(username.ifBlank { appString(R.string.text_listener_37ea46) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(appString(R.string.text_view_profile_b98795), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(serverBadge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_library_accounts_e097be)) }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.SwitchAccount, SettingsDestinations.accounts, onClick = onOpenAccounts)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.MergeType, SettingsDestinations.sources, onClick = onOpenSources)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Download, SettingsDestinations.storage, appString(R.string.text_downloaded_quality_and_offline_files_4bd602, (downloads.size)), onClick = onOpenDownloads)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.AutoAwesome, SettingsDestinations.analysis, onClick = onOpenSonic)
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_audio_acdac2)) }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.PlayCircle, SettingsDestinations.playback, onClick = onOpenPlayback)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Devices, SettingsDestinations.output, OUTPUT_SUMMARY, onClick = onOpenOutput)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.equalizer, onClick = onOpenEq)
                    AnimatedVisibility(
                        visible = !simpleMode,
                        enter = expandVertically(animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
                        exit = shrinkVertically(animationSpec = tween(260)) + fadeOut(animationSpec = tween(260)),
                    ) {
                        Column {
                            SettingsRowDivider()
                            SettingsDestinationRow(Icons.Filled.VolumeUp, SettingsDestinations.loudness, onClick = onOpenLoudness)
                            SettingsRowDivider()
                            SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.advancedAudio, onClick = onOpenAdvancedAudio)
                            SettingsRowDivider()
                            SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath, onClick = onOpenSignalPath)
                        }
                    }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_appearance_controls_76f34d)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Language, appString(R.string.language_title),
                        subtitle = when (language.substringBefore('-')) {
                            "ru" -> "Русский"
                            "en" -> "English"
                            else -> appString(R.string.language_system)
                        }, selected = LocalSelectedSettingsRoute.current == Routes.SETTINGS_LANGUAGE, onClick = onOpenLanguage)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Palette, SettingsDestinations.appearance, onClick = onOpenAppearance)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.GraphicEq, SettingsDestinations.visualizer, onClick = onOpenVisualizer)
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_connections_8f3509)) }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Extension, SettingsDestinations.integrations, onClick = onOpenIntegrations)
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_app_data_4d9bf9)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.CheckCircle, appString(R.string.simple_mode),
                        appString(R.string.simple_mode_description), simpleMode) { enabled ->
                        scope.launch { container.settingsStore.setSimpleMode(enabled) }
                    }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Minimize, "Close to tray",
                        "Keep Aurora playing in the notification area when the window closes", closeToTray) { enabled ->
                        scope.launch { container.desktopSettings.setCloseToTray(enabled) }
                    }
                    if (onReplayTour != null) {
                        SettingsRowDivider()
                        SettingsNavRow(Icons.Filled.PlayCircle, appString(R.string.onboarding_replay_title),
                            subtitle = appString(R.string.onboarding_replay_body), onClick = onReplayTour)
                    }
                    if (onOpenBackup != null) {
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.Backup, SettingsDestinations.backup, onClick = onOpenBackup)
                    }
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Info, SettingsDestinations.about,
                        appString(R.string.text_version_d2f210, BuildInfo.VERSION_NAME), onClick = onOpenAbout)
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onLogout).padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(appString(R.string.text_log_out_6e78c9), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
