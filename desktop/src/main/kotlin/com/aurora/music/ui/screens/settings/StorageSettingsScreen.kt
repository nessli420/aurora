package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.localization.appPlural
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.Session
import com.aurora.music.data.StorageUsage
import com.aurora.music.data.qualifiesAsPlay
import com.aurora.music.desktop.ui.LocalDesktopContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private typealias Perform = (String, Int, suspend () -> Boolean) -> Unit
private typealias Ask = (String, String, String, () -> Unit) -> Unit

@OptIn(FlowPreview::class)
@Composable
fun StorageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenYouTubeDownload: () -> Unit = {}) {
    val container = LocalDesktopContainer.current
    val store = container.settingsStore
    val paths = container.paths
    val inventory = container.storage
    val usage by inventory.usage.collectAsStateWithLifecycle()
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val offline by store.offlineMode.collectAsStateWithLifecycle(initialValue = false)
    val prefs by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.PlaybackPrefs())
    val sessions by store.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val isLocal = container.isLocal
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf<StorageConfirm?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val rates = listOf(0, 128, 192, 256, 320)
    val rateLabels = listOf(appString(R.string.text_lossless_f3b36f), "128", "192", "256", "320")

    LaunchedEffect(container) {
        inventory.refresh()
        combine(container.downloadManager.downloads, container.audioCache.status, container.artworkRepository.bytes) { d, c, a -> Triple(d.size, c.bytes, a) }
            .drop(1).debounce(1000).collect { inventory.refresh() }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(4000); notice = null } }

    val perform: Perform = perform@{ key, done, action ->
        if (key in working) return@perform
        working = working + key
        scope.launch {
            val result = withContext(NonCancellable) {
                runCatching { action() }.also { runCatching { inventory.refresh() } }
            }
            working = working - key
            notice = appString(when {
                result.isFailure -> R.string.storage_clear_failed
                result.getOrDefault(true) -> done
                else -> R.string.storage_stems_busy
            })
        }
    }
    val ask: Ask = { title, message, label, action -> confirm = StorageConfirm(title, message, label, action) }
    fun open(dir: File) { scope.launch(Dispatchers.IO) { runCatching { java.awt.Desktop.getDesktop().open(dir.apply { mkdirs() }) } } }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_downloads_storage_f7c580), onBack)
        SettingsScroll(contentPadding) {
            StorageOverview(usage, "caches" in working, notice) {
                ask(appString(R.string.storage_clear_caches_title), appString(R.string.storage_clear_caches_message), appString(R.string.storage_clear)) {
                    perform("caches", R.string.storage_caches_cleared) { inventory.clearCaches() }
                }
            }

            SettingsSectionTitle(appString(R.string.storage_part_downloads))
            SettingsGroup {
                StorageRow(Icons.Filled.DownloadDone, appString(R.string.storage_downloads_title),
                    if (downloads.isEmpty()) appString(R.string.storage_downloads_none)
                    else storageDetail(usage.downloads, appPlural(R.plurals.storage_downloaded_tracks, downloads.size)),
                    appString(R.string.storage_remove_all), enabled = downloads.isNotEmpty(), working = "downloads" in working, destructive = true) {
                    ask(appString(R.string.storage_remove_downloads_title), appString(R.string.storage_remove_downloads_message), appString(R.string.storage_remove_all)) {
                        perform("downloads", R.string.storage_cleared) { inventory.removeDownloads(); true }
                    }
                }
                val sources = downloads.values.groupBy { it.serverId.orEmpty() }
                if (sources.size > 1) sources.entries.sortedByDescending { usage.downloadsByServer[it.key] ?: 0L }.forEach { (serverId, songs) ->
                    val label = sourceLabel(serverId, sessions)
                    SettingsRowDivider()
                    StorageRow(Icons.Filled.Dns, label,
                        storageDetail(usage.downloadsByServer[serverId] ?: 0L, appPlural(R.plurals.storage_downloaded_tracks, songs.size)),
                        appString(R.string.storage_remove), working = "downloads:$serverId" in working, destructive = true) {
                        ask(appString(R.string.storage_remove_source_title, label), appString(R.string.storage_remove_source_message), appString(R.string.storage_remove)) {
                            perform("downloads:$serverId", R.string.storage_cleared) { inventory.removeDownloads(serverId); true }
                        }
                    }
                }
            }
            if (!isLocal) {
                SettingsSectionTitle(appString(R.string.text_download_quality_9d14a9))
                SegmentedRow(appString(R.string.text_bitrate_d153e7), rateLabels, rates.indexOf(prefs.downloadBitrate).coerceAtLeast(0)) { i ->
                    scope.launch { store.setDownloadBitrate(rates[i]) }
                }
            }

            SettingsSectionTitle(appString(R.string.text_offline_e01fa7))
            SettingsGroup {
                SettingsSwitchRow(Icons.Filled.CloudOff, appString(R.string.text_offline_mode_66cf31), appString(R.string.cache_offline_summary), offline) { v ->
                    scope.launch { store.setOfflineMode(v) }
                }
            }

            AudioCacheSettings(container)

            YouTubeDownloadSettings(onOpenYouTubeDownload) { open(container.youTubeDownloadFolder) }

            SettingsSectionTitle(appString(R.string.storage_section_images))
            SettingsGroup {
                val lookup by store.artworkLookupEnabled.collectAsStateWithLifecycle(initialValue = true)
                SettingsSwitchRow(Icons.Filled.Image, appString(R.string.artwork_lookup), appString(R.string.artwork_description), lookup) { v ->
                    scope.launch { store.setArtworkLookupEnabled(v) }
                }
                SettingsRowDivider()
                StorageRow(Icons.Filled.Image, appString(R.string.storage_cover_cache),
                    appString(R.string.storage_cover_cache_detail, storageSize(usage.artwork), storageSize(com.aurora.music.data.artwork.ArtworkRepository.LIMIT_BYTES)),
                    appString(R.string.storage_clear), enabled = usage.artwork > 0, working = "artwork" in working) {
                    perform("artwork", R.string.storage_cleared) { inventory.clearArtwork(); true }
                }
                SettingsRowDivider()
                StorageRow(Icons.Filled.PhotoLibrary, appString(R.string.storage_image_cache),
                    storageDetail(usage.images, appString(R.string.storage_image_cache_detail)),
                    appString(R.string.storage_clear), enabled = usage.images > 0, working = "images" in working) {
                    perform("images", R.string.storage_cleared) { inventory.clearImages(); true }
                }
                if (usage.mapTiles > 0) {
                    SettingsRowDivider()
                    StorageRow(Icons.Filled.Map, appString(R.string.storage_map_tiles),
                        storageDetail(usage.mapTiles, appString(R.string.storage_map_tiles_detail)),
                        appString(R.string.storage_clear), working = "tiles" in working) {
                        perform("tiles", R.string.storage_cleared) { inventory.clearMapTiles(); true }
                    }
                }
            }

            AnalysisSettings(usage, working, perform, ask)

            HistorySettings(usage, working, perform, ask)

            SettingsSectionTitle(appString(R.string.storage_section_other))
            SettingsGroup {
                val artistInfo by store.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
                SettingsSwitchRow(Icons.Filled.Person, appString(R.string.text_artist_information_d5c3fc),
                    appString(R.string.text_look_up_biographies_and_images_9931d4), artistInfo) { v ->
                    scope.launch { store.setArtistEnrichment(v) }
                }
                SettingsRowDivider()
                StorageRow(Icons.Filled.Person, appPlural(R.plurals.storage_artists, container.artistInfoStore.size),
                    storageDetail(usage.artistInfo), appString(R.string.storage_clear),
                    enabled = usage.artistInfo > 0, working = "artists" in working) {
                    ask(appString(R.string.storage_artist_info_clear_title), appString(R.string.storage_artist_info_clear_message), appString(R.string.storage_clear)) {
                        perform("artists", R.string.storage_cleared) { inventory.clearArtistInfo(); true }
                    }
                }
                SettingsRowDivider()
                StorageRow(Icons.Filled.DeleteSweep, appString(R.string.storage_temporary),
                    storageDetail(usage.temporary, appString(R.string.storage_temporary_detail)),
                    appString(R.string.storage_clear), enabled = usage.temporary > 0, working = "temporary" in working) {
                    perform("temporary", R.string.storage_cleared) { inventory.clearTemporary(); true }
                }
            }

            SettingsSectionTitle(appString(R.string.text_folders_19adc4))
            SettingsGroup {
                SettingsNavRow(Icons.Filled.Download, appString(R.string.text_downloads_a862c2), subtitle = paths.downloads.absolutePath,
                    value = storageSize(usage.downloads).takeIf { usage.ready }) { open(paths.downloads) }
                SettingsRowDivider()
                SettingsNavRow(Icons.Filled.Image, appString(R.string.text_cache_50338b), subtitle = paths.cache.absolutePath,
                    value = storageSize(usage.caches).takeIf { usage.ready }) { open(paths.cache) }
                SettingsRowDivider()
                SettingsNavRow(Icons.Filled.Description, appString(R.string.text_logs_126dd3), subtitle = paths.logs.absolutePath,
                    value = storageSize(usage.logs).takeIf { usage.ready }) { open(paths.logs) }
                SettingsRowDivider()
                SettingsNavRow(Icons.Filled.Folder, appString(R.string.text_app_data_4d9bf9), subtitle = paths.roaming.absolutePath) { open(paths.roaming) }
            }
        }
    }
    StorageConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun YouTubeDownloadSettings(onOpen: () -> Unit, onOpenFolder: () -> Unit) {
    val container = LocalDesktopContainer.current
    val scope = rememberCoroutineScope()
    val youTube by container.settingsStore.youTubeDownloadPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.YouTubeDownloadPrefs())
    val formats = com.aurora.music.data.YouTubeDownloadFormat.entries
    val bitrates = com.aurora.music.data.YouTubeDownloadPrefs.bitrates
    SettingsSectionTitle(appString(R.string.youtube_download_settings))
    SettingsGroup {
        SettingsNavRow(Icons.Filled.CloudDownload, appString(R.string.youtube_download_title),
            subtitle = appString(R.string.youtube_download_open_detail), onClick = onOpen)
        SettingsRowDivider()
        SettingsNavRow(Icons.Filled.FolderOpen, appString(R.string.youtube_download_open_folder),
            subtitle = container.youTubeDownloadFolder.absolutePath, onClick = onOpenFolder)
    }
    SegmentedRow(appString(R.string.youtube_download_format), listOf("Opus", "M4A"), formats.indexOf(youTube.format)) { i ->
        scope.launch { container.settingsStore.setYouTubeDownloadFormat(formats[i]) }
    }
    SegmentedRow(appString(R.string.youtube_download_quality),
        bitrates.map { if (it == 0) appString(R.string.youtube_download_quality_best) else it.toString() },
        bitrates.indexOf(youTube.maxBitrateKbps).coerceAtLeast(0)) { i ->
        scope.launch { container.settingsStore.setYouTubeDownloadBitrate(bitrates[i]) }
    }
    Text(
        appString(R.string.youtube_download_quality_note) + "\n" + appString(R.string.storage_youtube_desktop_note),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.Image, appString(R.string.youtube_download_artwork),
            appString(R.string.youtube_download_artwork_detail), youTube.embedArtwork) { v ->
            scope.launch { container.settingsStore.setYouTubeDownloadArtwork(v) }
        }
    }
}

