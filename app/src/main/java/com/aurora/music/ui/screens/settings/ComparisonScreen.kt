package com.aurora.music.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingPresetLibrary
import com.aurora.music.playback.compare.ComparisonSelection
import com.aurora.music.viewmodel.ComparisonViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ComparisonScreen(contentPadding: PaddingValues, onBack: () -> Unit,
    onOpenPresets: () -> Unit, currentSource: String = "", currentTitle: String = "",
    model: ComparisonViewModel = viewModel()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val store = (context.applicationContext as AuroraApplication).container.settingsStore
    val library by store.processingPresetLibrary.collectAsStateWithLifecycle(ProcessingPresetLibrary())
    val state by model.state.collectAsStateWithLifecycle()
    var aId by rememberSaveable { mutableStateOf("") }
    var bId by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(currentSource) }
    var sourceLabel by rememberSaveable { mutableStateOf(currentTitle) }
    var start by rememberSaveable { mutableStateOf("0") }
    var blind by rememberSaveable { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            source = uri.toString()
            sourceLabel = "Selected audio file"
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) state.result?.let { result ->
            scope.launch {
                exportMessage = runCatching {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { out ->
                            out.appendLine("Aurora ABX")
                            out.appendLine("Protocol: ${result.planned} fixed trials; independent random X per trial")
                            out.appendLine("Answered: ${result.answered}")
                            out.appendLine("Correct: ${result.correct}")
                            out.appendLine("Interrupted: ${result.interrupted}")
                            result.probability?.let { out.appendLine("One-sided chance probability: $it") }
                            out.appendLine("Matching: stereo RMS over the same selection, within 0.1 dB")
                            out.appendLine("Output: ${state.rate} Hz float PCM; same route and volume")
                            out.appendLine("Selection: ${state.selectionFrames} frames")
                            state.levels?.let { levels ->
                                out.appendLine("Linear gains: A=${levels.gainA}, B=${levels.gainB}")
                                out.appendLine("Measured RMS: A=${levels.rmsA}, B=${levels.rmsB}; difference=${levels.residualDb} dB")
                            }
                            result.trials.forEach { trial ->
                                out.appendLine("Trial ${trial.number}: guess=${if (trial.guessA) "A" else "B"}, X=${if (trial.xIsA) "A" else "B"}")
                            }
                        }
                    }
                    "Result exported."
                }.getOrElse { "Could not export the result." }
            }
        }
    }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    LaunchedEffect(library.presets) {
        if (library.presets.none { it.id == aId }) aId = library.presets.firstOrNull()?.id.orEmpty()
        if (library.presets.none { it.id == bId }) bId = library.presets.getOrNull(1)?.id ?: aId
    }
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Compare presets", onBack = { model.stop(); onBack() })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.active && !state.preparing) {
                item {
                    PresetComparisonChoice("A", aId, library.presets) { aId = it }
                    Spacer(Modifier.height(12.dp))
                    PresetComparisonChoice("B", bId, library.presets) { bId = it }
                    TextButton(onClick = onOpenPresets) { Text("Saved processing presets") }
                }
                item {
                    if (sourceLabel.isNotBlank()) Text(sourceLabel, style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { pick.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose audio") }
                    if (currentSource.isNotBlank() && source != currentSource) TextButton(onClick = {
                        source = currentSource; sourceLabel = currentTitle
                    }) { Text("Use current track") }
                    OutlinedTextField(value = start, onValueChange = { start = it.filter(Char::isDigit).take(5) },
                        label = { Text("Start at (seconds)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Blind ABX · 16 trials")
                        Switch(checked = blind, onCheckedChange = { blind = it })
                    }
                    Text("Up to 15 seconds, RMS matched within 0.1 dB. Shorter at high sample rates.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        val a = library.presets.first { it.id == aId }; val b = library.presets.first { it.id == bId }
                        model.start(Uri.parse(source), start.toIntOrNull() ?: 0, a, b, blind)
                    }, enabled = source.isNotBlank() && aId.isNotBlank() && bId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (blind) "Start ABX" else "Start A/B")
                    }
                }
            }
            if (state.preparing) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Preparing both presets…")
                TextButton(onClick = { model.stop() }) { Text("Cancel") }
            }
            if (state.active) {
                item {
                    if (state.blind) Text("Trial ${state.answered + 1} of ${state.planned}", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        (if (state.blind) ComparisonSelection.entries else listOf(ComparisonSelection.A, ComparisonSelection.B)).forEach { selection ->
                            Button(onClick = { model.select(selection) }, modifier = Modifier.weight(1f),
                                colors = if (state.selection == selection) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()) {
                                Text(selection.name)
                            }
                        }
                    }
                }
                if (state.blind) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { model.guess(true) }, Modifier.weight(1f)) { Text("X is A") }
                        OutlinedButton(onClick = { model.guess(false) }, Modifier.weight(1f)) { Text("X is B") }
                    }
                }
                else item {
                    state.levels?.let { Text(String.format(Locale.ROOT, "RMS difference: %.3f dB", it.residualDb)) }
                }
                item { OutlinedButton(onClick = { model.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") } }
            }
            state.result?.let { result -> item {
                Text(if (result.interrupted) "Test interrupted" else "${result.correct} correct out of ${result.planned}", style = MaterialTheme.typography.titleLarge)
                result.probability?.let { Text(String.format(Locale.ROOT, "Chance probability: %.4f", it)) }
                if (result.interrupted) Text("${result.answered} answers recorded. No significance result.")
                TextButton(onClick = { export.launch("aurora-abx.txt") }) { Text("Export result") }
            } }
            (state.error ?: library.error ?: exportMessage)?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun PresetComparisonChoice(label: String, selected: String, presets: List<ProcessingPreset>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = presets.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("$label · ${presets.firstOrNull { it.id == selected }?.name ?: "Save a preset first"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { preset -> DropdownMenuItem(text = { Text(preset.name) }, onClick = { onSelect(preset.id); expanded = false }) }
        }
    }
}
