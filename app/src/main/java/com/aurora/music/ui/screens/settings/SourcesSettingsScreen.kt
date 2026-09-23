package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.localizedMediaType

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import com.aurora.music.data.SacdLibrary
import com.aurora.music.data.SacdLibraryEntry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DEFAULT_SOURCE_PRIORITY
import com.aurora.music.data.MERGE_NONE
import com.aurora.music.data.ServerType
import com.aurora.music.data.accountKey
import kotlinx.coroutines.launch

private fun tierLabel(t: String) = when (t) {
    "local" -> appString(R.string.text_on_device_file_7d2f79)
    "downloaded" -> appString(R.string.text_downloaded_c61970)
    "stream" -> appString(R.string.text_stream_from_server_1f8520)
    else -> t
}
private fun tierSub(t: String) = when (t) {
    "local" -> appString(R.string.text_a_matching_file_in_your_device_s_music_library_691446)
    "downloaded" -> appString(R.string.text_a_track_downloaded_inside_the_app_8619cd)
    "stream" -> appString(R.string.plex_sources_stream_detail)
    else -> ""
}

@Composable
fun SourcesSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onArtistSeparators: () -> Unit) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val scope = rememberCoroutineScope()
    val sacd = remember { SacdLibrary(ctx) }
    var images by remember { mutableStateOf(emptyList<SacdLibraryEntry>()) }
    var importBusy by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sacd) { images = sacd.entries() }
    val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importBusy = true; importStatus = null
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val count = sacd.add(uri)
                container.localLibrary.refresh()
                images = sacd.entries()
                importStatus = appString(R.string.text_imported_tracks_45fc79, (count))
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                importStatus = failure.message ?: appString(R.string.text_image_could_not_be_imported_d64587)
            } finally { importBusy = false }
        }
    }

    val preferLocal by store.preferLocalSources.collectAsStateWithLifecycle(initialValue = true)
    val priority by store.sourcePriority.collectAsStateWithLifecycle(initialValue = DEFAULT_SOURCE_PRIORITY)
    val unified by store.unifiedLibrary.collectAsStateWithLifecycle(initialValue = false)
    val mergeKeys by store.mergeSources.collectAsStateWithLifecycle(initialValue = emptySet())
    val saved by store.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())

    val servers = remember(saved) {
        saved.filter { it.type.supportsMergedLibrary && it.type != ServerType.LOCAL }.distinctBy { it.accountKey() }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_library_sources_101221), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle(appString(R.string.text_local_library_1c67cd)) }
            item {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onArtistSeparators).padding(20.dp)) {
                        Text(appString(R.string.text_artist_separators_4a3dc4), style = MaterialTheme.typography.titleSmall)
                    }
                    TextButton(onClick = { importImage.launch(arrayOf("*/*")) }, enabled = !importBusy,
                        modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(if (importBusy) appString(R.string.text_importing_820599) else appString(R.string.text_import_sacd_image_dd7548))
                    }
                    importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                }
            }
            items(images, key = { it.uri }) { image ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(image.name, style = MaterialTheme.typography.bodyMedium)
                        image.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(enabled = !importBusy, onClick = { scope.launch {
                        importBusy = true
                        try { sacd.remove(image.uri); container.localLibrary.refresh(); images = sacd.entries() }
                        catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            importStatus = appString(R.string.text_image_could_not_be_removed_f91668)
                        } finally { importBusy = false }
                    } }) { Text(appString(R.string.text_remove_e96390)) }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_best_source_playback_8c92cb)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.Smartphone, appString(R.string.text_prefer_local_copies_a74911),
                        appString(R.string.text_play_a_matching_on_device_or_downloaded_file_instead_of_streaming_6f069e), preferLocal,
                    ) { v -> scope.launch { store.setPreferLocalSources(v) } }
                }
            }
            item {
                Text(
                    appString(R.string.text_when_a_track_is_available_from_more_than_one_place_aurora_plays_i_99eef3) +
                        appString(R.string.text_source_below_that_has_it_reorder_to_taste_f0910c),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            itemsIndexed(priority) { idx, tier ->
                PriorityRow(
                    label = tierLabel(tier),
                    subtitle = tierSub(tier),
                    enabled = preferLocal,
                    canUp = idx > 0,
                    canDown = idx < priority.lastIndex,
                    onUp = { scope.launch { store.setSourcePriority(priority.swapped(idx, idx - 1)) } },
                    onDown = { scope.launch { store.setSourcePriority(priority.swapped(idx, idx + 1)) } },
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SettingsSectionTitle(appString(R.string.text_unified_library_f888ae)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.MergeType, appString(R.string.text_merge_all_sources_004b13),
                        appString(R.string.text_show_albums_artists_playlists_from_your_local_files_and_every_inc_4ea040), unified,
                    ) { v -> scope.launch { store.setUnifiedLibrary(v) } }
                }
            }
            if (unified) {
                item {
                    Text(
                        appString(R.string.text_included_in_the_unified_library_0ecc5b),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                    )
                }
                item { SourceRow(appString(R.string.text_on_this_device_a7f962), appString(R.string.text_local_files_f4a889), included = true, toggleable = false) {} }
                items(servers.size) { i ->
                    val s = servers[i]
                    val key = s.accountKey()
                    val noneSelected = mergeKeys == setOf(MERGE_NONE)
                    val included = !noneSelected && (mergeKeys.isEmpty() || key in mergeKeys)
                    SourceRow(s.typeLabel.localizedMediaType(), s.server.removePrefix("http://").removePrefix("https://"), included = included, toggleable = true) {
                        val current = when {
                            noneSelected -> emptySet()
                            mergeKeys.isEmpty() -> servers.map { it.accountKey() }.toSet()   // empty means all make explicit
                            else -> mergeKeys
                        }
                        val next = if (key in current) current - key else current + key
                        // empty re-reads as all so persist none-sentinel for local only
                        scope.launch { store.setMergeSources(if (next.isEmpty()) setOf(MERGE_NONE) else next) }
                    }
                }
                if (servers.isEmpty()) {
                    item {
                        Text(
                            appString(R.string.plex_sources_empty_detail),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                item {
                    Text(
                        appString(R.string.text_youtube_music_adds_catalogue_search_and_a_separate_home_feed_matc_b8a69c),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private fun List<String>.swapped(a: Int, b: Int): List<String> {
    if (a !in indices || b !in indices) return this
    return toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
}

@Composable
private fun PriorityRow(label: String, subtitle: String, enabled: Boolean, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Icon(
            Icons.Filled.KeyboardArrowUp, appString(R.string.text_move_up_b4f57c),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled && canUp) 1f else 0.25f),
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled && canUp, onClick = onUp).padding(8.dp),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown, appString(R.string.text_move_down_260ff8),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled && canDown) 1f else 0.25f),
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled && canDown, onClick = onDown).padding(8.dp),
        )
    }
}

@Composable
private fun SourceRow(title: String, subtitle: String, included: Boolean, toggleable: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Switch(checked = included, enabled = toggleable, onCheckedChange = { onToggle() })
    }
}