@Composable
private fun AiDjModelRow(
    store: com.aurora.music.ai.DjModelStore,
    title: String,
    key: String,
    bytes: Long,
    missing: String,
    working: Set<String>,
    perform: Perform,
    ask: Ask,
    onDownload: () -> Unit,
    onDelete: suspend () -> Unit,
) {
    val model by store.state.collectAsStateWithLifecycle()
    when {
        model == com.aurora.music.ai.DjModelState.Ready -> {
            val delete = appString(R.string.storage_delete)
            StorageRow(Icons.Filled.AutoAwesome, title, storageDetail(bytes, store.spec.name), delete,
                working = key in working, destructive = true) {
                ask(appString(R.string.storage_model_delete_title), appString(R.string.storage_model_delete_message), delete) {
                    perform(key, R.string.storage_cleared) { onDelete(); true }
                }
            }
        }
        model is com.aurora.music.ai.DjModelState.Downloading -> {
            val d = model as com.aurora.music.ai.DjModelState.Downloading
            StorageRow(Icons.Filled.AutoAwesome, title, appString(R.string.ai_dj_model_downloading, storageSize(d.bytes), storageSize(d.total)),
                appString(R.string.ai_dj_model_cancel)) { store.cancel() }
        }
        model == com.aurora.music.ai.DjModelState.Verifying -> StorageRow(Icons.Filled.AutoAwesome, title, appString(R.string.ai_dj_model_verifying), null, working = true)
        else -> StorageRow(Icons.Filled.AutoAwesome, title,
            (model as? com.aurora.music.ai.DjModelState.Failed)?.let { com.aurora.music.ui.screens.aidj.aiDjFailure(it.reason) }
                ?: missing,
            appString(R.string.ai_dj_model_download)) { onDownload() }
    }
}

