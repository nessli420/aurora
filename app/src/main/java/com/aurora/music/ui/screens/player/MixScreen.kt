package com.aurora.music.ui.screens.player

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.mix.*
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.viewmodel.MixViewModel
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixScreen(vm: MixViewModel, queue: List<Song>, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val project = state.project
    var picker by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var master by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf<MixProject?>(null) }
    var newConfirm by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<MixProject?>(null) }
    var layered by remember { mutableStateOf(false) }
    var trackList by remember { mutableStateOf(false) }
    var autoConfirm by remember { mutableStateOf(false) }
    val selected = project.clips.firstOrNull { it.id == state.selectedId } ?: project.clips.firstOrNull()
    val active = playback.projectId == project.id
    val position = if (active) playback.positionSec else 0f
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    BackHandler { onClose() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, appString(R.string.text_close_mix_studio_bb83bf)) }
                        Column(Modifier.weight(1f).clickable { rename = true }.padding(vertical = 8.dp)) {
                            Text(appString(R.string.text_mix_studio_cdc446), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(project.name + if (state.dirty) " ·" else "", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { saved = true }) { Icon(Icons.Default.FolderOpen, appString(R.string.text_saved_mixes_fe1986)) }
                        FilledTonalIconButton(onClick = vm::save, enabled = !state.busy && project.clips.isNotEmpty()) { Icon(Icons.Default.Save, appString(R.string.text_save_mix_bba953)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(appString(R.string.tracks_duration, appPlural(R.plurals.track_count, (project.clips.size)), (time(project.durationSec))), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.Default.Undo, appString(R.string.text_undo_edit_7e5f02)) }
                        IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.Default.Redo, appString(R.string.text_redo_edit_15178f)) }
                        IconButton(onClick = { master = true }) { Icon(Icons.Default.Tune, appString(R.string.text_master_controls_1c46ae)) }
                    }
                }
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Slider(value = position.coerceIn(0f, project.durationSec.coerceAtLeast(1f)), onValueChange = vm::seek,
                            enabled = active, valueRange = 0f..project.durationSec.coerceAtLeast(1f),
                            modifier = Modifier.semantics { contentDescription = appString(R.string.text_mix_playhead_dc2dd4) })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(time(position), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = vm::stop, enabled = active) { Icon(Icons.Default.Stop, appString(R.string.text_stop_mix_and_return_to_queue_eeb40f)) }
                            FilledIconButton(onClick = { if (active && playback.error == null) vm.toggle() else vm.play(position) }, enabled = project.clips.size >= 2 && !state.busy && !state.building,
                                modifier = Modifier.size(56.dp).semantics { contentDescription = if (active && playback.playing) appString(R.string.text_pause_mix_cf2e8e) else appString(R.string.text_play_mix_7ae7ed) }) {
                                if (active && playback.buffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Icon(if (active && playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (active && playback.playing) appString(R.string.text_pause_mix_cf2e8e) else appString(R.string.text_play_mix_7ae7ed))
                            }
                            IconButton(onClick = { vm.edit { it.copy(loop = !it.loop) } }) {
                                Icon(Icons.Default.Repeat, appString(R.string.text_loop_mix_721c7e, (if (project.loop) appString(R.string.text_on_e0049a) else appString(R.string.text_off_e3de5a))), tint = if (project.loop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(time(project.durationSec), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.building) item {
                    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text(appString(R.string.text_creating_transitions_12f0bd), style = MaterialTheme.typography.titleMedium)
                        Text("${state.buildDone} / ${state.buildTotal} · ${state.buildLabel}", maxLines = 2)
                        LinearProgressIndicator(progress = { if (state.buildTotal > 0) (state.buildDone + state.analysisProgress) / state.buildTotal else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        TextButton(onClick = vm::cancelAutoMix) { Text(appString(R.string.text_cancel_keep_current_mix_d10ab9)) }
                    } }
                }
                if (playback.error != null) item {
                    Text(playback.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (project.clips.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.GraphicEq, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(18.dp))
                        Text(appString(R.string.text_make_room_for_your_own_mix_7600ed), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(appString(R.string.text_blend_transitions_or_layer_tracks_shape_every_entrance_exit_and_s_022092), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(18.dp))
                        if (queue.isNotEmpty()) OutlinedButton(onClick = { vm.seed(queue) }) { Text(appString(R.string.text_start_from_queue_237018)) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (project.clips.isEmpty()) appString(R.string.text_your_tracks_226210) else appString(R.string.text_arrangement_48db54), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = { picker = true }, enabled = project.clips.size < MixProject.MAX_TRACKS) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(appString(R.string.text_add_track_316cad))
                        }
                    }
                }
                if (project.clips.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { trackList = true }) { Text(appString(R.string.text_all_tracks_d1ccb9, (project.clips.size))) }
                        FilledTonalButton(onClick = { autoConfirm = true }, enabled = !state.building && project.clips.size >= 2) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(appString(R.string.text_auto_mix_2947fe))
                        }
                    }
                    MixTimeline(project, state.analyses, selected?.id.orEmpty(), position) { id ->
                        vm.select(id)
                    }
                }
                if (selected != null) item(key = selected.id) {
                    if (selected.transitionNote.isNotBlank()) Text(selected.transitionNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                        Text(appString(R.string.text_vocals_backing_d8ce42), style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StemMode.entries.forEach { mode -> FilterChip(selected.stem == mode,
                                onClick = { vm.chooseStem(selected, mode) }, enabled = !state.building,
                                label = { Text(mode.label) }) }
                        }
                        if (state.separating == selected.id) {
                            Text("${state.separationLabel} · ${(state.separationProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp))
                            LinearProgressIndicator(progress = { state.separationProgress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                            TextButton(onClick = vm::cancelSeparation) { Text(appString(R.string.text_cancel_separation_d3a810)) }
                        } else Text(if (state.modelReady) appString(R.string.text_isolate_the_singer_or_remove_vocals_neural_separation_runs_on_thi_0c5736)
                            else appString(R.string.text_choose_vocals_or_backing_to_download_the_64_mb_uvr_model_once_and_f75596),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(appString(R.string.text_uvr_anjok07_aufr33_cached_stems_may_contain_separation_artifacts_1cd68b), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    val analysis = state.analyses[selected.song.id]
                    ClipEditor(selected, project, state.analyses, analysis, state.analyzing == selected.id, state.analysisProgress,
                        onChange = vm::updateClip, onAnalyze = { vm.analyze(selected) }, onCancelAnalysis = vm::cancelAnalysis,
                        onRemove = { vm.remove(selected.id) }, onPreview = { vm.play((selected.startSec - 3).coerceAtLeast(0f)) },
                        onTempoMatch = { vm.matchTempo(selected) }, onBars = { vm.alignTransition(selected, it) })
                }
                item { Text(appString(R.string.text_analysis_reads_the_audio_stream_and_keeps_a_waveform_and_tempo_es_6995bf),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (autoConfirm) AlertDialog(onDismissRequest = { autoConfirm = false }, title = { Text(appString(R.string.text_create_automatic_transitions_c31cb5)) },
        text = { Column {
            Text(appString(R.string.text_rebuild_timing_and_fades_from_sonic_analysis_keeping_track_order_bb56e7))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.tempoMatch, vm::setTempoMatch); Text(appString(R.string.text_match_close_tempos_up_to_6_8b3c61))
            }
        } }, confirmButton = { TextButton(onClick = { autoConfirm = false; vm.autoMix() }) { Text(appString(R.string.text_create_transitions_47dcdf)) } },
        dismissButton = { TextButton(onClick = { autoConfirm = false }) { Text(appString(R.string.text_cancel_77dfd2)) } })
    if (trackList) ModalBottomSheet(onDismissRequest = { trackList = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(appString(R.string.text_mix_tracklist_a8a9ff), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxHeight(.8f)) {
            items(project.clips, key = { it.id }) { clip ->
                ListItem(headlineContent = { Text(clip.song.title) }, supportingContent = { Text("${time(clip.startSec)} · ${clip.song.artist}") },
                    leadingContent = { Artwork(clip.song.artworkUrl, clip.song.accent, Modifier.size(48.dp)) },
                    modifier = Modifier.clickable { vm.select(clip.id); trackList = false })
            }
        }
    }
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
            Text(appString(R.string.text_add_to_your_mix_927f93), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!layered, { layered = false }, label = { Text(appString(R.string.text_blend_after_last_68db27)) })
                FilterChip(layered, { layered = true }, label = { Text(appString(R.string.text_layer_from_start_25e830)) })
            }
            OutlinedTextField(state.query, vm::search, Modifier.fillMaxWidth(), label = { Text(appString(R.string.text_search_your_library_d9fa3f)) },
                singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                val songs = (if (state.query.isBlank()) queue + state.songs else state.songs).distinctBy { it.id }
                if (!state.searching && songs.isEmpty()) item { Text(appString(R.string.text_no_songs_found_try_another_search_or_check_your_library_connectio_b6d0ed), Modifier.padding(16.dp)) }
                items(songs, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().clickable { vm.add(song, layered); picker = false }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song.artworkUrl, MaterialTheme.colorScheme.primary, Modifier.size(48.dp), corner = 8.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.Add, appString(R.string.text_add_b61b5a, (song.title)))
                    }
                }
            }
        }
    }
    if (saved) ModalBottomSheet(onDismissRequest = { saved = false }) {
        LazyColumn(contentPadding = PaddingValues(20.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.text_your_mixes_7e74f6), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { if (state.dirty) newConfirm = true else vm.newMix(); saved = false }) { Text(appString(R.string.text_new_mix_48ef4a)) }
            } }
            if (state.saved.isEmpty()) item { Text(appString(R.string.text_save_your_first_mix_to_find_it_here_9d6680), Modifier.padding(vertical = 24.dp)) }
            items(state.saved, key = { it.id }) { mix ->
                ListItem(headlineContent = { Text(mix.name) }, supportingContent = { Text(appString(R.string.tracks_duration, appPlural(R.plurals.track_count, (mix.clips.size)), (time(mix.durationSec)))) },
                    trailingContent = { IconButton(onClick = { delete = mix }) { Icon(Icons.Default.DeleteOutline, appString(R.string.text_delete_48c860, (mix.name))) } },
                    modifier = Modifier.clickable { if (state.dirty) replace = mix else vm.open(mix); saved = false })
            }
        }
    }
    if (master) ModalBottomSheet(onDismissRequest = { master = false }) {
        Column(Modifier.padding(24.dp)) {
            Text(appString(R.string.text_master_controls_1c46ae), style = MaterialTheme.typography.headlineSmall)
            ValueSlider(appString(R.string.text_master_level_2293c4), project.masterDb, -36f..0f, appString(R.string.text_db_e44622)) { value -> vm.edit { it.copy(masterDb = value) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.text_overlap_headroom_c1a0a1), fontWeight = FontWeight.SemiBold)
                    Text(appString(R.string.text_balances_overlapping_levels_to_prevent_overload_each_track_also_h_e99ff4), style = MaterialTheme.typography.bodySmall)
                }
                Switch(project.protectPeaks, { value -> vm.edit { it.copy(protectPeaks = value) } })
            }
            Text(appString(R.string.text_mix_eq_and_pitch_controls_use_processed_audio_through_your_select_253b70), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (rename) {
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text(appString(R.string.text_name_your_mix_039c07)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(appString(R.string.text_mix_name_dff04e)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.edit { it.copy(name = name) }; rename = false }) { Text(appString(R.string.text_done_e9b450)) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(appString(R.string.text_cancel_77dfd2)) } })
    }
    if (replace != null || newConfirm) AlertDialog(onDismissRequest = { replace = null; newConfirm = false },
        title = { Text(appString(R.string.text_replace_unsaved_edits_8ff8f2)) }, text = { Text(appString(R.string.text_save_this_mix_first_if_you_want_to_keep_your_changes_655e48)) },
        confirmButton = { TextButton(onClick = { replace?.let(vm::open) ?: vm.newMix(); replace = null; newConfirm = false }) { Text(appString(R.string.text_replace_a7cf7b)) } },
        dismissButton = { TextButton(onClick = { replace = null; newConfirm = false }) { Text(appString(R.string.text_keep_editing_d4d9e3)) } })
    delete?.let { mix -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(appString(R.string.text_delete_137cdc, (mix.name))) },
        text = { Text(appString(R.string.text_this_removes_the_saved_mix_your_songs_stay_in_your_library_1951d2)) },
        confirmButton = { TextButton(onClick = { vm.delete(mix); delete = null }) { Text(appString(R.string.text_delete_f6fdbe)) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text(appString(R.string.text_cancel_77dfd2)) } }) }
}

