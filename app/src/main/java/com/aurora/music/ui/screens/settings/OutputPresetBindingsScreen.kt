package com.aurora.music.ui.screens.settings

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun OutputPresetBindingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val observation by store.processingRoutes.observations.collectAsStateWithLifecycle()
    var loadError by remember { mutableStateOf<String?>(null) }
    val rulesFlow = remember(store) { store.processingRouteRules.catch { loadError = it.message ?: "Output rules unavailable." } }
    val rules by rulesFlow.collectAsStateWithLifecycle<ProcessingRouteRules?>(initialValue = null)
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
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: "Could not update output rules.") }
            finally { busy = false }
        }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar("Output presets", onBack)
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
                        SettingsSwitchRow(title = "Apply output presets", checked = rules!!.enabled,
                            onCheckedChange = { enabled -> perform { store.setRouteRulesEnabled(enabled) } })
                        if (key != null) {
                            SettingsSwitchRow(title = "Keep current sound", subtitle = "Keep manual settings after reconnecting.",
                                checked = key in rules!!.manual, onCheckedChange = { hold -> perform { store.holdCurrentRoute(hold) } })
                        }
                    }
                }
                if (key != null && route.kind == ProcessingRouteKind.ANDROID) {
                    val choices = rules?.bindings.orEmpty().filter { it.routeKey == key }.map { it.headphones }.distinct()
                    if (choices.any { it.isNotEmpty() }) item {
                        SettingsGroup {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Connected headphones", style = MaterialTheme.typography.titleSmall)
                                (listOf("") + choices).distinct().forEach { headphones ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = rules?.headphones?.get(key).orEmpty() == headphones,
                                            enabled = ready, onClick = { perform { store.chooseRouteHeadphones(key, headphones) } })
                                        Text(headphones.ifEmpty { "Output default" })
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Button(onClick = { bindingObservation = observation }, enabled = ready && library?.error == null && library?.presets?.isNotEmpty() == true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("Bind saved preset") }
                        if (library?.presets?.isEmpty() == true) Text("Save a processing preset first.", Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(rules?.bindings.orEmpty(), key = { "${it.routeKey}/${it.headphones}" }) { binding ->
                    SettingsGroup {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(binding.routeLabel, style = MaterialTheme.typography.titleSmall)
                                if (binding.headphones.isNotEmpty()) Text(binding.headphones, style = MaterialTheme.typography.bodyMedium)
                                Text(library?.presets?.firstOrNull { it.id == binding.presetId }?.name ?: "Deleted preset", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(enabled = ready, onClick = { perform { store.removeRouteBinding(binding) } }) { Text("Remove") }
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Bind to $output") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(presets.firstOrNull { it.id == selected }?.name ?: "Choose preset") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    presets.forEach { preset -> DropdownMenuItem(text = { Text(preset.name) }, onClick = { selected = preset.id; expanded = false }) }
                }
            }
            OutlinedTextField(headphones, { headphones = it.take(80) }, label = { Text("Headphones (optional)") }, singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let { onBind(it, headphones.trim()) } }) { Text("Bind and apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