@Composable
private fun AnalysisSettings(usage: StorageUsage, working: Set<String>, perform: Perform, ask: Ask) {
    val container = LocalDesktopContainer.current
    val scope = rememberCoroutineScope()
    val auto by container.settingsStore.sonicAutoAnalyze.collectAsStateWithLifecycle(initialValue = false)
    val analysed by container.sonicStore.count.collectAsStateWithLifecycle()
    val clear = appString(R.string.storage_clear)
    SettingsSectionTitle(appString(R.string.storage_section_analysis))
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.AutoAwesome, appString(R.string.text_auto_analyze_on_launch_854335),
            appString(R.string.text_analyze_new_tracks_across_your_library_including_streams_841790), auto) { v ->
            scope.launch { container.settingsStore.setSonicAutoAnalyze(v) }
        }
        SettingsRowDivider()
        StorageRow(Icons.Filled.AutoAwesome, appString(R.string.storage_sonic),
            storageDetail(usage.sonic, appPlural(R.plurals.storage_tracks_analysed, analysed)),
            clear, enabled = usage.sonic > 0, working = "sonic" in working) {
            ask(appString(R.string.storage_sonic_clear_title), appString(R.string.storage_sonic_clear_message), clear) {
                perform("sonic", R.string.storage_cleared) { container.storage.clearSonic(); true }
            }
        }
        SettingsRowDivider()
        StorageRow(Icons.AutoMirrored.Filled.QueueMusic, appString(R.string.storage_stems),
            storageDetail(usage.stems, appString(R.string.storage_stems_detail)),
            clear, enabled = usage.stems > 0, working = "stems" in working) {
            ask(appString(R.string.storage_stems_clear_title), appString(R.string.storage_stems_clear_message), clear) {
                perform("stems", R.string.storage_cleared) { container.stemSeparator.clearStems() }
            }
        }
        SettingsRowDivider()
        val delete = appString(R.string.storage_delete)
        StorageRow(Icons.Filled.Psychology, appString(R.string.storage_stem_model),
            if (usage.stemModel > 0) storageDetail(usage.stemModel, appString(R.string.storage_stem_model_ready))
            else appString(R.string.storage_stem_model_missing),
            delete, enabled = usage.stemModel > 0, working = "model" in working, destructive = true) {
            ask(appString(R.string.storage_model_delete_title), appString(R.string.storage_model_delete_message), delete) {
                perform("model", R.string.storage_cleared) { container.stemSeparator.deleteModel() }
            }
        }
        SettingsRowDivider()
        AiDjModelRow(container.aiDjModels, appString(R.string.ai_dj_model), "ai_dj", usage.aiDjModel,
            appString(R.string.ai_dj_model_missing, storageSize(com.aurora.music.ai.DjModelStore.BYTES)), working, perform, ask,
            container::downloadAiDjModel, container::deleteAiDjModel)
        SettingsRowDivider()
        AiDjModelRow(container.aiDjVoiceModels, appString(R.string.ai_dj_voice_model), "ai_dj_voice", usage.aiDjVoiceModel,
            appString(R.string.ai_dj_voice_missing, storageSize(com.aurora.music.ai.DjModelStore.WHISPER.bytes)), working, perform, ask,
            container::downloadAiDjVoiceModel, container::deleteAiDjVoiceModel)
        SettingsRowDivider()
        AiDjModelRow(container.aiDjSpeakerModels, appString(R.string.ai_dj_speaker_model), "ai_dj_speaker", usage.aiDjSpeakerModel,
            appString(R.string.ai_dj_speaker_missing, storageSize(com.aurora.music.ai.DjModelStore.SPEAKER.bytes)), working, perform, ask,
            container::downloadAiDjSpeakerModel, container::deleteAiDjSpeakerModel)
        val measured = container.replayGainStore.size
        if (measured > 0) {
            SettingsRowDivider()
            StorageRow(Icons.Filled.Hearing, appString(R.string.storage_replaygain),
                appPlural(R.plurals.storage_tracks_analysed, measured), clear, working = "replaygain" in working) {
                ask(appString(R.string.storage_replaygain_clear_title), appString(R.string.storage_replaygain_clear_message), clear) {
                    perform("replaygain", R.string.storage_cleared) { container.replayGainStore.clear(); true }
                }
            }
        }
    }
}

