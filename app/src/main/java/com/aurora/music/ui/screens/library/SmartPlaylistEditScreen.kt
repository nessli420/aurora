package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.data.SmartPlaylist
import com.aurora.music.data.SmartRule
import com.aurora.music.ui.screens.settings.SegmentedRow
import com.aurora.music.ui.screens.settings.SettingsGroup
import com.aurora.music.ui.screens.settings.SettingsTopBar

// keys must match SmartPlaylistEngine
private const val TYPE_TEXT = 0
private const val TYPE_NUMBER = 1
private const val TYPE_BOOL = 2

private data class FieldSpec(val key: String, val label: String, val type: Int)

private val FIELDS = listOf(
    FieldSpec("title", "Title", TYPE_TEXT),
    FieldSpec("artist", "Artist", TYPE_TEXT),
    FieldSpec("album", "Album", TYPE_TEXT),
    FieldSpec("format", "Format (flac, mp3…)", TYPE_TEXT),
    FieldSpec("duration", "Duration (seconds)", TYPE_NUMBER),
    FieldSpec("bitrate", "Bitrate (kbps)", TYPE_NUMBER),
    FieldSpec("playCount", "Play count", TYPE_NUMBER),
    FieldSpec("lastPlayedDays", "Last played (days ago)", TYPE_NUMBER),
    FieldSpec("liked", "Liked", TYPE_BOOL),
    FieldSpec("downloaded", "Downloaded", TYPE_BOOL),
)

private val TEXT_OPS = listOf("contains" to "contains", "notContains" to "doesn't contain", "is" to "is", "isNot" to "is not", "startsWith" to "starts with")
private val NUM_OPS = listOf("gt" to "more than", "lt" to "less than", "eq" to "exactly")
private val BOOL_OPS = listOf("isTrue" to "yes", "isFalse" to "no")

private val SORTS = listOf(
    "title" to "Title", "artist" to "Artist", "album" to "Album", "duration" to "Duration",
    "playCount" to "Play count", "lastPlayed" to "Last played", "random" to "Random",
)

private fun fieldSpec(key: String?): FieldSpec = FIELDS.firstOrNull { it.key == key } ?: FIELDS.first()
private fun opsFor(type: Int) = when (type) { TYPE_NUMBER -> NUM_OPS; TYPE_BOOL -> BOOL_OPS; else -> TEXT_OPS }

@Composable
fun SmartPlaylistEditScreen(
    contentPadding: PaddingValues,
    playlist: SmartPlaylist,
    isNew: Boolean,
    onUpdate: ((SmartPlaylist) -> SmartPlaylist) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(title = if (isNew) "New smart playlist" else "Edit smart playlist", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            OutlinedTextField(
                value = playlist.name.orEmpty(),
                onValueChange = { v -> onUpdate { it.copy(name = v) } },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(14.dp))

            SettingsGroup {
                SegmentedRow(
                    title = "Match",
                    options = listOf("All rules", "Any rule"),
                    selected = if (playlist.matchAll != false) 0 else 1,
                    onSelect = { i -> onUpdate { it.copy(matchAll = i == 0) } },
                )
            }
            Spacer(Modifier.height(14.dp))

            Text("Rules", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            val rules = playlist.rules.orEmpty()
            rules.forEachIndexed { i, rule ->
                RuleRow(
                    rule = rule,
                    onChange = { r -> onUpdate { it.copy(rules = rules.toMutableList().apply { set(i, r) }) } },
                    onRemove = { onUpdate { it.copy(rules = rules.toMutableList().apply { removeAt(i) }) } },
                )
            }
            TextButton(onClick = { onUpdate { it.copy(rules = rules + SmartRule()) } }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add rule")
            }
            Spacer(Modifier.height(14.dp))

            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort by", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Dropdown(
                        options = SORTS.map { it.second },
                        selected = SORTS.indexOfFirst { it.first == (playlist.sortBy ?: "title") }.coerceAtLeast(0),
                        onSelect = { i -> onUpdate { it.copy(sortBy = SORTS[i].first) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    val desc = playlist.descending == true
                    Icon(
                        if (desc) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        if (desc) "Descending" else "Ascending",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .clickable { onUpdate { it.copy(descending = !desc) } }.padding(7.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Limit (0 = all)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = (playlist.limit ?: 0).takeIf { it > 0 }?.toString() ?: "",
                        onValueChange = { v -> onUpdate { it.copy(limit = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) } },
                        placeholder = { Text("0") },
                        singleLine = true,
                        modifier = Modifier.width(90.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onSave,
                enabled = !playlist.name.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(if (isNew) "Create smart playlist" else "Save changes", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun RuleRow(rule: SmartRule, onChange: (SmartRule) -> Unit, onRemove: () -> Unit) {
    val spec = fieldSpec(rule.field)
    val ops = opsFor(spec.type)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                options = FIELDS.map { it.label },
                selected = FIELDS.indexOfFirst { it.key == spec.key }.coerceAtLeast(0),
                onSelect = { i ->
                    val f = FIELDS[i]
                    // reset op and bool value when field type changes
                    val op = if (opsFor(f.type).any { it.first == rule.op }) rule.op else opsFor(f.type).first().first
                    onChange(rule.copy(field = f.key, op = op, value = if (f.type == TYPE_BOOL) "" else rule.value))
                },
                modifier = Modifier.weight(1f),
            )
            Dropdown(
                options = ops.map { it.second },
                selected = ops.indexOfFirst { it.first == rule.op }.coerceAtLeast(0),
                onSelect = { i -> onChange(rule.copy(op = ops[i].first)) },
            )
            Icon(
                Icons.Filled.Close, "Remove rule",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove).padding(6.dp),
            )
        }
        if (spec.type != TYPE_BOOL) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = rule.value.orEmpty(),
                onValueChange = { v -> onChange(rule.copy(value = if (spec.type == TYPE_NUMBER) v.filter { it.isDigit() } else v)) },
                placeholder = { Text(if (spec.type == TYPE_NUMBER) "Number…" else "Text…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Dropdown(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.getOrElse(selected) { options.first() },
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(i); open = false })
            }
        }
    }
}
