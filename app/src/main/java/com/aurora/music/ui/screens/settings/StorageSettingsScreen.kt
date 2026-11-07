package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appString
import com.aurora.music.localization.appPlural
import com.aurora.music.R

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.Session
import com.aurora.music.data.qualifiesAsPlay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
fun StorageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenYouTubeDownload: () -> Unit = {}) {
    val container = LocalContextApp()
    val context = LocalContext.current
    val store = container.settingsStore
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
    val rates = listOf(0, 128, 192, 256, 320)
    val rateLabels = listOf(appString(R.string.text_lossless_f3b36f), "128", "192", "256", "320")

    LaunchedEffect(container) {
        inventory.refresh()
        combine(container.downloadManager.downloads, container.audioCache.status, container.artworkRepository.bytes) { d, c, a -> Triple(d.size, c.bytes, a) }
            .drop(1).debounce(1000).collect { inventory.refresh() }
    }

    fun perform(key: String, done: Int = R.string.storage_cleared, action: suspend () -> Boolean) {
        if (key in working) return
        working = working + key
        scope.launch {
            val result = withContext(NonCancellable) {
                runCatching { action() }.also { runCatching { inventory.refresh() } }
            }
            working = working - key
            val message = when {
                result.isFailure -> R.string.storage_clear_failed
                result.getOrDefault(true) -> done
                else -> R.string.storage_stems_busy
            }
            Toast.makeText(context, appString(message), Toast.LENGTH_SHORT).show()
        }
    }

    fun ask(title: String, message: String, confirmLabel: String = appString(R.string.storage_clear), action: () -> Unit) {
        confirm = StorageConfirm(title, message, confirmLabel, action)
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_downloads_storage_f7c580), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            Spacer(Modifier.height(8.dp))
            StorageOverview(usage, "caches" in working) {
                ask(appString(R.string.storage_clear_caches_title), appString(R.string.storage_clear_caches_message)) {
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
                        perform("downloads") { inventory.removeDownloads(); true }
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
                            perform("downloads:$serverId") { inventory.removeDownloads(serverId); true }
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

            YouTubeDownloadSettings(onOpenYouTubeDownload)

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
                    perform("artwork") { inventory.clearArtwork(); true }
                }
                SettingsRowDivider()
                StorageRow(Icons.Filled.PhotoLibrary, appString(R.string.storage_image_cache),
                    storageDetail(usage.images, appString(R.string.storage_image_cache_detail)),
                    appString(R.string.storage_clear), enabled = usage.images > 0, working = "images" in working) {
                    perform("images") { inventory.clearImages(); true }
                }
                if (usage.mapTiles > 0) {
                    SettingsRowDivider()
                    StorageRow(Icons.Filled.Map, appString(R.string.storage_map_tiles),
                        storageDetail(usage.mapTiles, appString(R.string.storage_map_tiles_detail)),
                        appString(R.string.storage_clear), working = "tiles" in working) {
                        perform("tiles") { inventory.clearMapTiles(); true }
                    }
                }
            }

            AnalysisSettings(usage, working, ::perform, ::ask)

            TranslationSettings(usage, working, ::perform, ::ask)

            HistorySettings(usage, working, ::perform, ::ask)

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
                    ask(appString(R.string.storage_artist_info_clear_title), appString(R.string.storage_artist_info_clear_message)) {
                        perform("artists") { inventory.clearArtistInfo(); true }
                    }
                }
                SettingsRowDivider()
                StorageRow(Icons.Filled.DeleteSweep, appString(R.string.storage_temporary),
                    storageDetail(usage.temporary, appString(R.string.storage_temporary_detail)),
                    appString(R.string.storage_clear), enabled = usage.temporary > 0, working = "temporary" in working) {
                    perform("temporary") { inventory.clearTemporary(); true }
                }
            }

            Text(
                appString(R.string.text_downloads_are_stored_privately_inside_the_app_and_removed_when_yo_ffbbf6),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
    StorageConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun YouTubeDownloadSettings(onOpen: () -> Unit) {
    val container = LocalContextApp()
    val scope = rememberCoroutineScope()
    val youTube by container.settingsStore.youTubeDownloadPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.YouTubeDownloadPrefs())
    val formats = com.aurora.music.data.YouTubeDownloadFormat.entries
    val bitrates = com.aurora.music.data.YouTubeDownloadPrefs.bitrates
    SettingsSectionTitle(appString(R.string.youtube_download_settings))
    SettingsGroup {
        SettingsNavRow(Icons.Filled.CloudDownload, appString(R.string.youtube_download_title),
            subtitle = appString(R.string.youtube_download_open_detail), onClick = onOpen)
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
        appString(R.string.youtube_download_quality_note) + "\n" + appString(R.string.storage_youtube_note),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.VisibilityOff, appString(R.string.youtube_download_anonymous),
            appString(R.string.youtube_download_anonymous_detail), youTube.anonymous) { v ->
            scope.launch { container.settingsStore.setYouTubeDownloadAnonymous(v) }
        }
        SettingsRowDivider()
        SettingsSwitchRow(Icons.Filled.Image, appString(R.string.youtube_download_artwork),
            appString(R.string.youtube_download_artwork_detail), youTube.embedArtwork) { v ->
            scope.launch { container.settingsStore.setYouTubeDownloadArtwork(v) }
        }
    }
}