@Composable
private fun HistorySettings(usage: StorageUsage, working: Set<String>, perform: Perform, ask: Ask) {
    val container = LocalDesktopContainer.current
    val store = container.settingsStore
    val scope = rememberCoroutineScope()
    val privateSession by store.privateSession.collectAsStateWithLifecycle(initialValue = false)
    val history by container.playHistory.history.collectAsStateWithLifecycle()
    val saveSearches by store.saveRecentSearches.collectAsStateWithLifecycle(initialValue = true)
    val searches by store.recentSearches.collectAsStateWithLifecycle(initialValue = emptyList())
    val insights by store.insightsPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.InsightsPrefs())
    val places by container.placesStore.places.collectAsStateWithLifecycle()
    val levels by container.listeningLevels.state.collectAsStateWithLifecycle()
    val clear = appString(R.string.storage_clear)
    val delete = appString(R.string.storage_delete)
    val plays = history.count { it.qualifiesAsPlay }
    SettingsSectionTitle(appString(R.string.storage_section_history))
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.Lock, appString(R.string.text_private_session_ec7cef), appString(R.string.storage_private_detail), privateSession) { v ->
            scope.launch { store.setPrivateSession(v) }
        }
        SettingsRowDivider()
        StorageRow(Icons.Filled.History, appString(R.string.storage_play_history), appPlural(R.plurals.storage_plays, plays),
            clear, enabled = history.isNotEmpty(), working = "history" in working, destructive = true) {
            ask(appString(R.string.storage_play_history_clear_title), appString(R.string.storage_play_history_clear_message), clear) {
                perform("history", R.string.storage_cleared) { container.playHistory.clear(); true }
            }
        }
        SettingsRowDivider()
        StorageRow(Icons.Filled.Insights, appString(R.string.insights_title),
            listOfNotNull(appPlural(R.plurals.storage_plays, usage.insightsPlays),
                appString(R.string.storage_insights_off).takeIf { !insights.enabled }).joinToString(" · "),
            delete, enabled = usage.insightsPlays > 0, working = "insights" in working, destructive = true) {
            ask(appString(R.string.insights_delete), appString(R.string.insights_delete_body), appString(R.string.insights_delete_confirm)) {
                perform("insights", R.string.storage_cleared) { container.listeningDetails.clear(); true }
            }
        }
        if (usage.locatedPlays > 0) {
            SettingsRowDivider()
            StorageRow(Icons.Filled.Map, appString(R.string.insights_location_history),
                appString(R.string.insights_location_count, usage.locatedPlays.toString()),
                delete, working = "locations" in working, destructive = true) {
                ask(appString(R.string.insights_delete_locations), appString(R.string.insights_delete_locations_body), appString(R.string.insights_delete_confirm)) {
                    perform("locations", R.string.storage_cleared) { container.listeningDetails.clearLocations(); true }
                }
            }
        }
        if (places.configured) {
            SettingsRowDivider()
            StorageRow(Icons.Filled.Place, appString(R.string.insights_places), appString(R.string.storage_places_detail),
                appString(R.string.text_forget_03d5d8), working = "places" in working, destructive = true) {
                perform("places", R.string.storage_cleared) { container.placesStore.clear(); true }
            }
        }
        SettingsRowDivider()
        SettingsSwitchRow(Icons.Filled.Search, appString(R.string.storage_save_searches), appString(R.string.storage_save_searches_detail), saveSearches) { v ->
            scope.launch { store.setSaveRecentSearches(v) }
        }
        SettingsRowDivider()
        StorageRow(Icons.Filled.Search, appString(R.string.storage_search_history), appPlural(R.plurals.storage_searches, searches.size),
            clear, enabled = searches.isNotEmpty(), working = "searches" in working) {
            perform("searches", R.string.storage_cleared) { store.clearRecentSearches(); true }
        }
        SettingsRowDivider()
        SettingsSwitchRow(Icons.Filled.Hearing, appString(R.string.text_save_level_samples_c79a47),
            appString(R.string.text_every_30_seconds_up_to_2_880_samples_stored_locally_39373d), levels.historyEnabled) { v ->
            scope.launch { container.listeningLevels.setHistoryEnabled(v) }
        }
        SettingsRowDivider()
        StorageRow(Icons.Filled.Hearing, appString(R.string.storage_levels), appPlural(R.plurals.storage_level_samples, levels.history.size),
            clear, enabled = levels.history.isNotEmpty(), working = "levels" in working) {
            ask(appString(R.string.storage_levels_clear_title), appString(R.string.storage_levels_clear_message), clear) {
                perform("levels", R.string.storage_cleared) { container.listeningLevels.clearHistory().getOrThrow(); true }
            }
        }
    }
}

private fun sourceLabel(serverId: String, sessions: List<Session>): String {
    if (serverId.isBlank()) return appString(R.string.storage_source_unknown)
    val host = runCatching { java.net.URI(serverId).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: serverId
    val user = sessions.firstOrNull { it.server == serverId }?.username?.takeIf { it.isNotBlank() }
    return if (user != null) "$user · $host" else host
}
