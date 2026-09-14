package com.aurora.music.ui.screens.player

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
                        IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Close mix studio") }
                        Column(Modifier.weight(1f).clickable { rename = true }.padding(vertical = 8.dp)) {
                            Text("MIX STUDIO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(project.name + if (state.dirty) " ·" else "", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { saved = true }) { Icon(Icons.Default.FolderOpen, "Saved mixes") }
                        FilledTonalIconButton(onClick = vm::save, enabled = !state.busy && project.clips.isNotEmpty()) { Icon(Icons.Default.Save, "Save mix") }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${project.clips.size} tracks · ${time(project.durationSec)}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.Default.Undo, "Undo edit") }
                        IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.Default.Redo, "Redo edit") }
                        IconButton(onClick = { master = true }) { Icon(Icons.Default.Tune, "Master controls") }
                    }
                }
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Slider(value = position.coerceIn(0f, project.durationSec.coerceAtLeast(1f)), onValueChange = vm::seek,
                            enabled = active, valueRange = 0f..project.durationSec.coerceAtLeast(1f),
                            modifier = Modifier.semantics { contentDescription = "Mix playhead" })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(time(position), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = vm::stop, enabled = active) { Icon(Icons.Default.Stop, "Stop mix and return to queue") }
                            FilledIconButton(onClick = { if (active && playback.error == null) vm.toggle() else vm.play(position) }, enabled = project.clips.size >= 2 && !state.busy && !state.building,
                                modifier = Modifier.size(56.dp).semantics { contentDescription = if (active && playback.playing) "Pause mix" else "Play mix" }) {
                                if (active && playback.buffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Icon(if (active && playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (active && playback.playing) "Pause mix" else "Play mix")
                            }
                            IconButton(onClick = { vm.edit { it.copy(loop = !it.loop) } }) {
                                Icon(Icons.Default.Repeat, "Loop mix ${if (project.loop) "on" else "off"}", tint = if (project.loop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("Creating transitions", style = MaterialTheme.typography.titleMedium)
                        Text("${state.buildDone} / ${state.buildTotal} · ${state.buildLabel}", maxLines = 2)
                        LinearProgressIndicator(progress = { if (state.buildTotal > 0) (state.buildDone + state.analysisProgress) / state.buildTotal else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        TextButton(onClick = vm::cancelAutoMix) { Text("Cancel · keep current mix") }
                    } }
                }
                if (playback.error != null) item {
                    Text(playback.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (project.clips.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.GraphicEq, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(18.dp))
                        Text("Make room for your own mix", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Blend transitions or layer tracks. Shape every entrance, exit and sound.", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(18.dp))
                        if (queue.isNotEmpty()) OutlinedButton(onClick = { vm.seed(queue) }) { Text("Start from queue") }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (project.clips.isEmpty()) "YOUR TRACKS" else "ARRANGEMENT", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = { picker = true }, enabled = project.clips.size < MixProject.MAX_TRACKS) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add track")
                        }
                    }
                }
                if (project.clips.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { trackList = true }) { Text("All ${project.clips.size} tracks") }
                        FilledTonalButton(onClick = { autoConfirm = true }, enabled = !state.building && project.clips.size >= 2) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Auto mix")
                        }
                    }
                    MixTimeline(project, state.analyses, selected?.id.orEmpty(), position) { id ->
                        vm.select(id)
                    }
                }
                if (selected != null) item(key = selected.id) {
                    if (selected.transitionNote.isNotBlank()) Text(selected.transitionNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                        Text("Vocals & backing", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StemMode.entries.forEach { mode -> FilterChip(selected.stem == mode,
                                onClick = { vm.chooseStem(selected, mode) }, enabled = !state.building,
                                label = { Text(mode.label) }) }
                        }
                        if (state.separating == selected.id) {
                            Text("${state.separationLabel} · ${(state.separationProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp))
                            LinearProgressIndicator(progress = { state.separationProgress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                            TextButton(onClick = vm::cancelSeparation) { Text("Cancel separation") }
                        } else Text(if (state.modelReady) "Isolate the singer or remove vocals. Neural separation runs on this device and may take several minutes."
                            else "Choose Vocals or Backing to download the 64 MB UVR model once and separate on this device. Audio stays private.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("UVR · Anjok07 & aufr33 · cached stems may contain separation artifacts", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    val analysis = state.analyses[selected.song.id]
                    ClipEditor(selected, project, state.analyses, analysis, state.analyzing == selected.id, state.analysisProgress,
                        onChange = vm::updateClip, onAnalyze = { vm.analyze(selected) }, onCancelAnalysis = vm::cancelAnalysis,
                        onRemove = { vm.remove(selected.id) }, onPreview = { vm.play((selected.startSec - 3).coerceAtLeast(0f)) },
                        onTempoMatch = { vm.matchTempo(selected) }, onBars = { vm.alignTransition(selected, it) })
                }
                item { Text("Analysis reads the audio stream and keeps a waveform and tempo estimate. No saved download is required.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (autoConfirm) AlertDialog(onDismissRequest = { autoConfirm = false }, title = { Text("Create automatic transitions") },
        text = { Column {
            Text("Rebuild timing and fades from Sonic analysis, keeping track order. You can undo this edit.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.tempoMatch, vm::setTempoMatch); Text("Match close tempos (up to 6%)")
            }
        } }, confirmButton = { TextButton(onClick = { autoConfirm = false; vm.autoMix() }) { Text("Create transitions") } },
        dismissButton = { TextButton(onClick = { autoConfirm = false }) { Text("Cancel") } })
    if (trackList) ModalBottomSheet(onDismissRequest = { trackList = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Mix tracklist", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
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
            Text("Add to your mix", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!layered, { layered = false }, label = { Text("Blend after last") })
                FilterChip(layered, { layered = true }, label = { Text("Layer from start") })
            }
            OutlinedTextField(state.query, vm::search, Modifier.fillMaxWidth(), label = { Text("Search your library") },
                singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                val songs = (if (state.query.isBlank()) queue + state.songs else state.songs).distinctBy { it.id }
                if (!state.searching && songs.isEmpty()) item { Text("No songs found. Try another search or check your library connection.", Modifier.padding(16.dp)) }
                items(songs, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().clickable { vm.add(song, layered); picker = false }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song.artworkUrl, MaterialTheme.colorScheme.primary, Modifier.size(48.dp), corner = 8.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.Add, "Add ${song.title}")
                    }
                }
            }
        }
    }
    if (saved) ModalBottomSheet(onDismissRequest = { saved = false }) {
        LazyColumn(contentPadding = PaddingValues(20.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your mixes", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { if (state.dirty) newConfirm = true else vm.newMix(); saved = false }) { Text("New mix") }
            } }
            if (state.saved.isEmpty()) item { Text("Save your first mix to find it here.", Modifier.padding(vertical = 24.dp)) }
            items(state.saved, key = { it.id }) { mix ->
                ListItem(headlineContent = { Text(mix.name) }, supportingContent = { Text("${mix.clips.size} tracks · ${time(mix.durationSec)}") },
                    trailingContent = { IconButton(onClick = { delete = mix }) { Icon(Icons.Default.DeleteOutline, "Delete ${mix.name}") } },
                    modifier = Modifier.clickable { if (state.dirty) replace = mix else vm.open(mix); saved = false })
            }
        }
    }
    if (master) ModalBottomSheet(onDismissRequest = { master = false }) {
        Column(Modifier.padding(24.dp)) {
            Text("Master controls", style = MaterialTheme.typography.headlineSmall)
            ValueSlider("Master level", project.masterDb, -36f..0f, "dB") { value -> vm.edit { it.copy(masterDb = value) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Overlap headroom", fontWeight = FontWeight.SemiBold)
                    Text("Balances overlapping levels to prevent overload. Each track also has a safety limiter.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(project.protectPeaks, { value -> vm.edit { it.copy(protectPeaks = value) } })
            }
            Text("Mix EQ and pitch controls use processed audio through your selected Android output.", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (rename) {
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Name your mix") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Mix name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.edit { it.copy(name = name) }; rename = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    }
    if (replace != null || newConfirm) AlertDialog(onDismissRequest = { replace = null; newConfirm = false },
        title = { Text("Replace unsaved edits?") }, text = { Text("Save this mix first if you want to keep your changes.") },
        confirmButton = { TextButton(onClick = { replace?.let(vm::open) ?: vm.newMix(); replace = null; newConfirm = false }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { replace = null; newConfirm = false }) { Text("Keep editing") } })
    delete?.let { mix -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${mix.name}?") },
        text = { Text("This removes the saved mix. Your songs stay in your library.") },
        confirmButton = { TextButton(onClick = { vm.delete(mix); delete = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
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
        if (project.clips.size > 8) Text("Transition around track ${selectedIndex + 1} · choose a track from All tracks", style = MaterialTheme.typography.labelSmall)
        visible.forEach { index ->
            val clip = project.clips[index]
            val color = if (index % 2 == 0) primary else secondary
            val analysis = analyses[clip.song.id]
            val audibleClip = remember(clip) { clip.copy(muted = false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(clip.id) }
                .then(if (clip.id == selected) Modifier.border(1.dp, color, RoundedCornerShape(8.dp)) else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text("${index + 1}  ${clip.song.title}${if (clip.muted) " · muted" else if (clip.solo) " · solo" else ""}",
                    style = MaterialTheme.typography.labelMedium, color = if (clip.id == selected) color else foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Canvas(Modifier.fillMaxWidth().height(38.dp).semantics { contentDescription = "Track ${index + 1}: starts ${time(clip.startSec)}, length ${time(clip.durationSec)}" }) {
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
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove ${clip.song.title} from mix") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(clip.muted, { onChange(clip.copy(muted = !clip.muted)) }, label = { Text("Mute") })
                FilterChip(clip.solo, { onChange(clip.copy(solo = !clip.solo)) }, label = { Text("Solo") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onPreview, enabled = project.clips.size >= 2) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Text("Preview") }
            }
            if (analyzing) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reading audio · ${(progress * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelAnalysis) { Text("Cancel") }
                }
            } else if (analysis == null) {
                OutlinedButton(onClick = onAnalyze, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.GraphicEq, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Analyze waveform & tempo") }
            } else {
                Text(if (analysis.bpm > 0) "≈ ${number(analysis.bpm, 0)} BPM · ${if (analysis.confidence > 0.45f) "strong" else "tentative"} tempo estimate · ${number(analysis.rmsDb)} dB RMS"
                    else "Waveform ready · tempo uncertain", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Timing", "Fades", "Sound").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
            }
            when (tab) {
                0 -> {
                    ValueSlider("Starts in mix", clip.startSec, 0f..max(60f, project.durationSec), "s") { onChange(clip.copy(startSec = it)) }
                    ValueSlider("Cue in", clip.cueInSec, 0f..max(0.1f, clip.cueOutSec - 0.1f), "s") { onChange(clip.copy(cueInSec = it)) }
                    ValueSlider("Cue out", clip.cueOutSec, (clip.cueInSec + 0.1f)..clip.song.durationSec.toFloat().coerceAtLeast(clip.cueInSec + 0.2f), "s") { onChange(clip.copy(cueOutSec = it)) }
                    Text("Tap a value to enter an exact time. Cue points refer to the source song.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (project.clips.indexOf(clip) > 0) {
                        Text("Transition from previous track", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(4, 8, 16).forEach { bars -> OutlinedButton(onClick = { onBars(bars) }) { Text("$bars bars") } }
                        }
                        Text("Uses the previous track's tempo estimate; falls back to 8 seconds before analysis. Assumes 4 beats per bar.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                1 -> {
                    TransitionPreview(project, clip, analyses)
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(clip.bassSwap, { onChange(clip.copy(bassSwap = it)) }); Spacer(Modifier.width(10.dp)); Text("Exchange bass during fades") }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FadeCurve.entries.forEach { curve -> FilterChip(clip.curve == curve, { onChange(clip.copy(curve = curve)) }, label = { Text(curve.label) }) }
                    }
                    ValueSlider("Fade in", clip.fadeInSec, 0f..min(60f, clip.durationSec), "s") { onChange(clip.copy(fadeInSec = it)) }
                    ValueSlider("Fade out", clip.fadeOutSec, 0f..min(60f, clip.durationSec), "s") { onChange(clip.copy(fadeOutSec = it)) }
                    ValueSlider("Entrance shape", clip.fadeInBend, 0.25f..4f, "×", decimals = 2) { onChange(clip.copy(fadeInBend = it)) }
                    ValueSlider("Exit shape", clip.fadeOutBend, 0.25f..4f, "×", decimals = 2) { onChange(clip.copy(fadeOutBend = it)) }
                    Text("The line in the arrangement shows this track's volume envelope. Master headroom balances overlapping tracks.", style = MaterialTheme.typography.bodySmall)
                }
                2 -> {
                    ValueSlider("Track level", clip.gainDb, -36f..0f, "dB", live = true) { onChange(clip.copy(gainDb = it)) }
                    ValueSlider("Pan · left / right", clip.pan, -1f..1f, "", live = true) { onChange(clip.copy(pan = it)) }
                    ValueSlider("Bass", clip.bassDb, -24f..6f, "dB", live = true) { onChange(clip.copy(bassDb = it)) }
                    ValueSlider("Mid", clip.midDb, -24f..6f, "dB", live = true) { onChange(clip.copy(midDb = it)) }
                    ValueSlider("Treble", clip.trebleDb, -24f..6f, "dB", live = true) { onChange(clip.copy(trebleDb = it)) }
                    ValueSlider("Tempo", clip.speed, 0.5f..2f, "×", decimals = 2) { onChange(clip.copy(speed = it)) }
                    ValueSlider("Pitch", clip.pitchSemitones, -12f..12f, "st") { onChange(clip.copy(pitchSemitones = it)) }
                    if (analysis?.bpm != null && analysis.bpm > 0 && project.clips.indexOf(clip) > 0) OutlinedButton(onClick = onTempoMatch) { Text("Match previous track's tempo") }
                    Text("Tempo preserves pitch. EQ boosts reserve headroom before processing.", style = MaterialTheme.typography.bodySmall)
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
        Text("TRANSITION · ${time(start)} — ${time(end)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        clips.forEachIndexed { i, c ->
            val color = if (i == 0 && previous != null) second else accent
            val audibleClip = remember(c) { c.copy(muted = false) }
            Text(c.song.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color)
            Canvas(Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp)
                .semantics { contentDescription = "${c.song.title}, transition envelope from ${time(start)} to ${time(end)}" }) {
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
        if (clips.any { analyses[it.song.id] == null }) Text("Analyze each track to reveal its waveform here.", style = MaterialTheme.typography.bodySmall)
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
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(unit.ifBlank { "Value" }) },
                supportingText = { Text("${number(range.start)} to ${number(range.endInclusive)}") },
                isError = parsed == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
            confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onValue); edit = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
    }
}

private fun time(seconds: Float): String = "${seconds.toInt() / 60}:${(seconds.toInt() % 60).toString().padStart(2, '0')}"
private fun number(value: Float, decimals: Int = 1) = String.format(Locale.US, "%.${decimals}f", value)