@Composable
private fun MixTimeline(project: MixProject, analyses: Map<String, MixAnalysis>, selected: String, position: Float, onSelect: (String) -> Unit) {
    val selectedIndex = project.clips.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val visible = if (project.clips.size <= 8) project.clips.indices.toList() else
        ((selectedIndex - 1).coerceAtLeast(0)..(selectedIndex + 1).coerceAtMost(project.clips.lastIndex)).toList()
    val start = if (project.clips.size <= 8) 0f else visible.minOf { project.clips[it].startSec }
    val end = if (project.clips.size <= 8) project.durationSec else visible.maxOf { project.clips[it].endSec }
    val duration = (end - start).coerceAtLeast(1f)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val foreground = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(start), style = MaterialTheme.typography.labelSmall)
            Text(time(start + duration / 2), style = MaterialTheme.typography.labelSmall)
            Text(time(end), style = MaterialTheme.typography.labelSmall)
        }
        if (project.clips.size > 8) Text(appString(R.string.text_transition_around_track_choose_a_track_from_all_tracks_f3487f, (selectedIndex + 1)), style = MaterialTheme.typography.labelSmall)
        visible.forEach { index ->
            val clip = project.clips[index]
            val color = if (index % 2 == 0) primary else secondary
            val analysis = analyses[clip.song.id]
            val audibleClip = remember(clip) { clip.copy(muted = false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(clip.id) }
                .then(if (clip.id == selected) Modifier.border(1.dp, color, RoundedCornerShape(8.dp)) else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text("${index + 1}  ${clip.song.title}${if (clip.muted) appString(R.string.text_muted_b6a778) else if (clip.solo) appString(R.string.text_solo_6d717d) else ""}",
                    style = MaterialTheme.typography.labelMedium, color = if (clip.id == selected) color else foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.fillMaxWidth().height(38.dp).semantics { contentDescription = appString(R.string.text_track_starts_length_7f84c5, (index + 1), (time(clip.startSec)), (time(clip.durationSec))) }) {
                    val left = (clip.startSec - start) / duration * size.width
                    val width = clip.durationSec / duration * size.width
                    drawRect(color.copy(alpha = if (clip.muted) 0.06f else 0.16f), Offset(left, 0f), androidx.compose.ui.geometry.Size(width, size.height))
                    if (analysis != null) {
                        val count = width.toInt().coerceAtLeast(1)
                        for (x in 0 until count step 2) {
                            val source = clip.cueInSec + x.toFloat() / count * (clip.cueOutSec - clip.cueInSec)
                            val amplitude = analysis.peaks.getOrElse((source / analysis.seconds * analysis.peaks.size).toInt()) { 0f }
                            val h = amplitude * size.height * 0.45f
                            drawLine(color.copy(alpha = 0.6f), Offset(left + x, size.height / 2 - h), Offset(left + x, size.height / 2 + h), 1.5f)
                        }
                    }
                    val path = Path()
                    for (x in 0..100) {
                        val local = clip.durationSec * x / 100f
                        val gain = MixMath.envelope(audibleClip, clip.startSec + local.coerceAtMost(clip.durationSec - 0.001f))
                        val y = size.height - gain * (size.height - 4)
                        if (x == 0) path.moveTo(left, y) else path.lineTo(left + x / 100f * width, y)
                    }
                    drawPath(path, color, style = Stroke(2f))
                    val playX = (position - start) / duration * size.width
                    if (position in start..end) drawLine(foreground, Offset(playX, 0f), Offset(playX, size.height), 1.5f)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipEditor(clip: MixClip, project: MixProject, analyses: Map<String, MixAnalysis>, analysis: MixAnalysis?, analyzing: Boolean, progress: Float,
    onChange: (MixClip) -> Unit, onAnalyze: () -> Unit, onCancelAnalysis: () -> Unit, onRemove: () -> Unit,
    onPreview: () -> Unit, onTempoMatch: () -> Unit, onBars: (Int) -> Unit) {
    var tab by remember(clip.id) { mutableIntStateOf(0) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(clip.song.artworkUrl, MaterialTheme.colorScheme.primary, Modifier.size(56.dp), corner = 10.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(clip.song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(clip.song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, appString(R.string.text_remove_from_mix_a1651c, (clip.song.title))) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(clip.muted, { onChange(clip.copy(muted = !clip.muted)) }, label = { Text(appString(R.string.text_mute_0f0973)) })
                FilterChip(clip.solo, { onChange(clip.copy(solo = !clip.solo)) }, label = { Text(appString(R.string.text_solo_9fc93a)) })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onPreview, enabled = project.clips.size >= 2) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Text(appString(R.string.text_preview_f1fbb2)) }
            }
            if (analyzing) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.text_reading_audio_0b406f, ((progress * 100).toInt())), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelAnalysis) { Text(appString(R.string.text_cancel_77dfd2)) }
                }
            } else if (analysis == null) {
                OutlinedButton(onClick = onAnalyze, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.GraphicEq, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(appString(R.string.text_analyze_waveform_tempo_2c12d4)) }
            } else {
                Text(if (analysis.bpm > 0) appString(R.string.text_bpm_tempo_estimate_db_rms_652fe5, (number(analysis.bpm, 0)), (if (analysis.confidence > 0.45f) "strong" else "tentative"), (number(analysis.rmsDb)))
                    else appString(R.string.text_waveform_ready_tempo_uncertain_df52c4), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(appString(R.string.text_timing_098024), appString(R.string.text_fades_6d1e7d), appString(R.string.text_sound_b4e3ef)).forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
            }
            when (tab) {
                0 -> {
                    ValueSlider(appString(R.string.text_starts_in_mix_0762f2), clip.startSec, 0f..max(60f, project.durationSec), "s") { onChange(clip.copy(startSec = it)) }
                    ValueSlider(appString(R.string.text_cue_in_52bfaa), clip.cueInSec, 0f..max(0.1f, clip.cueOutSec - 0.1f), "s") { onChange(clip.copy(cueInSec = it)) }
                    ValueSlider(appString(R.string.text_cue_out_d4b49c), clip.cueOutSec, (clip.cueInSec + 0.1f)..clip.song.durationSec.toFloat().coerceAtLeast(clip.cueInSec + 0.2f), "s") { onChange(clip.copy(cueOutSec = it)) }
                    Text(appString(R.string.text_tap_a_value_to_enter_an_exact_time_cue_points_refer_to_the_source_842e53), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (project.clips.indexOf(clip) > 0) {
                        Text(appString(R.string.text_transition_from_previous_track_80635a), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(4, 8, 16).forEach { bars -> OutlinedButton(onClick = { onBars(bars) }) { Text(appPlural(R.plurals.bar_count, (bars))) } }
                        }
                        Text(appString(R.string.text_uses_the_previous_track_s_tempo_estimate_falls_back_to_8_seconds_70019c), style = MaterialTheme.typography.bodySmall)
                    }
                }
                1 -> {
                    TransitionPreview(project, clip, analyses)
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(clip.bassSwap, { onChange(clip.copy(bassSwap = it)) }); Spacer(Modifier.width(10.dp)); Text(appString(R.string.text_exchange_bass_during_fades_dc9e15)) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FadeCurve.entries.forEach { curve -> FilterChip(clip.curve == curve, { onChange(clip.copy(curve = curve)) }, label = { Text(curve.label) }) }
                    }
                    ValueSlider(appString(R.string.text_fade_in_6b46f2), clip.fadeInSec, 0f..min(60f, clip.durationSec), "s") { onChange(clip.copy(fadeInSec = it)) }
                    ValueSlider(appString(R.string.text_fade_out_72f7b2), clip.fadeOutSec, 0f..min(60f, clip.durationSec), "s") { onChange(clip.copy(fadeOutSec = it)) }
                    ValueSlider(appString(R.string.text_entrance_shape_a03f73), clip.fadeInBend, 0.25f..4f, "×", decimals = 2) { onChange(clip.copy(fadeInBend = it)) }
                    ValueSlider(appString(R.string.text_exit_shape_5e1931), clip.fadeOutBend, 0.25f..4f, "×", decimals = 2) { onChange(clip.copy(fadeOutBend = it)) }
                    Text(appString(R.string.text_the_line_in_the_arrangement_shows_this_track_s_volume_envelope_ma_689eda), style = MaterialTheme.typography.bodySmall)
                }
                2 -> {
                    ValueSlider(appString(R.string.text_track_level_d1a7fd), clip.gainDb, -36f..0f, appString(R.string.text_db_e44622), live = true) { onChange(clip.copy(gainDb = it)) }
                    ValueSlider(appString(R.string.text_pan_left_right_ef4fc7), clip.pan, -1f..1f, "", live = true) { onChange(clip.copy(pan = it)) }
                    ValueSlider(appString(R.string.text_bass_99cb7d), clip.bassDb, -24f..6f, appString(R.string.text_db_e44622), live = true) { onChange(clip.copy(bassDb = it)) }
                    ValueSlider(appString(R.string.text_mid_9a6af7), clip.midDb, -24f..6f, appString(R.string.text_db_e44622), live = true) { onChange(clip.copy(midDb = it)) }
                    ValueSlider(appString(R.string.text_treble_f66bec), clip.trebleDb, -24f..6f, appString(R.string.text_db_e44622), live = true) { onChange(clip.copy(trebleDb = it)) }
                    ValueSlider(appString(R.string.text_tempo_899658), clip.speed, 0.5f..2f, "×", decimals = 2) { onChange(clip.copy(speed = it)) }
                    ValueSlider(appString(R.string.text_pitch_a6c2d0), clip.pitchSemitones, -12f..12f, "st") { onChange(clip.copy(pitchSemitones = it)) }
                    if (analysis?.bpm != null && analysis.bpm > 0 && project.clips.indexOf(clip) > 0) OutlinedButton(onClick = onTempoMatch) { Text(appString(R.string.text_match_previous_track_s_tempo_70bf60)) }
                    Text(appString(R.string.text_tempo_preserves_pitch_eq_boosts_reserve_headroom_before_processin_2b088a), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TransitionPreview(project: MixProject, selected: MixClip, analyses: Map<String, MixAnalysis>) {
    val index = project.clips.indexOf(selected)
    val previous = project.clips.getOrNull(index - 1)
    val clips = listOfNotNull(previous, selected)
    val start = (selected.startSec - 2f).coerceAtLeast(0f)
    val end = (selected.startSec + max(8f, selected.fadeInSec) + 2).coerceAtMost(project.durationSec)
    val span = (end - start).coerceAtLeast(1f)
    val accent = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.tertiary
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(appString(R.string.text_transition_f70dd0, (time(start)), (time(end))), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        clips.forEachIndexed { i, c ->
            val color = if (i == 0 && previous != null) second else accent
            val audibleClip = remember(c) { c.copy(muted = false) }
            Text(c.song.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color)
            Canvas(Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp)
                .semantics { contentDescription = appString(R.string.text_transition_envelope_from_to_dc4235, (c.song.title), (time(start)), (time(end))) }) {
                val analysis = analyses[c.song.id]
                for (x in 0 until size.width.toInt() step 3) {
                    val mixTime = start + x / size.width * span
                    val local = (mixTime - c.startSec) * c.speed + c.cueInSec
                    if (mixTime < c.startSec || mixTime >= c.endSec) continue
                    val peak = if (analysis == null) 0f else analysis.peaks.getOrElse((local / analysis.seconds * analysis.peaks.size).toInt()) { 0f }
                    val h = peak * size.height * 0.48f
                    if (analysis != null) drawLine(color.copy(alpha = 0.35f), Offset(x.toFloat(), size.height / 2 - h), Offset(x.toFloat(), size.height / 2 + h), 2f)
                }
                val path = Path()
                for (x in 0..200) {
                    val mixTime = start + x / 200f * span
                    val gain = MixMath.envelope(audibleClip, mixTime)
                    val y = size.height - 2 - gain * (size.height - 4)
                    if (x == 0) path.moveTo(0f, y) else path.lineTo(size.width * x / 200f, y)
                }
                drawPath(path, color, style = Stroke(3f))
            }
        }
        if (clips.any { analyses[it.song.id] == null }) Text(appString(R.string.text_analyze_each_track_to_reveal_its_waveform_here_b126f7), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, decimals: Int = 1, live: Boolean = false, onValue: (Float) -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { edit = true }) { Text("${number(drag ?: value, decimals)} $unit") }
        }
        Slider((drag ?: value).coerceIn(range), { drag = it; if (live) onValue(it) }, valueRange = range,
            onValueChangeFinished = { drag?.let(onValue); drag = null }, modifier = Modifier.semantics { contentDescription = label })
    }
    if (edit) {
        var text by remember { mutableStateOf(number(value, decimals)) }
        val parsed = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in range }
        AlertDialog(onDismissRequest = { edit = false }, title = { Text(label) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(unit.ifBlank { appString(R.string.text_value_8dce17) }) },
                supportingText = { Text(appString(R.string.text_to_e8d7f8, (number(range.start)), (number(range.endInclusive)))) },
                isError = parsed == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
            confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onValue); edit = false }) { Text(appString(R.string.text_apply_cfea41)) } },
            dismissButton = { TextButton(onClick = { edit = false }) { Text(appString(R.string.text_cancel_77dfd2)) } })
    }
}

private fun time(seconds: Float): String = "${seconds.toInt() / 60}:${(seconds.toInt() % 60).toString().padStart(2, '0')}"
private fun number(value: Float, decimals: Int = 1) = String.format(Locale.US, "%.${decimals}f", value)
