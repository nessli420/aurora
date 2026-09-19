package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ArtistSeparator
import com.aurora.music.data.ArtistSeparators
import com.aurora.music.data.SeparatorMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ArtistSeparatorsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.settingsStore
    val saved by store.artistSeparators.collectAsStateWithLifecycle(initialValue = null)
    var draft by remember { mutableStateOf<ArtistSeparators?>(null) }
    var example by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(saved) { if (draft == null) draft = saved }
    val rules = draft?.rules.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Artist separators", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item {
                Text("Artist indexing and scrobbling. File tags stay unchanged.", modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(example, { example = it.take(500) }, label = { Text("Preview artist tag") }, modifier = Modifier.fillMaxWidth())
                    if (example.isNotBlank()) Text(draft?.split(example)?.joinToString(" · ").orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            itemsIndexed(rules) { index, rule ->
                SettingsGroup {
                    SegmentedRow(rule.text.orEmpty(), SeparatorMatch.entries.map { it.label },
                        SeparatorMatch.entries.indexOf(rule.match), onSelect = { chosen ->
                            if (!busy) draft = ArtistSeparators(rules.toMutableList().apply { set(index, rule.copy(match = SeparatorMatch.entries[chosen])) })
                        })
                    if (rule.text !in ArtistSeparators.defaults().map { it.text }) {
                        TextButton(enabled = !busy, onClick = { draft = ArtistSeparators(rules.filterIndexed { i, _ -> i != index }) }) { Text("Remove separator") }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(enabled = !busy && draft != null && rules.size < 24, onClick = { adding = true; newText = ""; error = null }) { Text("Add separator") }
                    TextButton(enabled = !busy && draft != null, onClick = { draft = ArtistSeparators(); error = null }) { Text("Reset defaults") }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = !busy && draft != null && draft != saved, onClick = {
                        scope.launch {
                            busy = true; error = null
                            try { store.setArtistSeparators(requireNotNull(draft)) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "Could not save separators." }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving…" else "Save") }
                }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add separator") },
        text = { OutlinedTextField(newText, { newText = it.take(32) }, label = { Text("Separator") }, singleLine = true) },
        confirmButton = { TextButton(enabled = newText.isNotBlank() && rules.none { it.text.equals(newText.trim(), true) }, onClick = {
            draft = ArtistSeparators(rules + ArtistSeparator(newText.trim(), SeparatorMatch.SPACED)); adding = false
        }) { Text("Add") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
}
