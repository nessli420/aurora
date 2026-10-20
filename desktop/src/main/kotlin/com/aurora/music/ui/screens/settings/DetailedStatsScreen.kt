package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.data.InsightsPrefs
import com.aurora.music.data.InsightsSummary
import com.aurora.music.localization.appString
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun DetailedStatsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenInsights: () -> Unit = {}, onOpenMap: () -> Unit = {}) {
    val container = LocalDesktopContainer.current
    val store = container.settingsStore
    val prefs by store.insightsPrefs.collectAsStateWithLifecycle<InsightsPrefs?>(initialValue = null)
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(InsightsSummary()) }
    var refresh by remember { mutableIntStateOf(0) }
    var device by rememberSaveable { mutableStateOf(true) }
    var audioPath by rememberSaveable { mutableStateOf(true) }
    var importExisting by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(prefs?.enabled, refresh) {
        if (prefs?.enabled == true) summary = container.listeningDetails.summary()
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(appString(R.string.insights_title), onBack)
        val current = prefs ?: return@Column
        LazyColumn(Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!current.enabled) {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp)) {
                            Text(appString(R.string.insights_intro_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(appString(R.string.insights_intro_body), Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SettingsSectionTitle(appString(R.string.insights_guide_title)) }
                item {
                    SettingsGroup {
                        GuideStep(1, appString(R.string.insights_step_recorded_title), appString(R.string.insights_step_recorded_body))
                        GuideStep(2, appString(R.string.insights_step_extras_title), appString(R.string.insights_step_extras_body))
                        GuideStep(3, appString(R.string.insights_step_private_title_desktop), appString(R.string.insights_step_private_body))
                    }
                }
                item { SettingsSectionTitle(appString(R.string.insights_extras_title)) }
                item {
                    SettingsGroup {
                        SettingsSwitchRow(Icons.Filled.Headphones, appString(R.string.insights_device), appString(R.string.insights_device_desc), device) { device = it }
                        SettingsRowDivider()
                        SettingsSwitchRow(Icons.Filled.GraphicEq, appString(R.string.insights_audio_path), appString(R.string.insights_audio_path_desc), audioPath) { audioPath = it }
                        SettingsRowDivider()
                        SettingsSwitchRow(Icons.Filled.History, appString(R.string.insights_import), appString(R.string.insights_import_desc), importExisting) { importExisting = it }
                    }
                }
                item {
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            try {
                                store.setInsightsDevice(device)
                                store.setInsightsAudioPath(audioPath)
                                if (importExisting) container.listeningDetails.importHistory(container.playHistory.snapshot())
                                store.setInsightsEnabled(true)
                            } finally { busy = false }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(appString(R.string.insights_enable))
                    }
                }
            } else {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp)) {
                            Text(appString(R.string.insights_recording), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(summary.since?.let { appString(R.string.insights_count, summary.plays, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
                                ?: appString(R.string.insights_empty), Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SettingsRowDivider()
                        SettingsSwitchRow(Icons.Filled.Insights, appString(R.string.insights_record), appString(R.string.insights_description), true) { on ->
                            scope.launch { store.setInsightsEnabled(on) }
                        }
                    }
                }
                item {
                    Button(onClick = onOpenInsights, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(appString(R.string.insights_open))
                    }
                }
                item { SettingsSectionTitle(appString(R.string.insights_extras_title)) }
                item {
                    SettingsGroup {
                        SettingsSwitchRow(Icons.Filled.Headphones, appString(R.string.insights_device), appString(R.string.insights_device_desc), current.device) { on ->
                            scope.launch { store.setInsightsDevice(on) }
                        }
                        SettingsRowDivider()
                        SettingsSwitchRow(Icons.Filled.GraphicEq, appString(R.string.insights_audio_path), appString(R.string.insights_audio_path_desc), current.audioPath) { on ->
                            scope.launch { store.setInsightsAudioPath(on) }
                        }
                    }
                }
                item { SettingsSectionTitle(appString(R.string.insights_places)) }
                item { PlacesSettings(current.places) }
                item { SettingsSectionTitle(appString(R.string.insights_location_history)) }
                item { LocationHistorySettings(current.locationHistory, refresh, onOpenMap) }
                item { Note(appString(R.string.insights_step_private_body)) }
                item {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(appString(R.string.insights_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh, 
        onDismissRequest = { confirmDelete = false },
        icon = { androidx.compose.material3.Icon(Icons.Filled.Delete, null) },
        title = { Text(appString(R.string.insights_delete)) },
        text = { Text(appString(R.string.insights_delete_body)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    container.listeningDetails.clear()
                    refresh++
                }
            }) { Text(appString(R.string.insights_delete_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}

@Composable
private fun LocationAccess() {
    val placesStore = com.aurora.music.desktop.ui.LocalDesktopContainer.current.placesStore
    val denied by placesStore.denied.collectAsStateWithLifecycle()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    when {
        !placesStore.supported -> Note(appString(R.string.location_unsupported))
        denied && !com.aurora.music.desktop.platform.HostPlatform.isWindows -> Note(appString(R.string.location_linux_off))
        denied -> {
            Note(appString(R.string.location_windows_off))
            TextButton(onClick = { runCatching { uri.openUri("ms-settings:privacy-location") } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(appString(R.string.location_open_windows_settings))
            }
        }
    }
}

@Composable
private fun PlacesSettings(enabled: Boolean) {
    val container = com.aurora.music.desktop.ui.LocalDesktopContainer.current
    val placesStore = container.placesStore
    val places by placesStore.places.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val radius = places.home?.radiusM ?: places.work?.radiusM ?: 250
    fun setHere(apply: (com.aurora.music.data.Places, com.aurora.music.data.Place) -> com.aurora.music.data.Places) {
        status = null
        scope.launch {
            when (val result = placesStore.currentLocation()) {
                is com.aurora.music.desktop.platform.LocationResult.Found -> {
                    placesStore.update { apply(it, com.aurora.music.data.Place(result.fix.latitude, result.fix.longitude, radius)) }
                    status = appString(R.string.insights_place_saved)
                }
                com.aurora.music.desktop.platform.LocationResult.Denied -> status = null
                com.aurora.music.desktop.platform.LocationResult.Unavailable -> status = appString(R.string.insights_location_failed)
            }
        }
    }
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.Place, appString(R.string.insights_places_toggle), appString(R.string.insights_places_desc), enabled) { on ->
            status = null
            scope.launch { container.settingsStore.setInsightsPlaces(on && placesStore.supported) }
        }
        Note(appString(R.string.insights_places_guide_desktop))
        LocationAccess()
        if (enabled) {
            SettingsRowDivider()
            PlaceRow(Icons.Filled.Home, appString(R.string.insights_zone_home), places.home != null) { setHere { p, place -> p.copy(home = place) } }
            PlaceRow(Icons.Filled.Work, appString(R.string.insights_zone_work), places.work != null) { setHere { p, place -> p.copy(work = place) } }
            SegmentedRow(appString(R.string.insights_radius), RADII.map { "$it m" }, RADII.indexOf(radius).coerceAtLeast(0)) { i ->
                placesStore.update { it.copy(home = it.home?.copy(radiusM = RADII[i]), work = it.work?.copy(radiusM = RADII[i])) }
            }
            status?.let { Note(it) }
            if (places.configured) TextButton(onClick = { placesStore.clear(); status = null }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(appString(R.string.insights_forget_places))
            }
        }
    }
}

@Composable
private fun LocationHistorySettings(enabled: Boolean, refreshKey: Int, onOpenMap: () -> Unit) {
    val container = com.aurora.music.desktop.ui.LocalDesktopContainer.current
    val placesStore = container.placesStore
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(0) }
    var cleared by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey, cleared, enabled) { count = container.listeningDetails.locationCount() }
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.Map, appString(R.string.insights_location_history_toggle), appString(R.string.insights_location_history_desc), enabled) { on ->
            scope.launch {
                container.settingsStore.setInsightsLocationHistory(on && placesStore.supported)
                if (on) placesStore.currentLocation()
            }
        }
        Note(appString(R.string.insights_location_history_guide_desktop))
        LocationAccess()
        SettingsRowDivider()
        Note(appString(R.string.insights_location_count, count))
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = onOpenMap) { Text(appString(R.string.insights_open_map)) }
            TextButton(onClick = { confirm = true }, enabled = count > 0) {
                Text(appString(R.string.insights_delete_locations),
                    color = if (count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirm) AlertDialog(containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh,
        onDismissRequest = { confirm = false },
        title = { Text(appString(R.string.insights_delete_locations)) },
        text = { Text(appString(R.string.insights_delete_locations_body)) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch { container.listeningDetails.clearLocations(); cleared++ }
            }) { Text(appString(R.string.insights_delete_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}

@Composable
private fun PlaceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, set: Boolean, onUseLocation: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (set) appString(R.string.insights_place_saved) else appString(R.string.text_not_set_93039e),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onUseLocation) { Text(appString(R.string.insights_use_location)) }
    }
}

private val RADII = listOf(100, 250, 500)

@Composable
private fun GuideStep(number: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
