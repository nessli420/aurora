package com.aurora.music.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingPresetLibrary
import com.aurora.music.data.routes.ProcessingRouteRules
import com.aurora.music.data.rules.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun PresetRulesScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    var loadError by remember { mutableStateOf<String?>(null) }
    val ruleFlow = remember(store) { store.presetRules.catch { loadError = it.message ?: "Rules unavailable." } }
    val ruleSet by ruleFlow.collectAsStateWithLifecycle<PresetRuleSet?>(initialValue = null)
    val outputFlow = remember(store) { store.processingRouteRules.catch { loadError = it.message ?: "Output rules unavailable." } }
    val outputRules by outputFlow.collectAsStateWithLifecycle(initialValue = ProcessingRouteRules())
    val route by store.processingRoutes.observations.collectAsStateWithLifecycle()
    val context by store.presetRuleContext.observations.collectAsStateWithLifecycle()
    val library by store.processingPresetLibrary.collectAsStateWithLifecycle(initialValue = ProcessingPresetLibrary())
    val preview by container.autoEqController.rulePreview.collectAsStateWithLifecycle()
    val status by container.autoEqController.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<PresetRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PresetRule?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ready = ruleSet != null && loadError == null && !busy && !context.frozen
    val input = PresetRuleInput(context, route, ruleSet ?: PresetRuleSet(), outputRules)
    fun perform(action: suspend () -> Result<Unit>) {
        if (!ready) return
        busy = true
        scope.launch {
            try { action().getOrThrow() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: "Could not update preset rules.") }
            finally { busy = false }
        }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar("Preset rules", onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(preview.winner?.let { "First match: ${it.name}" } ?: "No matching rule", style = MaterialTheme.typography.titleMedium)
                            Text(preview.pausedReason ?: status.message, style = MaterialTheme.typography.bodySmall)
                            Text("Higher priority wins; ties use list order. Previous sound returns when rules stop matching.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        ruleSet?.let { rules ->
                            SettingsSwitchRow(title = "Apply preset rules", checked = rules.enabled,
                                onCheckedChange = { enabled -> perform { store.setPresetRulesEnabled(enabled) } })
                            SettingsSwitchRow(title = "Keep current sound", checked = rules.manualHold || route.route.key in outputRules.manual,
                                onCheckedChange = { hold -> perform { store.setPresetRuleManualHold(hold) } })
                        }
                    }
                }
                loadError?.let { message -> item { Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
                library.error?.let { message -> item { Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
                item {
                    Button(onClick = { editor = null; showEditor = true }, enabled = ready && library.error == null &&
                        library.presets.isNotEmpty() && (ruleSet?.rules?.size ?: 0) < PresetRuleCodec.MAX_RULES,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("Add rule") }
                    if (library.presets.isEmpty()) Text("Save a processing preset first.", Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                itemsIndexed(ruleSet?.rules.orEmpty(), key = { _, rule -> rule.id }) { index, rule ->
                    val evaluation = preview.results.firstOrNull { it.rule.id == rule.id }
                    SettingsGroup {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                                    Text(library.presets.firstOrNull { it.id == rule.presetId }?.name ?: "Deleted preset", style = MaterialTheme.typography.bodySmall)
                                    Text("Priority ${rule.priority} · ${if (!rule.enabled) "Off" else if (evaluation?.matched == true) "Matches" else "No match"}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = rule.enabled, enabled = ready,
                                    onCheckedChange = { enabled -> perform { store.savePresetRule(rule.copy(enabled = enabled)) } })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(enabled = ready && index > 0, onClick = { perform { store.movePresetRule(rule.id, -1) } }) { Text("Up") }
                                TextButton(enabled = ready && index < ruleSet!!.rules.lastIndex, onClick = { perform { store.movePresetRule(rule.id, 1) } }) { Text("Down") }
                                TextButton(onClick = { details = rule }) { Text("Explain") }
                                TextButton(enabled = ready, onClick = { editor = rule; showEditor = true }) { Text("Edit") }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }
    details?.let { selected ->
        val evaluation = PresetRuleEngine.preview(input.copy(rules = input.rules.copy(rules = listOf(selected)))).results.singleOrNull()
        AlertDialog(onDismissRequest = { details = null }, title = { Text(selected.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (selected.match == RuleMatch.ALL) "All conditions must match." else "Any condition may match.")
                evaluation?.conditions?.forEach { condition ->
                    Text("${condition.condition.field.label}: ${condition.state.label()}\n${condition.explanation}", style = MaterialTheme.typography.bodyMedium)
                }
                if (evaluation?.matched == true && preview.winner?.id != selected.id) Text("A higher-ranked rule takes precedence.")
                preview.pausedReason?.let { Text(it) }
            }
        }, confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } })
    }
    if (showEditor) PresetRuleEditor(editor, library.presets, input, ready, onDismiss = { showEditor = false },
        onSave = { rule -> showEditor = false; perform { store.savePresetRule(rule) } },
        onDelete = editor?.let { selected -> { showEditor = false; perform { store.removePresetRule(selected.id) } } })
}

@Composable
private fun PresetRuleEditor(initial: PresetRule?, presets: List<ProcessingPreset>, input: PresetRuleInput,
    ready: Boolean, onDismiss: () -> Unit, onSave: (PresetRule) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var selectedPreset by remember { mutableStateOf(initial?.presetId ?: presets.firstOrNull()?.id.orEmpty()) }
    var priority by remember { mutableStateOf((initial?.priority ?: 0).toString()) }
    var match by remember { mutableStateOf(initial?.match ?: RuleMatch.ALL) }
    var conditions by remember { mutableStateOf(initial?.conditions.orEmpty()) }
    var conditionEditor by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val preview = if (conditions.isEmpty()) null else PresetRuleEngine.preview(input.copy(rules = PresetRuleSet(enabled = true,
        rules = listOf(PresetRule(initial?.id ?: "00000000-0000-0000-0000-000000000001", name, selectedPreset,
            priority = priority.toIntOrNull() ?: 0, match = match, conditions = conditions))))).results.singleOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "Add preset rule" else "Edit preset rule") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true)
            RuleChoice("Preset", selectedPreset, presets.map { it.id to it.name }) { selectedPreset = it }
            OutlinedTextField(priority, { priority = it.take(6) }, label = { Text("Priority (-1000 to 1000)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            RuleChoice("Match", match.name, listOf(RuleMatch.ALL.name to "All conditions", RuleMatch.ANY.name to "Any condition")) { match = RuleMatch.valueOf(it) }
            conditions.forEachIndexed { index, condition ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { conditionEditor = index }, modifier = Modifier.weight(1f)) {
                        Text("${condition.field.label}: ${conditionSummary(condition)}")
                    }
                    TextButton(onClick = { conditions = conditions.filterIndexed { i, _ -> i != index } }) { Text("Remove") }
                }
            }
            TextButton(enabled = conditions.size < PresetRuleCodec.MAX_CONDITIONS, onClick = { conditionEditor = conditions.size }) { Text("Add condition") }
            preview?.let { Text(if (it.matched) "Matches current playback." else "Does not match current playback.", style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            onDelete?.let { action -> TextButton(enabled = ready, onClick = action) { Text("Delete rule") } }
        }
    }, confirmButton = { TextButton(enabled = ready, onClick = {
        runCatching {
            val parsedPriority = priority.toIntOrNull() ?: error("Enter a whole-number priority.")
            val rule = PresetRule(initial?.id ?: UUID.randomUUID().toString(), name.trim(), selectedPreset,
                initial?.enabled ?: true, parsedPriority, match, conditions)
            PresetRuleCodec.validate(PresetRuleSet(rules = listOf(rule)))
            onSave(rule)
        }.onFailure { error = it.message ?: "Invalid rule." }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    conditionEditor?.let { index ->
        key(index) { RuleConditionEditor(conditions.getOrNull(index), input, onDismiss = { conditionEditor = null }, onSave = { condition ->
            conditions = conditions.toMutableList().apply { if (index < size) set(index, condition) else add(condition) }
            conditionEditor = null
        }) }
    }
}

@Composable
private fun RuleConditionEditor(initial: PresetRuleCondition?, input: PresetRuleInput, onDismiss: () -> Unit,
    onSave: (PresetRuleCondition) -> Unit) {
    var field by remember { mutableStateOf(initial?.field ?: RuleField.GENRE) }
    var value by remember { mutableStateOf(initial?.values?.joinToString(" | ").orEmpty()) }
    var minimum by remember { mutableStateOf(initial?.minRateHz?.toString().orEmpty()) }
    var maximum by remember { mutableStateOf(initial?.maxRateHz?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = currentCondition(field, input)
    fun useCurrent() {
        current?.let { condition -> value = condition.values.joinToString(" | ")
            minimum = condition.minRateHz?.toString().orEmpty(); maximum = condition.maxRateHz?.toString().orEmpty() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rule condition") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RuleChoice("Condition", field.name, RuleField.entries.map { it.name to it.label }) {
                field = RuleField.valueOf(it); value = ""; minimum = ""; maximum = ""; error = null
            }
            when (field) {
                RuleField.SOURCE -> RuleChoice("Source", value, RuleSource.entries.map { it.name to it.label }) { value = it }
                RuleField.CONTEXT -> RuleChoice("Context", value, RulePlaybackMode.entries.map { it.name to it.label }) { value = it }
                RuleField.SAMPLE_RATE -> {
                    OutlinedTextField(minimum, { minimum = it.take(8) }, label = { Text("Minimum Hz") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(maximum, { maximum = it.take(8) }, label = { Text("Maximum Hz") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                RuleField.ROUTE -> Text(if (value.isEmpty()) "Choose the current confirmed output." else "Confirmed output selected.")
                else -> OutlinedTextField(value, { value = it.take(2048) }, label = {
                    Text(if (field == RuleField.HEADPHONES) "Headphones (empty for output default)" else "Values, separated by |")
                }, minLines = 1, maxLines = 3)
            }
            TextButton(enabled = current != null, onClick = ::useCurrent) { Text("Use current") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            fun number(text: String): Int? = text.takeIf { it.isNotBlank() }?.let { it.toIntOrNull() ?: error("Enter a whole-number sample rate.") }
            val condition = if (field == RuleField.SAMPLE_RATE) PresetRuleCondition(field, minRateHz = number(minimum), maxRateHz = number(maximum))
                else PresetRuleCondition(field, value.split('|').map { it.trim() }.distinct())
            PresetRuleCodec.validate(PresetRuleSet(rules = listOf(PresetRule(UUID.randomUUID().toString(), "Condition", UUID.randomUUID().toString(), conditions = listOf(condition)))))
            onSave(condition)
        }.onFailure { error = it.message ?: "Invalid condition." }
    }) { Text("Add") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RuleChoice(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${options.firstOrNull { it.first == selected }?.second ?: "Choose"}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(value); open = false }) }
        }
    }
}

private fun RuleConditionState.label() = when (this) {
    RuleConditionState.MATCH -> "Matches"
    RuleConditionState.NO_MATCH -> "No match"
    RuleConditionState.UNKNOWN -> "Unknown"
}

private fun conditionSummary(condition: PresetRuleCondition): String = when (condition.field) {
    RuleField.SAMPLE_RATE -> "${condition.minRateHz ?: "Any"}–${condition.maxRateHz ?: "Any"} Hz"
    RuleField.ROUTE -> "Confirmed output"
    RuleField.HEADPHONES -> condition.values.joinToString(" / ") { it.ifEmpty { "Output default" } }
    RuleField.SOURCE -> condition.values.joinToString { RuleSource.valueOf(it).label }
    RuleField.CONTEXT -> condition.values.joinToString { RulePlaybackMode.valueOf(it).label }
    else -> condition.values.joinToString(" / ")
}

private fun currentCondition(field: RuleField, input: PresetRuleInput): PresetRuleCondition? {
    val context = input.observation.playback
    val values = when (field) {
        RuleField.ROUTE -> input.route.route.key?.let(::listOf)
        RuleField.HEADPHONES -> input.route.route.key?.let { listOf(input.outputRules.headphones[it].orEmpty()) }
        RuleField.SOURCE -> context.source?.name?.let(::listOf)
        RuleField.PROVIDER -> context.providerId?.let(::listOf)
        RuleField.ALBUM_ID -> context.albumId?.let(::listOf)
        RuleField.ALBUM_NAME -> context.albumName?.let(::listOf)
        RuleField.GENRE -> context.genres?.toList()?.takeIf { it.isNotEmpty() }
        RuleField.PLAYLIST_ID -> context.playlistId?.let(::listOf)
        RuleField.PLAYLIST_NAME -> context.playlistName?.let(::listOf)
        RuleField.CODEC -> context.codec?.let(::listOf)
        RuleField.CONTAINER -> context.container?.let(::listOf)
        RuleField.CONTEXT -> when {
            context.cast == true -> listOf(RulePlaybackMode.CAST.name)
            context.androidAuto == true -> listOf(RulePlaybackMode.ANDROID_AUTO.name)
            context.cast == false && context.androidAuto == false -> listOf(RulePlaybackMode.LOCAL.name)
            else -> null
        }
        RuleField.SAMPLE_RATE -> return context.sampleRateHz?.takeIf { it > 0 }?.let { PresetRuleCondition(field, minRateHz = it, maxRateHz = it) }
    }
    return values?.let { PresetRuleCondition(field, it) }
}
