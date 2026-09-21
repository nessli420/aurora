package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
            sourceLabel = appString(R.string.text_selected_audio_file_fd51b5)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) state.result?.let { result ->
            scope.launch {
                exportMessage = runCatching {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { out ->
                            out.appendLine("Aurora ABX")
                            out.appendLine(appString(R.string.text_protocol_fixed_trials_independent_random_x_per_trial_8b5cae, (result.planned)))
                            out.appendLine(appString(R.string.text_answered_dc317a, (result.answered)))
                            out.appendLine(appString(R.string.text_correct_57cbf9, (result.correct)))
                            out.appendLine(appString(R.string.text_interrupted_403186, (result.interrupted)))
                            result.probability?.let { out.appendLine(appString(R.string.text_one_sided_chance_probability_56287c, (it))) }
                            out.appendLine(appString(R.string.text_matching_stereo_rms_over_the_same_selection_within_0_1_db_b1073d))
                            out.appendLine(appString(R.string.text_output_hz_float_pcm_same_route_and_volume_e7768a, (state.rate)))
                            out.appendLine(appString(R.string.text_selection_frames_b3c1b7, (state.selectionFrames)))
                            state.levels?.let { levels ->
                                out.appendLine(appString(R.string.text_linear_gains_a_b_0f2e4c, (levels.gainA), (levels.gainB)))
                                out.appendLine(appString(R.string.text_measured_rms_a_b_difference_db_afeb51, (levels.rmsA), (levels.rmsB), (levels.residualDb)))
                            }
                            result.trials.forEach { trial ->
                                out.appendLine(appString(R.string.text_trial_guess_x_e02d03, (trial.number), (if (trial.guessA) "A" else "B"), (if (trial.xIsA) "A" else "B")))
                            }
                        }
                    }
                    appString(R.string.text_result_exported_771e11)
                }.getOrElse { appString(R.string.text_could_not_export_the_result_151bea) }
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
        SettingsTopBar(appString(R.string.text_compare_presets_b7d8ea), onBack = { model.stop(); onBack() })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.active && !state.preparing) {
                item {
                    PresetComparisonChoice("A", aId, library.presets) { aId = it }
                    Spacer(Modifier.height(12.dp))
                    PresetComparisonChoice("B", bId, library.presets) { bId = it }
                    TextButton(onClick = onOpenPresets) { Text(appString(R.string.text_saved_processing_presets_f22d4b)) }
                }
                item {
                    if (sourceLabel.isNotBlank()) Text(sourceLabel, style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { pick.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_choose_audio_ea6db7)) }
                    if (currentSource.isNotBlank() && source != currentSource) TextButton(onClick = {
                        source = currentSource; sourceLabel = currentTitle
                    }) { Text(appString(R.string.text_use_current_track_5c31d5)) }
                    OutlinedTextField(value = start, onValueChange = { start = it.filter(Char::isDigit).take(5) },
                        label = { Text(appString(R.string.text_start_at_seconds_d4e717)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(appString(R.string.text_blind_abx_16_trials_eab67b))
                        Switch(checked = blind, onCheckedChange = { blind = it })
                    }
                    Text(appString(R.string.text_up_to_15_seconds_rms_matched_within_0_1_db_shorter_at_high_sample_64e480), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        val a = library.presets.first { it.id == aId }; val b = library.presets.first { it.id == bId }
                        model.start(Uri.parse(source), start.toIntOrNull() ?: 0, a, b, blind)
                    }, enabled = source.isNotBlank() && aId.isNotBlank() && bId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (blind) appString(R.string.text_start_abx_d3d188) else appString(R.string.text_start_a_b_6348cf))
                    }
                }
            }
            if (state.preparing) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(appString(R.string.text_preparing_both_presets_04bd1f))
                TextButton(onClick = { model.stop() }) { Text(appString(R.string.text_cancel_77dfd2)) }
            }
            if (state.active) {
                item {
                    if (state.blind) Text(appString(R.string.text_trial_of_1d28cf, (state.answered + 1), (state.planned)), style = MaterialTheme.typography.titleLarge)
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
                        OutlinedButton(onClick = { model.guess(true) }, Modifier.weight(1f)) { Text(appString(R.string.text_x_is_a_4e9e48)) }
                        OutlinedButton(onClick = { model.guess(false) }, Modifier.weight(1f)) { Text(appString(R.string.text_x_is_b_76d9a9)) }
                    }
                }
                else item {
                    state.levels?.let { Text(String.format(Locale.ROOT, appString(R.string.text_rms_difference_3f_db_288abf), it.residualDb)) }
                }
                item { OutlinedButton(onClick = { model.stop() }, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_stop_9e2534)) } }
            }
            state.result?.let { result -> item {
                Text(if (result.interrupted) appString(R.string.text_test_interrupted_ae8398) else appString(R.string.text_correct_out_of_763a9d, (result.correct), (result.planned)), style = MaterialTheme.typography.titleLarge)
                result.probability?.let { Text(String.format(Locale.ROOT, appString(R.string.text_chance_probability_4f_3166a5), it)) }
                if (result.interrupted) Text(appString(R.string.text_answers_recorded_no_significance_result_5be219, (result.answered)))
                TextButton(onClick = { export.launch("aurora-abx.txt") }) { Text(appString(R.string.text_export_result_efc657)) }
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
            Text("$label · ${presets.firstOrNull { it.id == selected }?.name ?: appString(R.string.text_save_a_preset_first_0d40db)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { preset -> DropdownMenuItem(text = { Text(preset.name) }, onClick = { onSelect(preset.id); expanded = false }) }
        }
    }
}
