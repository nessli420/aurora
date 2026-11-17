package com.aurora.music.ui.screens.settings

import androidx.compose.material.icons.filled.CallMerge
import com.aurora.music.ui.theme.paperClip
import androidx.compose.material.icons.filled.Lyrics
import com.aurora.music.localization.appString

import com.aurora.music.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.pressClickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Insights
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
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
import com.aurora.music.data.SignalPath
import com.aurora.music.desktop.platform.BuildInfo
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.desktop.ui.LocalTrayAvailable
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.localizedSignalLabel
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.components.Artwork
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    onOpenSonic: (() -> Unit)?,
    onOpenSources: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLyricsSettings: () -> Unit = {},
    onOpenMerge: () -> Unit = {},
    onOpenLanguage: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenConnect: () -> Unit = {},
    onOpenAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenBackup: (() -> Unit)? = null,
    onOpenInsights: (() -> Unit)? = null,
    onLogout: () -> Unit,
    onReplayTour: (() -> Unit)? = null,
    onOpenRoute: (String) -> Unit = {},
) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val searchHits = rememberSettingsSearchHits()
    val container = LocalDesktopContainer.current
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    val simpleMode by container.settingsStore.simpleMode.collectAsStateWithLifecycle(initialValue = false)
    val closeToTray by container.desktopSettings.closeToTray.collectAsStateWithLifecycle(initialValue = com.aurora.music.desktop.platform.HostPlatform.isMac)
    val language by AppStrings.languageTag.collectAsState()
    val scope = rememberCoroutineScope()
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val player = LocalPlayer.current
    val signalSummary by remember(player, language) { player.signalPath.map { it.settingsSummary() }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = player.signalPath.value.settingsSummary())
    val serverBadge = when (session?.type) {
        com.aurora.music.data.ServerType.SPOTIFY -> "SPOTIFY"
        com.aurora.music.data.ServerType.YOUTUBE_MUSIC -> "YOUTUBE MUSIC"
        com.aurora.music.data.ServerType.SOUNDCLOUD -> "SOUNDCLOUD"
        com.aurora.music.data.ServerType.APPLE_MUSIC -> "APPLE MUSIC"
        com.aurora.music.data.ServerType.JELLYFIN -> "JELLYFIN"
        com.aurora.music.data.ServerType.PLEX -> "PLEX"
        com.aurora.music.data.ServerType.LOCAL -> appString(R.string.text_local_9be340)
        com.aurora.music.data.ServerType.EXTENSION -> appString(R.string.text_extension_f88c4d)
        else -> "NAVIDROME"
    }

    CompositionLocalProvider(LocalSettingsListPane provides (LocalSelectedSettingsRoute.current != null)) {
        Column(Modifier.fillMaxWidth()) {
            SettingsTopBar(
                appString(R.string.text_settings_c7f73b), onBack,
                showBack = !com.aurora.music.ui.layout.LocalWindowLayout.current.useNavigationRail,
            )
            SettingsSearchField(query, onQuery = { query = it })
            SettingsList(contentPadding) {
                if (query.isNotBlank()) {
                    item {
                        SettingsSearchResults(query, searchHits) { hit ->
                            when {
                                hit.route != null -> onOpenRoute(hit.route)
                                hit.titleRes == R.string.onboarding_replay_title && onReplayTour != null -> onReplayTour()
                                // the rest live on this page, so show it
                                else -> query = ""
                            }
                        }
                    }
                    return@SettingsList
                }
                item { SettingsSectionTitle(appString(R.string.text_library_accounts_e097be)) }
                item {
                    SettingsGroup {
                        AccountRow(username, avatarUrl, serverBadge, onOpenProfile)
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.SwitchAccount, SettingsDestinations.accounts, onClick = onOpenAccounts)
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.MergeType, SettingsDestinations.sources, onClick = onOpenSources)
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.CallMerge, SettingsDestinations.merge, onClick = onOpenMerge)
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.Download, SettingsDestinations.storage, appString(R.string.text_downloaded_quality_and_offline_files_4bd602, (downloads.size)), onClick = onOpenDownloads)
                        if (onOpenSonic != null) {
                            SettingsRowDivider()
                            SettingsDestinationRow(Icons.Filled.AutoAwesome, SettingsDestinations.analysis, onClick = onOpenSonic)
                        }
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
                            enter = expandVertically(animationSpec = Motion.enter()) + fadeIn(animationSpec = Motion.enter()),
                            exit = shrinkVertically(animationSpec = Motion.exit()) + fadeOut(animationSpec = Motion.exit()),
                        ) {
                            Column {
                                SettingsRowDivider()
                                SettingsDestinationRow(Icons.Filled.VolumeUp, SettingsDestinations.loudness, onClick = onOpenLoudness)
                                SettingsRowDivider()
                                SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.advancedAudio, onClick = onOpenAdvancedAudio)
                                SettingsRowDivider()
                                SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath, signalSummary, onClick = onOpenSignalPath)
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
                                "tr" -> "Türkçe"
                                "es" -> "Español"
                                "fr" -> "Français"
                                "zh" -> "简体中文"
                                "pt" -> "Português"
                                "hi" -> "हिन्दी"
                                "ur" -> "اردو"
                                "de" -> "Deutsch"
                                "it" -> "Italiano"
                                "nl" -> "Nederlands"
                                "pl" -> "Polski"
                                "id", "in" -> "Bahasa Indonesia"
                                "ja" -> "日本語"
                                "ko" -> "한국어"
                                "ar" -> "العربية"
                                "en" -> "English"
                                else -> appString(R.string.language_system)
                            }, selected = LocalSelectedSettingsRoute.current == Routes.SETTINGS_LANGUAGE, onClick = onOpenLanguage)
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.Palette, SettingsDestinations.appearance, onClick = onOpenAppearance)
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.Lyrics, SettingsDestinations.lyrics, onClick = onOpenLyricsSettings)
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.GraphicEq, SettingsDestinations.visualizer, onClick = onOpenVisualizer)
                    }
                }

                item { SettingsSectionTitle(appString(R.string.text_connections_8f3509)) }
                item {
                    SettingsGroup {
                        SettingsDestinationRow(Icons.Filled.Devices, SettingsDestinations.connect, onClick = onOpenConnect)
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
                        if (LocalTrayAvailable.current) {
                            SettingsRowDivider()
                            val mac = com.aurora.music.desktop.platform.HostPlatform.isMac
                            SettingsSwitchRow(Icons.Filled.Minimize,
                                appString(if (mac) R.string.text_close_to_menu_bar_mac else R.string.text_close_to_tray_f91cc6),
                                appString(if (mac) R.string.text_close_to_menu_bar_detail_mac else R.string.text_keep_aurora_playing_in_the_notification_area_when_the_window_clos_e5fed5), closeToTray) { enabled ->
                                scope.launch { container.desktopSettings.setCloseToTray(enabled) }
                            }
                        }
                        if (onReplayTour != null) {
                            SettingsRowDivider()
                            SettingsNavRow(Icons.Filled.PlayCircle, appString(R.string.onboarding_replay_title),
                                subtitle = appString(R.string.onboarding_replay_body), onClick = onReplayTour)
                        }
                        if (onOpenInsights != null) {
                            SettingsRowDivider()
                            val insights by LocalDesktopContainer.current.settingsStore.insightsPrefs.collectAsStateWithLifecycle(com.aurora.music.data.InsightsPrefs())
                            SettingsDestinationRow(Icons.Filled.Insights, SettingsDestinations.insights,
                                if (insights.enabled) appString(R.string.text_on_e0049a) else SettingsDestinations.insights.description, onClick = onOpenInsights)
                        }
                        if (onOpenBackup != null) {
                            SettingsRowDivider()
                            SettingsDestinationRow(Icons.Filled.Backup, SettingsDestinations.backup, onClick = onOpenBackup)
                        }
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.NewReleases, SettingsDestinations.whatsNew,
                            onClick = { onOpenRoute(Routes.SETTINGS_WHATS_NEW) })
                        SettingsRowDivider()
                        SettingsDestinationRow(Icons.Filled.Info, SettingsDestinations.about,
                            appString(R.string.text_version_d2f210, BuildInfo.VERSION_NAME), onClick = onOpenAbout)
                    }
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).paperClip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .pointerHoverIcon(PointerIcon.Hand).pressClickable(onClick = onLogout).padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(appString(R.string.text_log_out_6e78c9), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(username: String, avatarUrl: String, badge: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val size = if (LocalSettingsListPane.current) 34.dp else 38.dp
    SettingsRowScaffold(
        leading = {
            Box(
                Modifier.size(size).paperClip(CircleShape).background(Brush.linearGradient(listOf(colors.primary, colors.tertiary))),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl.isNotBlank()) Artwork(avatarUrl, colors.primary, Modifier.matchParentSize(), corner = size / 2)
                else Text(username.take(2).uppercase().ifBlank { appString(R.string.text_me_b4d362) }, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black, color = colors.onPrimary)
            }
        },
        title = username.ifBlank { appString(R.string.text_listener_37ea46) },
        subtitle = appString(R.string.text_view_profile_b98795),
        subtitleColor = colors.primary,
        onClick = onClick,
    ) {
        Box(Modifier.padding(start = 8.dp).paperClip(RoundedCornerShape(50)).background(colors.primary.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = colors.primary, maxLines = 1)
        }
    }
}

