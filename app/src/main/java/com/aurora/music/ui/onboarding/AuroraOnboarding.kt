package com.aurora.music.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.MaterialTheme
import com.aurora.music.ui.theme.OutlinedButton
import androidx.compose.material3.Surface
import com.aurora.music.ui.theme.Switch
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.pressClickable
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
        SetupPhase.QUESTIONS, SetupPhase.SOURCES, SetupPhase.TASKS ->
            SetupFlow(state, connected, hasSession, onConnectSource, onOpenTask, onFinish, onChoicesConfirmed)
        SetupPhase.AUTH -> AuthPrompt(state.connectingSource, onLeaveAuth)
        SetupPhase.TASK -> TaskPrompt(state.activeTask, onLeaveTask)
    }
}

// the four questions, sources and tasks as steps 0..5 of one page, so a step slides the way it travels
@Composable
private fun SetupFlow(state: OnboardingState, connected: Set<ServerType>, hasSession: Boolean,
    onConnectSource: (SetupSource) -> Unit, onOpenTask: (SetupTask) -> Unit, onFinish: () -> Unit, onChoicesConfirmed: () -> Unit) {
    val step = when (state.phase) { SetupPhase.QUESTIONS -> state.page; SetupPhase.SOURCES -> 4; else -> 5 }
    SetupPage(step, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                when (state.phase) {
                    SetupPhase.QUESTIONS -> if (state.page > 0) state.page-- else onFinish()
                    SetupPhase.SOURCES -> { state.phase = SetupPhase.QUESTIONS; state.page = 2 }
                    else -> state.phase = SetupPhase.SOURCES
                }
            }) { FooterLabel(appString(if (step > 0) R.string.setup_back else R.string.onboarding_skip)) }
            Button(onClick = {
                when (state.phase) {
                    SetupPhase.QUESTIONS -> if (state.page < 3) state.page++ else {
                        onChoicesConfirmed()
                        state.phase = SetupPhase.SOURCES
                    }
                    SetupPhase.SOURCES -> state.phase = SetupPhase.TASKS
                    else -> onFinish()
                }
            }) { FooterLabel(appString(when { step < 3 -> R.string.onboarding_next; step == 5 -> R.string.setup_finish; else -> R.string.setup_continue })) }
        }
    }) { shown ->
        when (shown) {
            4 -> SourcesScreen(state, connected, onConnectSource, onFinish)
            5 -> TasksScreen(state, connected, hasSession, onOpenTask)
            else -> QuestionsScreen(state, shown)
        }
    }
}

@Composable
private fun FooterLabel(text: String) {
    AnimatedContent(text, transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
        contentAlignment = Alignment.Center, label = "setupLabel") { Text(it, maxLines = 1) }
}

@Composable
private fun QuestionsScreen(state: OnboardingState, page: Int) {
    val titles = listOf(R.string.setup_listening_title, R.string.setup_gear_title,
        R.string.setup_sources_title, R.string.setup_features_title)
    val details = listOf(R.string.setup_listening_detail, R.string.setup_gear_detail,
        R.string.setup_sources_detail, R.string.setup_features_detail)
    SetupStep(appString(R.string.setup_progress, page + 1, 4), appString(titles[page]),
        appString(details[page])) {
        when (page) {
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
    SetupStep(appString(R.string.setup_stage_sources), appString(R.string.setup_connect_title),
        appString(R.string.setup_connect_detail)) {
        if (state.sources.isEmpty()) Text(appString(R.string.setup_no_sources),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupSource.entries.filter { it in state.sources }.forEach { source ->
            val isConnected = source.type in connected
            val isSkipped = source in state.skippedSources
            Surface(shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
    onOpenTask: (SetupTask) -> Unit) {
    SetupStep(appString(R.string.setup_stage_preferences), appString(R.string.setup_tasks_title),
        appString(R.string.setup_tasks_detail)) {
        if (!hasSession && state.tasks.isNotEmpty()) Text(appString(R.string.setup_connect_first),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (state.tasks.isEmpty()) Text(appString(R.string.setup_no_tasks),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.tasks.forEach { task ->
            val done = task in state.completedTasks
            val skipped = task in state.skippedTasks
            val needsSecondSource = task == SetupTask.MERGE &&
                state.sources.count { it.type in connected && it.type.supportsMergedLibrary } < 2
            Surface(shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
                        AnimatedContent(done || skipped, transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                            contentAlignment = Alignment.CenterStart, label = "taskMark") { marked ->
                        if (marked) Text(appString(if (done) R.string.setup_done else R.string.setup_skipped),
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
}

@Composable
private fun AuthPrompt(source: SetupSource?, onLeave: (Boolean) -> Unit) {
    if (source == null) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp),
            shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(source.title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Surface(Modifier.fillMaxWidth().widthIn(max = 520.dp), shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(22.dp)),
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
private fun SetupPage(step: Int, footer: @Composable () -> Unit, content: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp, vertical = if (wide) 32.dp else 14.dp)) {
            AnimatedContent(step, Modifier.weight(1f).fillMaxWidth(), transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (fadeIn(Motion.enter()) + slideInHorizontally(Motion.enter()) { dir * it / 10 }) togetherWith
                    (fadeOut(Motion.exit()) + slideOutHorizontally(Motion.exit()) { -dir * it / 10 }) using SizeTransform(clip = false)
            }, label = "setupStep") { shown -> content(shown) }
            Spacer(Modifier.height(12.dp))
            footer()
        }
    }
}

@Composable
private fun SetupStep(kicker: String, title: String, detail: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
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
    }
}

@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp))
    val target = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fill by animateColorAsState(target, Motion.state(), label = "choiceFill")
    val edge by animateColorAsState(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 1f else 0f), Motion.state(), label = "choiceEdge")
    Surface(Modifier.clip(shape).pressClickable(onClick = onClick), shape = shape,
        color = fill, contentColor = contentColorFor(target), border = BorderStroke(1.dp, edge)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(selected, enter = fadeIn(Motion.enter()) + scaleIn(Motion.enter()),
                exit = fadeOut(Motion.exit()) + scaleOut(Motion.exit())) {
                androidx.compose.material3.Icon(Icons.Filled.Check, null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true,
    onChange: (Boolean) -> Unit) {
    Surface(shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
