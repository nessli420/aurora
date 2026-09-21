package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingPresetLibrary
import com.aurora.music.data.routes.*
import com.aurora.music.data.rules.PresetRuleSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun OutputPresetBindingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val observation by store.processingRoutes.observations.collectAsStateWithLifecycle()
    var loadError by remember { mutableStateOf<String?>(null) }
    val rulesFlow = remember(store) { store.processingRouteRules.catch { loadError = it.message ?: appString(R.string.text_output_rules_unavailable_e638bb) } }
    val rules by rulesFlow.collectAsStateWithLifecycle<ProcessingRouteRules?>(initialValue = null)
    val orderedFlow = remember(store) { store.presetRules.catch { loadError = it.message ?: appString(R.string.text_preset_rules_unavailable_2c7cba) } }
    val ordered by orderedFlow.collectAsStateWithLifecycle(initialValue = PresetRuleSet())
    val library by store.processingPresetLibrary.collectAsStateWithLifecycle<ProcessingPresetLibrary?>(initialValue = null)
    val status by container.autoEqController.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var bindingObservation by remember { mutableStateOf<RouteObservation?>(null) }
    var busy by remember { mutableStateOf(false) }
    val route = observation.route
    val key = route.key
    val ready = rules != null && loadError == null && !busy
    fun perform(action: suspend () -> Result<Unit>) {
        if (!ready) return
        busy = true
        scope.launch {
            try { action().getOrThrow() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: appString(R.string.text_could_not_update_output_rules_c86d7e)) }
            finally { busy = false }
        }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(appString(R.string.text_output_presets_8523fd), onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(route.label, style = MaterialTheme.typography.titleMedium)
                            Text(route.detail, style = MaterialTheme.typography.bodySmall)
                            if (key != null) Text(status.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                loadError?.let { item { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
                if (rules != null) item {
                    SettingsGroup {
                        SettingsSwitchRow(title = appString(R.string.text_apply_output_presets_a03e14), checked = rules!!.enabled,
                            onCheckedChange = { enabled -> perform { store.setRouteRulesEnabled(enabled) } })
                        if (key != null) {
                            SettingsSwitchRow(title = appString(R.string.text_keep_current_sound_6e3bdc), subtitle = appString(R.string.text_keep_manual_settings_after_reconnecting_2f0892),
                                checked = ordered.manualHold || key in rules!!.manual, onCheckedChange = { hold -> perform {
                                    store.holdCurrentRoute(hold).getOrThrow()
                                    if (!hold) store.setPresetRuleManualHold(false) else Result.success(Unit)
                                } })
                        }
                    }
                }
                if (key != null && route.kind == ProcessingRouteKind.ANDROID) {
                    val choices = rules?.bindings.orEmpty().filter { it.routeKey == key }.map { it.headphones }.distinct()
                    if (choices.any { it.isNotEmpty() }) item {
                        SettingsGroup {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(appString(R.string.text_connected_headphones_68a2f7), style = MaterialTheme.typography.titleSmall)
                                (listOf("") + choices).distinct().forEach { headphones ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = rules?.headphones?.get(key).orEmpty() == headphones,
                                            enabled = ready, onClick = { perform { store.chooseRouteHeadphones(key, headphones) } })
                                        Text(headphones.ifEmpty { appString(R.string.text_output_default_f4c103) })
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Button(onClick = { bindingObservation = observation }, enabled = ready && library?.error == null && library?.presets?.isNotEmpty() == true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text(appString(R.string.text_bind_saved_preset_ee9b6b)) }
                        if (library?.presets?.isEmpty() == true) Text(appString(R.string.text_save_a_processing_preset_first_6dd1cf), Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(rules?.bindings.orEmpty(), key = { "${it.routeKey}/${it.headphones}" }) { binding ->
                    SettingsGroup {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(binding.routeLabel, style = MaterialTheme.typography.titleSmall)
                                if (binding.headphones.isNotEmpty()) Text(binding.headphones, style = MaterialTheme.typography.bodyMedium)
                                Text(library?.presets?.firstOrNull { it.id == binding.presetId }?.name ?: appString(R.string.text_deleted_preset_4bf611), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(enabled = ready, onClick = { perform { store.removeRouteBinding(binding) } }) { Text(appString(R.string.text_remove_e96390)) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }
    bindingObservation?.let { expected ->
        OutputBindingDialog(library?.presets.orEmpty(), rules?.headphones?.get(expected.route.key).orEmpty(), expected.route.label,
            onDismiss = { bindingObservation = null }, onBind = { preset, headphones ->
                bindingObservation = null
                perform { store.bindCurrentRoute(preset, headphones, expected) }
            })
    }
}

@Composable
private fun OutputBindingDialog(presets: List<ProcessingPreset>, initialHeadphones: String, output: String,
    onDismiss: () -> Unit, onBind: (String, String) -> Unit) {
    var selected by remember { mutableStateOf(presets.firstOrNull()?.id) }
    var headphones by remember { mutableStateOf(initialHeadphones) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.text_bind_to_9fa6ec, (output))) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(presets.firstOrNull { it.id == selected }?.name ?: appString(R.string.text_choose_preset_005541)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    presets.forEach { preset -> DropdownMenuItem(text = { Text(preset.name) }, onClick = { selected = preset.id; expanded = false }) }
                }
            }
            OutlinedTextField(headphones, { headphones = it.take(80) }, label = { Text(appString(R.string.text_headphones_optional_995b8e)) }, singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let { onBind(it, headphones.trim()) } }) { Text(appString(R.string.text_bind_and_apply_d9c71d)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
}
