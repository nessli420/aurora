package com.aurora.music.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.ServerType
import com.aurora.music.localization.appString

@Composable
fun AuroraOnboarding(
    state: OnboardingState,
    connected: Set<ServerType>,
    hasSession: Boolean,
    onConnectSource: (SetupSource) -> Unit,
    onLeaveAuth: (Boolean) -> Unit,
    onOpenTask: (SetupTask) -> Unit,
    onLeaveTask: (Boolean?) -> Unit,
    onFinish: () -> Unit,
    onChoicesConfirmed: () -> Unit,
) {
    when (state.phase) {
        SetupPhase.HIDDEN -> Unit
        SetupPhase.QUESTIONS -> QuestionsScreen(state, onFinish, onChoicesConfirmed)
        SetupPhase.SOURCES -> SourcesScreen(state, connected, onConnectSource, onFinish)
        SetupPhase.AUTH -> AuthPrompt(state.connectingSource, onLeaveAuth)
        SetupPhase.TASKS -> TasksScreen(state, connected, hasSession, onOpenTask, onFinish)
        SetupPhase.TASK -> TaskPrompt(state.activeTask, onLeaveTask)
    }
}

@Composable
private fun QuestionsScreen(state: OnboardingState, onFinish: () -> Unit, onChoicesConfirmed: () -> Unit) {
    val titles = listOf(R.string.setup_listening_title, R.string.setup_gear_title,
        R.string.setup_sources_title, R.string.setup_features_title)
    val details = listOf(R.string.setup_listening_detail, R.string.setup_gear_detail,
        R.string.setup_sources_detail, R.string.setup_features_detail)
    SetupPage(appString(R.string.setup_progress, state.page + 1, 4), appString(titles[state.page]),
        appString(details[state.page]), footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (state.page > 0) state.page-- else onFinish() }) {
                    Text(appString(if (state.page > 0) R.string.setup_back else R.string.onboarding_skip))
                }
                Button(onClick = {
                    if (state.page < 3) state.page++
                    else {
                        onChoicesConfirmed()
                        state.phase = SetupPhase.SOURCES
                    }
                }) { Text(appString(if (state.page == 3) R.string.setup_continue else R.string.onboarding_next)) }
            }
        }) {
        when (state.page) {
            0 -> {
                ChoiceRow(appString(R.string.setup_everyday), appString(R.string.setup_everyday_detail),
                    state.mode == ListeningMode.EVERYDAY) { state.mode = ListeningMode.EVERYDAY }
                ChoiceRow(appString(R.string.setup_advanced), appString(R.string.setup_advanced_detail),
                    state.mode == ListeningMode.ADVANCED) { state.mode = ListeningMode.ADVANCED }
            }
            1 -> {
                ToggleRow(appString(R.string.setup_dac), appString(R.string.setup_dac_detail), state.usbDac) { state.usbDac = it }
                ToggleRow(appString(R.string.setup_iems), appString(R.string.setup_iems_detail), state.iems) { state.iems = it }
                ToggleRow(appString(R.string.setup_autoeq), appString(R.string.setup_autoeq_detail), state.autoEq) { state.autoEq = it }
            }
            2 -> SetupSource.entries.forEach { source ->
                ChoiceRow(appString(source.title), appString(source.detail), source in state.sources) {
                    state.sources = if (source in state.sources) state.sources - source else state.sources + source
                    if (!state.canMerge) state.merge = false
                }
            }
            3 -> {
                ToggleRow(appString(R.string.setup_merge), appString(R.string.setup_merge_detail), state.merge,
                    enabled = state.canMerge) { state.merge = it }
                if (!state.canMerge) Text(appString(R.string.setup_merge_requirement),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToggleRow(appString(R.string.setup_alarm), appString(R.string.setup_alarm_detail), state.alarm) { state.alarm = it }
                ToggleRow(appString(R.string.setup_offline), appString(R.string.setup_offline_detail), state.offline) { state.offline = it }
            }
        }
    }
}