@Composable
private fun AnalysisSettings(
    usage: com.aurora.music.data.StorageUsage,
    working: Set<String>,
    perform: (String, Int, suspend () -> Boolean) -> Unit,
    ask: (String, String, String, () -> Unit) -> Unit,
) {
    val container = LocalContextApp()
    val scope = rememberCoroutineScope()
    val auto by container.settingsStore.sonicAutoAnalyze.collectAsStateWithLifecycle(initialValue = false)
    val analysed by container.sonicStore.count.collectAsStateWithLifecycle()
    val rg by container.replayGainScanner.progress.collectAsStateWithLifecycle()
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
        StorageRow(Icons.Filled.Lyrics, appString(R.string.storage_vocals),
            storageDetail(usage.vocals, appString(R.string.storage_vocals_detail)),
            clear, enabled = usage.vocals > 0, working = "vocals" in working) {
            perform("vocals", R.string.storage_cleared) { container.storage.clearVocals(); true }
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
        if (measured > 0 && !rg.running) {
            SettingsRowDivider()
            StorageRow(Icons.Filled.GraphicEq, appString(R.string.storage_replaygain),
                appPlural(R.plurals.storage_tracks_analysed, measured), clear, working = "replaygain" in working) {
                ask(appString(R.string.storage_replaygain_clear_title), appString(R.string.storage_replaygain_clear_message), clear) {
                    perform("replaygain", R.string.storage_cleared) { container.replayGainStore.clear(); true }
                }
            }
        }
    }
    if (container.isLocal) ReplayGainScanCard()
}

@Composable
private fun AiDjModelRow(
    store: com.aurora.music.ai.DjModelStore,
    title: String,
    key: String,
    bytes: Long,
    missing: String,
    working: Set<String>,
    perform: (String, Int, suspend () -> Boolean) -> Unit,
    ask: (String, String, String, () -> Unit) -> Unit,
    onDownload: () -> Unit,
    onDelete: suspend () -> Unit,
) {
    val container = LocalContextApp()
    val model by store.state.collectAsStateWithLifecycle()
    when {
        container.aiDjUnsupported != null -> StorageRow(Icons.Filled.AutoAwesome, title, appString(R.string.ai_dj_model_unsupported), null)
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
private fun ReplayGainScanCard() {
    val container = LocalContextApp()
    val rg by container.replayGainScanner.progress.collectAsStateWithLifecycle()
    SettingsSectionTitle(appString(R.string.text_volume_leveling_a9df98))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = !rg.running) { container.replayGainScanner.scan() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rg.running) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp,
            )
        } else {
            Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (rg.running) appString(R.string.text_scanning_replaygain_c527cc) else appString(R.string.text_scan_replaygain_d09e86),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
            )
            Text(
                when {
                    rg.running -> "${rg.done} / ${rg.total} • ${rg.current}"
                    container.replayGainStore.size > 0 -> appString(R.string.text_analyzed_tap_to_scan_new_tracks_5933cd, (container.replayGainStore.size))
                    else -> appString(R.string.text_measure_loudness_ebu_r128_to_level_playback_volume_30f01c)
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (rg.running) {
            Text(appString(R.string.text_cancel_77dfd2), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { container.replayGainScanner.cancel() }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
    Text(
        appString(R.string.text_applies_when_replaygain_is_set_to_track_or_album_in_equalizer_vol_1cf41c),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun TranslationSettings(
    usage: com.aurora.music.data.StorageUsage,
    working: Set<String>,
    perform: (String, Int, suspend () -> Boolean) -> Unit,
    ask: (String, String, String, () -> Unit) -> Unit,
) {
    val container = LocalContextApp()
    val scope = rememberCoroutineScope()
    val ui by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.UiPrefs())
    val delete = appString(R.string.storage_delete)
    SettingsSectionTitle(appString(R.string.storage_section_translation))
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.Translate, appString(R.string.lyrics_translate), appString(R.string.lyrics_translate_desc), ui.lyricsTranslate) { v ->
            scope.launch { container.settingsStore.setLyricsTranslate(v) }
        }
        if (usage.ready && usage.languagePacks.isEmpty()) {
            SettingsRowDivider()
            StorageRow(Icons.Filled.Translate, appString(R.string.storage_language_none), null, null)
        }
        usage.languagePacks.forEach { language ->
            val name = languageName(language)
            SettingsRowDivider()
            StorageRow(Icons.Filled.Translate, name, appString(R.string.storage_language_pack), delete,
                working = "pack:$language" in working, destructive = true) {
                ask(appString(R.string.storage_language_delete_title, name), appString(R.string.storage_language_delete_message), delete) {
                    perform("pack:$language", R.string.storage_cleared) { container.lyricsTranslator.deletePack(language) }
                }
            }
        }
    }
}

@Composable
private fun HistorySettings(
    usage: com.aurora.music.data.StorageUsage,
    working: Set<String>,
    perform: (String, Int, suspend () -> Boolean) -> Unit,
    ask: (String, String, String, () -> Unit) -> Unit,
) {
    val container = LocalContextApp()
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
                perform("history", R.string.storage_cleared) { container.playHistory.clear(); container.aiDjMemory.clear(); true }
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

private fun languageName(language: String): String {
    val display = AppStrings.context.resources.configuration.locales[0]
    return java.util.Locale.forLanguageTag(language).getDisplayLanguage(display)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(display) else it.toString() }
        .ifBlank { language }
}

private fun sourceLabel(serverId: String, sessions: List<Session>): String {
    if (serverId.isBlank()) return appString(R.string.storage_source_unknown)
    val host = runCatching { java.net.URI(serverId).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: serverId
    val user = sessions.firstOrNull { it.server == serverId }?.username?.takeIf { it.isNotBlank() }
    return if (user != null) "$user · $host" else host
}

@Composable
private fun LocalContextApp() =
    (LocalContext.current.applicationContext as AuroraApplication).container
