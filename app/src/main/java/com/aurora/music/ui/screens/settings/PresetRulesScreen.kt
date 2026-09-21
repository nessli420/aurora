package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString

import com.aurora.music.R
import com.aurora.music.localization.localizedLabel

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
    val ruleFlow = remember(store) { store.presetRules.catch { loadError = it.message ?: appString(R.string.text_rules_unavailable_4feedc) } }
    val ruleSet by ruleFlow.collectAsStateWithLifecycle<PresetRuleSet?>(initialValue = null)
    val outputFlow = remember(store) { store.processingRouteRules.catch { loadError = it.message ?: appString(R.string.text_output_rules_unavailable_e638bb) } }
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
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: appString(R.string.text_could_not_update_preset_rules_c56bf9)) }
            finally { busy = false }
        }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(appString(R.string.text_preset_rules_c9d4d1), onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(preview.winner?.let { appString(R.string.text_first_match_3faa83, (it.name)) } ?: appString(R.string.text_no_matching_rule_6426f8), style = MaterialTheme.typography.titleMedium)
                            Text(preview.pausedReason ?: status.message, style = MaterialTheme.typography.bodySmall)
                            Text(appString(R.string.text_higher_priority_wins_ties_use_list_order_previous_sound_returns_w_6bd32e),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        ruleSet?.let { rules ->
                            SettingsSwitchRow(title = appString(R.string.text_apply_preset_rules_16028a), checked = rules.enabled,
                                onCheckedChange = { enabled -> perform { store.setPresetRulesEnabled(enabled) } })
                            SettingsSwitchRow(title = appString(R.string.text_keep_current_sound_6e3bdc), checked = rules.manualHold || route.route.key in outputRules.manual,
                                onCheckedChange = { hold -> perform { store.setPresetRuleManualHold(hold) } })
                        }
                    }
                }
                loadError?.let { message -> item { Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
                library.error?.let { message -> item { Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
                item {
                    Button(onClick = { editor = null; showEditor = true }, enabled = ready && library.error == null &&
                        library.presets.isNotEmpty() && (ruleSet?.rules?.size ?: 0) < PresetRuleCodec.MAX_RULES,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text(appString(R.string.text_add_rule_11cc2b)) }
                    if (library.presets.isEmpty()) Text(appString(R.string.text_save_a_processing_preset_first_6dd1cf), Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                itemsIndexed(ruleSet?.rules.orEmpty(), key = { _, rule -> rule.id }) { index, rule ->
                    val evaluation = preview.results.firstOrNull { it.rule.id == rule.id }
                    SettingsGroup {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                                    Text(library.presets.firstOrNull { it.id == rule.presetId }?.name ?: appString(R.string.text_deleted_preset_4bf611), style = MaterialTheme.typography.bodySmall)
                                    Text(appString(R.string.text_priority_0168ee, (rule.priority), (if (!rule.enabled) appString(R.string.text_off_e3de5a) else if (evaluation?.matched == true) appString(R.string.text_matches_ee2dbd) else appString(R.string.text_no_match_3518df))),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = rule.enabled, enabled = ready,
                                    onCheckedChange = { enabled -> perform { store.savePresetRule(rule.copy(enabled = enabled)) } })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(enabled = ready && index > 0, onClick = { perform { store.movePresetRule(rule.id, -1) } }) { Text(appString(R.string.text_up_2038bd)) }
                                TextButton(enabled = ready && index < ruleSet!!.rules.lastIndex, onClick = { perform { store.movePresetRule(rule.id, 1) } }) { Text(appString(R.string.text_down_bf93e5)) }
                                TextButton(onClick = { details = rule }) { Text(appString(R.string.text_explain_55cbfd)) }
                                TextButton(enabled = ready, onClick = { editor = rule; showEditor = true }) { Text(appString(R.string.text_edit_530164)) }
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
                Text(if (selected.match == RuleMatch.ALL) appString(R.string.text_all_conditions_must_match_2a5764) else appString(R.string.text_any_condition_may_match_5c1b6f))
                evaluation?.conditions?.forEach { condition ->
                    Text("${condition.condition.field.localizedLabel}: ${condition.state.label()}\n${condition.explanation}", style = MaterialTheme.typography.bodyMedium)
                }
                if (evaluation?.matched == true && preview.winner?.id != selected.id) Text(appString(R.string.text_a_higher_ranked_rule_takes_precedence_ae1995))
                preview.pausedReason?.let { Text(it) }
            }
        }, confirmButton = { TextButton(onClick = { details = null }) { Text(appString(R.string.text_done_e9b450)) } })
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) appString(R.string.text_add_preset_rule_ce17da) else appString(R.string.text_edit_preset_rule_21254a)) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(appString(R.string.text_name_709a23)) }, singleLine = true)
            RuleChoice(appString(R.string.text_preset_bca788), selectedPreset, presets.map { it.id to it.name }) { selectedPreset = it }
            OutlinedTextField(priority, { priority = it.take(6) }, label = { Text(appString(R.string.text_priority_1000_to_1000_eb6c32)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            RuleChoice(appString(R.string.text_match_033520), match.name, listOf(RuleMatch.ALL.name to appString(R.string.text_all_conditions_771ea0), RuleMatch.ANY.name to appString(R.string.text_any_condition_424666))) { match = RuleMatch.valueOf(it) }
            conditions.forEachIndexed { index, condition ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { conditionEditor = index }, modifier = Modifier.weight(1f)) {
                        Text("${condition.field.localizedLabel}: ${conditionSummary(condition)}")
                    }
                    TextButton(onClick = { conditions = conditions.filterIndexed { i, _ -> i != index } }) { Text(appString(R.string.text_remove_e96390)) }
                }
            }
            TextButton(enabled = conditions.size < PresetRuleCodec.MAX_CONDITIONS, onClick = { conditionEditor = conditions.size }) { Text(appString(R.string.text_add_condition_42fe69)) }
            preview?.let { Text(if (it.matched) appString(R.string.text_matches_current_playback_124000) else appString(R.string.text_does_not_match_current_playback_fb4dfe), style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            onDelete?.let { action -> TextButton(enabled = ready, onClick = action) { Text(appString(R.string.text_delete_rule_784bef)) } }
        }
    }, confirmButton = { TextButton(enabled = ready, onClick = {
        runCatching {
            val parsedPriority = priority.toIntOrNull() ?: error(appString(R.string.text_enter_a_whole_number_priority_dde963))
            val rule = PresetRule(initial?.id ?: UUID.randomUUID().toString(), name.trim(), selectedPreset,
                initial?.enabled ?: true, parsedPriority, match, conditions)
            PresetRuleCodec.validate(PresetRuleSet(rules = listOf(rule)))
            onSave(rule)
        }.onFailure { error = it.message ?: appString(R.string.text_invalid_rule_c35c4d) }
    }) { Text(appString(R.string.text_save_efc007)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.text_rule_condition_c6d62b)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RuleChoice(appString(R.string.text_condition_2f4979), field.name, RuleField.entries.map { it.name to it.localizedLabel }) {
                field = RuleField.valueOf(it); value = ""; minimum = ""; maximum = ""; error = null
            }
            when (field) {
                RuleField.SOURCE -> RuleChoice(appString(R.string.text_source_6da13a), value, RuleSource.entries.map { it.name to it.localizedLabel }) { value = it }
                RuleField.CONTEXT -> RuleChoice(appString(R.string.text_context_cc11b3), value, RulePlaybackMode.entries.map { it.name to it.localizedLabel }) { value = it }
                RuleField.SAMPLE_RATE -> {
                    OutlinedTextField(minimum, { minimum = it.take(8) }, label = { Text(appString(R.string.text_minimum_hz_c8ccab)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(maximum, { maximum = it.take(8) }, label = { Text(appString(R.string.text_maximum_hz_77f953)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                RuleField.ROUTE -> Text(if (value.isEmpty()) appString(R.string.text_choose_the_current_confirmed_output_ad4813) else appString(R.string.text_confirmed_output_selected_bfe814))
                else -> OutlinedTextField(value, { value = it.take(2048) }, label = {
                    Text(if (field == RuleField.HEADPHONES) appString(R.string.text_headphones_empty_for_output_default_cff188) else appString(R.string.text_values_separated_by_8db2e6))
                }, minLines = 1, maxLines = 3)
            }
            TextButton(enabled = current != null, onClick = ::useCurrent) { Text(appString(R.string.text_use_current_701bc0)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            fun number(text: String): Int? = text.takeIf { it.isNotBlank() }?.let { it.toIntOrNull() ?: error(appString(R.string.text_enter_a_whole_number_sample_rate_badff4)) }
            val condition = if (field == RuleField.SAMPLE_RATE) PresetRuleCondition(field, minRateHz = number(minimum), maxRateHz = number(maximum))
                else PresetRuleCondition(field, value.split('|').map { it.trim() }.distinct())
            PresetRuleCodec.validate(PresetRuleSet(rules = listOf(PresetRule(UUID.randomUUID().toString(), appString(R.string.text_condition_2f4979), UUID.randomUUID().toString(), conditions = listOf(condition)))))
            onSave(condition)
        }.onFailure { error = it.message ?: appString(R.string.text_invalid_condition_f757ae) }
    }) { Text(appString(R.string.text_add_61cc55)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } })
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
    RuleConditionState.MATCH -> appString(R.string.text_matches_ee2dbd)
    RuleConditionState.NO_MATCH -> appString(R.string.text_no_match_3518df)
    RuleConditionState.UNKNOWN -> appString(R.string.text_unknown_bc7819)
}

private fun conditionSummary(condition: PresetRuleCondition): String = when (condition.field) {
    RuleField.SAMPLE_RATE -> appString(R.string.text_hz_40e093, (condition.minRateHz ?: appString(R.string.text_any_322444)), (condition.maxRateHz ?: appString(R.string.text_any_322444)))
    RuleField.ROUTE -> appString(R.string.text_confirmed_output_52a6b9)
    RuleField.HEADPHONES -> condition.values.joinToString(" / ") { it.ifEmpty { appString(R.string.text_output_default_f4c103) } }
    RuleField.SOURCE -> condition.values.joinToString { RuleSource.valueOf(it).localizedLabel }
    RuleField.CONTEXT -> condition.values.joinToString { RulePlaybackMode.valueOf(it).localizedLabel }
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