@Composable
private fun SourcesScreen(state: OnboardingState, connected: Set<ServerType>,
    onConnectSource: (SetupSource) -> Unit, onFinish: () -> Unit) {
    SetupPage(appString(R.string.setup_stage_sources), appString(R.string.setup_connect_title),
        appString(R.string.setup_connect_detail), footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { state.phase = SetupPhase.QUESTIONS; state.page = 2 }) {
                    Text(appString(R.string.setup_back))
                }
                Button(onClick = { state.phase = SetupPhase.TASKS }) { Text(appString(R.string.setup_continue)) }
            }
        }) {
        if (state.sources.isEmpty()) Text(appString(R.string.setup_no_sources),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupSource.entries.filter { it in state.sources }.forEach { source ->
            val isConnected = source.type in connected
            val isSkipped = source in state.skippedSources
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appString(source.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(appString(if (isConnected) R.string.setup_connected else if (isSkipped) R.string.setup_skipped else source.detail),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!isConnected) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { state.skippedSources = state.skippedSources - source; onConnectSource(source) }) {
                            Text(appString(if (source == SetupSource.LOCAL) R.string.setup_allow_local else R.string.setup_connect))
                        }
                        TextButton(onClick = { state.skippedSources = state.skippedSources + source }) {
                            Text(appString(R.string.setup_skip_source))
                        }
                    }
                }
            }
        }
        TextButton(onClick = onFinish) { Text(appString(R.string.setup_finish_for_now)) }
    }
}

@Composable
private fun TasksScreen(state: OnboardingState, connected: Set<ServerType>, hasSession: Boolean,
    onOpenTask: (SetupTask) -> Unit, onFinish: () -> Unit) {
    SetupPage(appString(R.string.setup_stage_preferences), appString(R.string.setup_tasks_title),
        appString(R.string.setup_tasks_detail), footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { state.phase = SetupPhase.SOURCES }) { Text(appString(R.string.setup_back)) }
                Button(onClick = onFinish) { Text(appString(R.string.setup_finish)) }
            }
        }) {
        if (!hasSession && state.tasks.isNotEmpty()) Text(appString(R.string.setup_connect_first),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (state.tasks.isEmpty()) Text(appString(R.string.setup_no_tasks),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.tasks.forEach { task ->
            val done = task in state.completedTasks
            val skipped = task in state.skippedTasks
            val needsSecondSource = task == SetupTask.MERGE &&
                state.sources.count { it.type in connected && it.type.supportsMergedLibrary } < 2
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appString(task.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(appString(task.detail), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (needsSecondSource) Text(appString(R.string.setup_merge_connect_first),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onOpenTask(task) }, enabled = hasSession && !needsSecondSource) {
                            Text(appString(if (done) R.string.setup_open_again else R.string.setup_open))
                        }
                        if (done || skipped) Text(appString(if (done) R.string.setup_done else R.string.setup_skipped),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { state.skippedTasks = state.skippedTasks + task }) {
                            Text(appString(R.string.setup_skip))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthPrompt(source: SetupSource?, onLeave: (Boolean) -> Unit) {
    if (source == null) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp),
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(source.title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { onLeave(false) }) { Text(appString(R.string.setup_back)) }
                TextButton(onClick = { onLeave(true) }) { Text(appString(R.string.setup_skip)) }
            }
        }
    }
}

@Composable
private fun TaskPrompt(task: SetupTask?, onLeave: (Boolean?) -> Unit) {
    if (task == null) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(16.dp),
        contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 520.dp), shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 12.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(appString(task.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(appString(task.detail), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onLeave(null) }) { Text(appString(R.string.setup_back)) }
                    TextButton(onClick = { onLeave(false) }) { Text(appString(R.string.setup_skip)) }
                    Button(onClick = { onLeave(true) }) { Text(appString(R.string.setup_done)) }
                }
            }
        }
    }
}

@Composable
private fun SetupPage(kicker: String, title: String, detail: String,
    footer: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(kicker, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            LazyColumn(Modifier.weight(1f)) {
                item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
            }
            Spacer(Modifier.height(12.dp))
            footer()
        }
    }
}

@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) androidx.compose.material3.Icon(Icons.Filled.Check, null,
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true,
    onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}
