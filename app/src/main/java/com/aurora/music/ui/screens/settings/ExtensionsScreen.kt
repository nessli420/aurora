package com.aurora.music.ui.screens.settings

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
            catch (failure: Exception) { message = failure.message ?: "Extension is unavailable." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        manager.refresh()
        manager.entries.value.filter { it.enabled }.forEach { manager.loadManifest(it.component) }
    }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        SettingsTopBar("Extensions", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Install a compatible extension app, then enable it here.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { run { manager.refresh() } }, enabled = !busy) { Text("Refresh") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (entries.isEmpty()) Text("No extensions installed.")
            }
            items(entries, key = { it.component }) { entry ->
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val capabilities = entry.descriptor?.capabilities ?: entry.grant?.capabilities.orEmpty()
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(capabilities.map { when (it) { "audio" -> "Audio presets"; "media" -> "Read-only library"; "metadata" -> "Metadata lookups"; else -> it } }
                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val license = entry.manifest?.license ?: entry.descriptor?.license.orEmpty()
                        if (license.isNotBlank()) Text("License: $license", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Enabled", modifier = Modifier.padding(top = 12.dp))
                            Switch(checked = entry.enabled, enabled = !busy && entry.descriptor?.compatible == true,
                                onCheckedChange = { enabled -> run { manager.setEnabled(entry.component, enabled, entry.descriptor).getOrThrow() } })
                        }
                        if (entry.enabled) {
                            if ("metadata" in capabilities) Text("Receives title, artist and album when you request a tag lookup.", style = MaterialTheme.typography.bodySmall)
                            entry.manifest?.settings?.forEach { field ->
                                var value by remember(entry.component, field.id, entry.grant?.settings) {
                                    mutableStateOf((entry.grant?.settings?.get(field.id) ?: field.default).toString())
                                }
                                OutlinedTextField(value, { value = it }, label = { Text(field.label) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                                TextButton(onClick = { run {
                                    manager.updateSetting(entry.component, field.id, value.toDoubleOrNull() ?: error("Enter a number.")).getOrThrow()
                                    message = "Setting saved."
                                } }, enabled = !busy) { Text("Save ${field.label.lowercase()}") }
                            }
                            if ("audio" in capabilities) TextButton(onClick = { run {
                                pendingAudio = manager.audio(entry.component).getOrThrow()
                            } }, enabled = !busy) { Text("Load audio preset") }
                            if ("media" in capabilities) TextButton(onClick = { run {
                                container.switchSession(manager.useLibrary(entry.component))
                                message = "Library selected."
                            } }, enabled = !busy) { Text("Use this library") }
                        }
                    }
                }
            }
        }
    }
    pendingAudio?.let { rack ->
        AlertDialog(onDismissRequest = { pendingAudio = null }, title = { Text("Load audio preset?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rack.name, style = MaterialTheme.typography.titleSmall)
                    Text(rack.nodes.joinToString(" → ") { it.name })
                    Text("This replaces the current rack. Saved presets stay available.")
                }
            },
            confirmButton = { TextButton(onClick = {
                pendingAudio = null
                run { container.settingsStore.setProcessingRack(rack).getOrThrow(); onRack() }
            }) { Text("Load") } }, dismissButton = { TextButton(onClick = { pendingAudio = null }) { Text("Cancel") } })
    }
}
