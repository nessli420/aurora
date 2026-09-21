package com.aurora.music.ui.screens.library

import com.aurora.music.localization.appString
import com.aurora.music.R

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

private val FIELDS: List<FieldSpec> get() = listOf(
    FieldSpec("title", appString(R.string.text_title_768e0c), TYPE_TEXT),
    FieldSpec("artist", appString(R.string.text_artist_6c3f3d), TYPE_TEXT),
    FieldSpec("album", appString(R.string.text_album_dfb4c9), TYPE_TEXT),
    FieldSpec("genre", appString(R.string.text_genre_2aa31f), TYPE_TEXT),
    FieldSpec("format", appString(R.string.text_format_flac_mp3_467eba), TYPE_TEXT),
    FieldSpec("duration", appString(R.string.text_duration_seconds_09b44e), TYPE_NUMBER),
    FieldSpec("bitrate", appString(R.string.text_bitrate_kbps_cee82c), TYPE_NUMBER),
    FieldSpec("playCount", appString(R.string.text_play_count_50a957), TYPE_NUMBER),
    FieldSpec("lastPlayedDays", appString(R.string.text_last_played_days_ago_8e3e3b), TYPE_NUMBER),
    FieldSpec("liked", appString(R.string.text_liked_8c794f), TYPE_BOOL),
    FieldSpec("downloaded", appString(R.string.text_downloaded_c61970), TYPE_BOOL),
)

private val TEXT_OPS: List<Pair<String, String>> get() = listOf("contains" to appString(R.string.rule_contains), "notContains" to appString(R.string.text_doesn_t_contain_688c39), "is" to appString(R.string.rule_is), "isNot" to appString(R.string.text_is_not_e82fdb), "startsWith" to appString(R.string.text_starts_with_2e2ec9))
private val NUM_OPS: List<Pair<String, String>> get() = listOf("gt" to appString(R.string.text_more_than_f9c07b), "lt" to appString(R.string.text_less_than_6bb2aa), "eq" to appString(R.string.rule_exactly))
private val BOOL_OPS: List<Pair<String, String>> get() = listOf("isTrue" to appString(R.string.answer_yes), "isFalse" to appString(R.string.answer_no))

private val SORTS: List<Pair<String, String>> get() = listOf(
    "title" to appString(R.string.text_title_768e0c), "artist" to appString(R.string.text_artist_6c3f3d), "album" to appString(R.string.text_album_dfb4c9), "duration" to appString(R.string.text_duration_137000),
    "playCount" to appString(R.string.text_play_count_50a957), "lastPlayed" to appString(R.string.text_last_played_7201a8), "random" to appString(R.string.text_random_58d888),
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
        SettingsTopBar(title = if (isNew) appString(R.string.text_new_smart_playlist_ee2a2d) else appString(R.string.text_edit_smart_playlist_5fd608), onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            OutlinedTextField(
                value = playlist.name.orEmpty(),
                onValueChange = { v -> onUpdate { it.copy(name = v) } },
                label = { Text(appString(R.string.text_name_709a23)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(14.dp))

            SettingsGroup {
                SegmentedRow(
                    title = appString(R.string.text_match_033520),
                    options = listOf(appString(R.string.text_all_rules_f0e38d), appString(R.string.text_any_rule_42d727)),
                    selected = if (playlist.matchAll != false) 0 else 1,
                    onSelect = { i -> onUpdate { it.copy(matchAll = i == 0) } },
                )
            }
            Spacer(Modifier.height(14.dp))

            Text(appString(R.string.text_rules_bb11a8), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
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
                Text(appString(R.string.text_add_rule_11cc2b))
            }
            Spacer(Modifier.height(14.dp))

            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.text_sort_by_a2a5bd), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Dropdown(
                        options = SORTS.map { it.second },
                        selected = SORTS.indexOfFirst { it.first == (playlist.sortBy ?: "title") }.coerceAtLeast(0),
                        onSelect = { i -> onUpdate { it.copy(sortBy = SORTS[i].first) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    val desc = playlist.descending == true
                    Icon(
                        if (desc) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        if (desc) appString(R.string.text_descending_36377a) else appString(R.string.text_ascending_2b226c),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .clickable { onUpdate { it.copy(descending = !desc) } }.padding(7.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.text_limit_0_all_451057), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
            ) { Text(if (isNew) appString(R.string.text_create_smart_playlist_6ea764) else appString(R.string.text_save_changes_179359), fontWeight = FontWeight.Bold) }
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
                Icons.Filled.Close, appString(R.string.text_remove_rule_e0d4ff),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove).padding(6.dp),
            )
        }
        if (spec.type != TYPE_BOOL) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = rule.value.orEmpty(),
                onValueChange = { v -> onChange(rule.copy(value = if (spec.type == TYPE_NUMBER) v.filter { it.isDigit() } else v)) },
                placeholder = { Text(if (spec.type == TYPE_NUMBER) appString(R.string.text_number_b1c1ba) else appString(R.string.text_text_6fc726)) },
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
