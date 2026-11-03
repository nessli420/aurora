package com.aurora.music.ui.screens.aidj

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.R
import com.aurora.music.ai.DjFamiliarity
import com.aurora.music.ai.DjIntent
import com.aurora.music.ai.DjModelState
import com.aurora.music.ai.DjModelStore
import com.aurora.music.ai.DjVocals
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.screens.settings.SettingsTopBar
import com.aurora.music.ui.screens.settings.storageSize
import com.aurora.music.viewmodel.AiDjViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiDjScreen(
    contentPadding: PaddingValues,
    current: Song?,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onStartDj: (String, DjIntent, Boolean, List<Song>) -> Unit,
    onMessage: (String) -> Unit,
) {
    val vm: AiDjViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val busy = state.phase == AiDjViewModel.Phase.THINKING || state.phase == AiDjViewModel.Phase.PICKING
    var saving by remember { mutableStateOf(false) }
    val examples = listOf(R.string.ai_dj_example_night, R.string.ai_dj_example_party, R.string.ai_dj_example_focus, R.string.ai_dj_example_new)
    val startDj = {
        vm.generate(seed = current.takeIf { state.prompt.isBlank() }, count = 16) { done ->
            done.intent?.let { if (done.songs.isNotEmpty()) onStartDj(done.prompt, it, done.usedModel, done.songs) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(title = appString(R.string.ai_dj_title), onBack = onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(appString(R.string.ai_dj_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = vm::setPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(appString(R.string.ai_dj_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy) vm.generate() }),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    examples.forEach { id ->
                        val text = appString(id)
                        SuggestionChip(onClick = { vm.setPrompt(text) }, label = { Text(text) })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.generate() }, enabled = !busy && state.prompt.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(appString(R.string.ai_dj_create))
                    }
                    OutlinedButton(onClick = startDj, enabled = !busy && (state.prompt.isNotBlank() || current != null)) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(appString(R.string.ai_dj_start))
                    }
                }
                if (state.prompt.isBlank() && current != null) Text(appString(R.string.ai_dj_from_current),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(appString(if (state.phase == AiDjViewModel.Phase.THINKING) R.string.ai_dj_thinking else R.string.ai_dj_picking))
                }
            }
            val intent = state.intent
            if (state.phase == AiDjViewModel.Phase.DONE && intent != null) {
                item { IntentSummary(intent, state.usedModel, state.thinkingMs, state.songs.size) }
                if (state.songs.isEmpty()) item {
                    Text(appString(R.string.ai_dj_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onPlay(state.songs, 0) }) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(appString(R.string.ai_dj_play))
                            }
                            FilledTonalButton(onClick = { saving = true }) { Text(appString(R.string.ai_dj_save)) }
                            TextButton(onClick = { onStartDj(state.prompt, intent, state.usedModel, state.songs) }) { Text(appString(R.string.ai_dj_start)) }
                        }
                    }
                    itemsIndexed(state.songs, key = { _, s -> s.id }) { i, song -> TrackRow(song, song.id == current?.id) { onPlay(state.songs, i) } }
                }
            }
            item { AiDjModelCard(vm) }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf(state.intent?.title?.ifBlank { null } ?: state.prompt.take(40)) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text(appString(R.string.ai_dj_save)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(appString(R.string.ai_dj_playlist_name)) }) },
            confirmButton = { TextButton(onClick = { saving = false; vm.save(name, onMessage) }, enabled = name.isNotBlank()) { Text(appString(R.string.ai_dj_save)) } },
            dismissButton = { TextButton(onClick = { saving = false }) { Text(appString(R.string.ai_dj_model_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntentSummary(intent: DjIntent, usedModel: Boolean, thinkingMs: Long, count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(intent.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val how = if (usedModel) appString(R.string.ai_dj_by_model, "%.1f s".format(thinkingMs / 1000f)) else appString(R.string.ai_dj_by_rules)
        Text("$how · ${appPlural(R.plurals.track_count, count)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val chips = buildList {
            add(appString(R.string.ai_dj_energy, intent.energyStart, intent.energyEnd))
            intent.decades.forEach { add("${it}s") }
            addAll(intent.genres.take(4))
            intent.artists.take(2).forEach(::add)
            when (intent.vocals) { DjVocals.NONE -> add(appString(R.string.ai_dj_no_vocals)); DjVocals.LESS -> add(appString(R.string.ai_dj_less_vocals)); else -> {} }
            when (intent.familiarity) { DjFamiliarity.FAVOURITES -> add(appString(R.string.ai_dj_favourites)); DjFamiliarity.DISCOVER -> add(appString(R.string.ai_dj_discover)); else -> {} }
            intent.avoid.take(3).forEach { add(appString(R.string.ai_dj_avoid, it)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { AssistChip(onClick = {}, label = { Text(it, maxLines = 1) }) }
        }
    }
}

@Composable
private fun TrackRow(song: Song, playing: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.artworkUrl, song.accent, Modifier.size(48.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AiDjModelCard(vm: AiDjViewModel) {
    val model by vm.model.collectAsStateWithLifecycle()
    val unsupported = vm.unsupported
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appString(R.string.ai_dj_model), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when {
                unsupported != null -> {
                    Text(appString(R.string.ai_dj_model_unsupported), style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(appString(R.string.ai_dj_basic), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                model == DjModelState.Ready -> Text(appString(R.string.ai_dj_model_ready, storageSize(DjModelStore.BYTES)), style = MaterialTheme.typography.bodySmall, color = muted)
                model is DjModelState.Downloading -> {
                    val d = model as DjModelState.Downloading
                    LinearProgressIndicator(progress = { d.bytes.toFloat() / d.total }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(appString(R.string.ai_dj_model_downloading, storageSize(d.bytes), storageSize(d.total)), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::cancelDownload) { Text(appString(R.string.ai_dj_model_cancel)) }
                    }
                }
                model == DjModelState.Verifying -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(appString(R.string.ai_dj_model_verifying), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                else -> {
                    (model as? DjModelState.Failed)?.let { Text(aiDjFailure(it.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text(appString(R.string.ai_dj_model_missing, storageSize(DjModelStore.BYTES)), style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(appString(R.string.ai_dj_basic), style = MaterialTheme.typography.bodySmall, color = muted)
                    Spacer(Modifier.height(2.dp))
                    FilledTonalButton(onClick = vm::downloadModel) { Text(appString(R.string.ai_dj_model_download)) }
                }
            }
        }
    }
}

fun aiDjFailure(reason: String): String = appString(when (reason) {
    DjModelStore.FAIL_WIFI -> R.string.ai_dj_model_wifi
    DjModelStore.FAIL_SPACE -> R.string.ai_dj_model_space
    DjModelStore.FAIL_CHECKSUM -> R.string.ai_dj_model_checksum
    else -> R.string.ai_dj_model_network
})

@Composable
fun AiDjQueueBanner(state: com.aurora.music.ai.DjMode.State, onSteer: (String) -> Unit, onStop: () -> Unit) {
    var steer by remember { mutableStateOf("") }
    val send = { if (steer.isNotBlank() && !state.busy) { onSteer(steer); steer = "" } }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.ai_dj_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(state.intent?.title?.ifBlank { null } ?: state.prompt, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onStop) { Text(appString(R.string.ai_dj_stop)) }
            }
            OutlinedTextField(
                value = steer,
                onValueChange = { steer = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(appString(R.string.ai_dj_steer_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { TextButton(onClick = send, enabled = steer.isNotBlank() && !state.busy) { Text(appString(R.string.ai_dj_steer)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            when {
                state.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.ai_dj_busy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.failed -> Text(appString(R.string.ai_dj_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
