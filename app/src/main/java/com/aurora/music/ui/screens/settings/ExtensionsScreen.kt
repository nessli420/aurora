package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ProcessingRack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ExtensionsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onRack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val manager = container.extensions
    val entries by manager.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingAudio by remember { mutableStateOf<ProcessingRack?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: appString(R.string.text_extension_is_unavailable_ad061c) }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        manager.refresh()
        manager.entries.value.filter { it.enabled }.forEach { manager.loadManifest(it.component) }
    }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        SettingsTopBar(appString(R.string.text_extensions_656bcf), onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(appString(R.string.text_install_a_compatible_extension_app_then_enable_it_here_c7fadc), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { run { manager.refresh() } }, enabled = !busy) { Text(appString(R.string.text_refresh_56e3ba)) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (entries.isEmpty()) Text(appString(R.string.text_no_extensions_installed_83afbc))
            }
            items(entries, key = { it.component }) { entry ->
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val capabilities = entry.descriptor?.capabilities ?: entry.grant?.capabilities.orEmpty()
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(capabilities.map { when (it) { "audio" -> appString(R.string.text_audio_presets_fbf3f8); "media" -> appString(R.string.text_read_only_library_ac6b49); "metadata" -> appString(R.string.text_metadata_lookups_af4cbd); else -> it } }
                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val license = entry.manifest?.license ?: entry.descriptor?.license.orEmpty()
                        if (license.isNotBlank()) Text(appString(R.string.text_license_1ba7bb, (license)), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(appString(R.string.text_enabled_df174a), modifier = Modifier.padding(top = 12.dp))
                            Switch(checked = entry.enabled, enabled = !busy && entry.descriptor?.compatible == true,
                                onCheckedChange = { enabled -> run { manager.setEnabled(entry.component, enabled, entry.descriptor).getOrThrow() } })
                        }
                        if (entry.enabled) {
                            if ("metadata" in capabilities) Text(appString(R.string.text_receives_title_artist_and_album_when_you_request_a_tag_lookup_dd0b6a), style = MaterialTheme.typography.bodySmall)
                            entry.manifest?.settings?.forEach { field ->
                                var value by remember(entry.component, field.id, entry.grant?.settings) {
                                    mutableStateOf((entry.grant?.settings?.get(field.id) ?: field.default).toString())
                                }
                                OutlinedTextField(value, { value = it }, label = { Text(field.label) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                                TextButton(onClick = { run {
                                    manager.updateSetting(entry.component, field.id, value.toDoubleOrNull() ?: error(appString(R.string.text_enter_a_number_12f340))).getOrThrow()
                                    message = appString(R.string.text_setting_saved_ac1563)
                                } }, enabled = !busy) { Text(appString(R.string.text_save_e7ced9, (field.label.lowercase()))) }
                            }
                            if ("audio" in capabilities) TextButton(onClick = { run {
                                pendingAudio = manager.audio(entry.component).getOrThrow()
                            } }, enabled = !busy) { Text(appString(R.string.text_load_audio_preset_1be2c8)) }
                            if ("media" in capabilities) TextButton(onClick = { run {
                                container.switchSession(manager.useLibrary(entry.component))
                                message = appString(R.string.text_library_selected_8b0aae)
                            } }, enabled = !busy) { Text(appString(R.string.text_use_this_library_742704)) }
                        }
                    }
                }
            }
        }
    }
    pendingAudio?.let { rack ->
        AlertDialog(onDismissRequest = { pendingAudio = null }, title = { Text(appString(R.string.text_load_audio_preset_24bd13)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rack.name, style = MaterialTheme.typography.titleSmall)
                    Text(rack.nodes.joinToString(" → ") { it.name })
                    Text(appString(R.string.text_this_replaces_the_current_rack_saved_presets_stay_available_19c825))
                }
            },
            confirmButton = { TextButton(onClick = {
                pendingAudio = null
                run { container.settingsStore.setProcessingRack(rack).getOrThrow(); onRack() }
            }) { Text(appString(R.string.text_load_ddcb77)) } }, dismissButton = { TextButton(onClick = { pendingAudio = null }) { Text(appString(R.string.text_cancel_77dfd2)) } })
    }
}