// stored values land just after a page opens, so nothing animates until it has settled
@Composable
internal fun rememberSettled(): Boolean {
    var settled by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(com.aurora.music.util.MotionTokens.STANDARD.toLong()); settled = true }
    return settled
}

// a block a switch or choice reveals: grows and fades in, leaves faster
@Composable
internal fun SettingsReveal(visible: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val settled = rememberSettled()
    AnimatedVisibility(
        visible = visible,
        enter = if (settled) expandVertically(animationSpec = Motion.enter()) + fadeIn(animationSpec = Motion.enter()) else EnterTransition.None,
        exit = if (settled) shrinkVertically(animationSpec = Motion.exit()) + fadeOut(animationSpec = Motion.exit()) else ExitTransition.None,
    ) { Column(content = content) }
}

// the switch of the shared rows, for rows that lay themselves out
@Composable
internal fun SettingsToggle(checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    com.aurora.music.ui.theme.Switch(checked, onCheckedChange, if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier, enabled)
}

private fun SignalPath.settingsSummary(): String = if (!active) appString(R.string.text_nothing_playing_13ae37) else buildList {
    add(output.localizedSignalLabel().ifBlank { appString(R.string.text_output_unknown_ef4fdb) })
    if (codec.isNotBlank()) add(codec)
    if (sampleRateHz > 0) add(appString(R.string.text_1f_khz_source_51278e, sampleRateHz / 1000f))
}.joinToString(" · ")
