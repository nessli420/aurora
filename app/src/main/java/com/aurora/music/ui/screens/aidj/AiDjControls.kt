package com.aurora.music.ui.screens.aidj

import androidx.compose.animation.core.RepeatMode
import com.aurora.music.ui.theme.paperClip
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.aurora.music.ui.theme.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.ai.DjFact
import com.aurora.music.ai.DjMode
import com.aurora.music.ai.DjModelState
import com.aurora.music.ai.DjModelStore
import com.aurora.music.ai.DjMoment
import com.aurora.music.ai.DjScript
import com.aurora.music.ai.DjSegment
import com.aurora.music.ai.DjSet
import com.aurora.music.localization.appString
import com.aurora.music.ui.screens.settings.storageSize

fun aiDjLabel(state: DjMode.State): String = state.intent?.let { it.title.ifBlank { state.prompt } }
    ?: state.segment?.let { aiDjSetLabel(it, state.artist) } ?: appString(R.string.ai_dj_busy)

fun aiDjSetLabel(segment: DjSegment, artist: String?): String = when (segment) {
    DjSegment.REPEAT -> appString(R.string.ai_dj_segment_repeat)
    DjSegment.HOUR -> appString(R.string.ai_dj_segment_hour)
    DjSegment.PAST -> appString(R.string.ai_dj_segment_past)
    DjSegment.NEW -> appString(R.string.ai_dj_segment_new)
    DjSegment.LIKED -> appString(R.string.ai_dj_segment_liked)
    DjSegment.ARTIST -> appString(R.string.ai_dj_segment_artist, artist.orEmpty())
    DjSegment.BECAUSE -> appString(R.string.ai_dj_segment_because, artist.orEmpty())
    DjSegment.DEEP -> appString(R.string.ai_dj_segment_deep)
    DjSegment.MIX -> appString(R.string.ai_dj_segment_mix)
}

@Composable
fun AiDjQueueBanner(state: DjMode.State, onSteer: (String) -> Unit, onSwitch: () -> Unit, onStop: () -> Unit, autoFocus: Boolean = false) {
    var steer by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val send = { if (steer.isNotBlank() && !state.busy) { onSteer(steer); steer = "" } }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.ai_dj_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(aiDjLabel(state), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onSwitch, enabled = !state.busy) { Text(appString(R.string.ai_dj_switch), maxLines = 1) }
                TextButton(onClick = onStop) { Text(appString(R.string.ai_dj_stop), maxLines = 1) }
            }
            OutlinedTextField(
                value = steer,
                onValueChange = { steer = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
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

private fun setLine(set: DjSet, turn: Int): String = when (set.segment) {
    DjSegment.REPEAT -> appString(if (turn % 2 == 0) R.string.ai_dj_line_repeat else R.string.ai_dj_line_repeat_2)
    DjSegment.HOUR -> appString(if (turn % 2 == 0) R.string.ai_dj_line_hour else R.string.ai_dj_line_hour_2)
    DjSegment.PAST -> appString(if (turn % 2 == 0) R.string.ai_dj_line_past else R.string.ai_dj_line_past_2)
    DjSegment.NEW -> appString(if (turn % 2 == 0) R.string.ai_dj_line_new else R.string.ai_dj_line_new_2)
    DjSegment.LIKED -> appString(if (turn % 2 == 0) R.string.ai_dj_line_liked else R.string.ai_dj_line_liked_2)
    DjSegment.ARTIST -> appString(if (turn % 2 == 0) R.string.ai_dj_line_artist else R.string.ai_dj_line_artist_2, set.artist.orEmpty())
    DjSegment.BECAUSE -> appString(R.string.ai_dj_line_because, set.artist.orEmpty())
    DjSegment.DEEP -> appString(R.string.ai_dj_line_deep)
    DjSegment.MIX -> appString(if (turn % 2 == 0) R.string.ai_dj_line_mix else R.string.ai_dj_line_mix_2)
}

private fun factLine(moment: DjMoment): String? {
    val s = moment.stats ?: return null
    return when (moment.fact) {
        DjFact.HOURS -> appString(R.string.ai_dj_line_hours, s.hoursThisWeek, s.topArtist.orEmpty())
        DjFact.TRACK -> appString(R.string.ai_dj_line_track, s.topTrack.orEmpty(), s.topTrackPlays)
        DjFact.ARTISTS -> appString(R.string.ai_dj_fact_artists, s.artistsThisWeek)
        DjFact.STREAK -> appString(R.string.ai_dj_fact_streak, s.streakDays)
        DjFact.NEW_ARTIST -> appString(R.string.ai_dj_fact_new_artist, s.newArtist.orEmpty())
        DjFact.MONTH_ARTIST -> appString(R.string.ai_dj_fact_month_artist, s.monthArtist.orEmpty())
        null -> null
    }
}

private val GREETINGS = listOf(R.string.ai_dj_greeting, R.string.ai_dj_greeting_2, R.string.ai_dj_greeting_3, R.string.ai_dj_greeting_4)
private val SWITCHES = listOf(R.string.ai_dj_line_switch, R.string.ai_dj_line_switch_2, R.string.ai_dj_line_switch_3)

fun aiDjScript(moment: DjMoment): DjScript {
    val title = appString(R.string.ai_dj_title)
    val turn = moment.turn
    return when (moment) {
        is DjMoment.Welcome -> DjScript(listOfNotNull(appString(GREETINGS[turn % GREETINGS.size]), factLine(moment), setLine(moment.set, turn)).joinToString(" "), title, aiDjSetLabel(moment.set.segment, moment.set.artist))
        is DjMoment.NextSet -> DjScript(listOfNotNull(factLine(moment), setLine(moment.set, turn)).joinToString(" "), title, aiDjSetLabel(moment.set.segment, moment.set.artist))
        is DjMoment.Switch -> DjScript("${appString(SWITCHES[turn % SWITCHES.size])} ${setLine(moment.set, turn)}", title, aiDjSetLabel(moment.set.segment, moment.set.artist))
        is DjMoment.Steered -> DjScript(appString(R.string.ai_dj_line_steer, moment.request), title, moment.request)
        is DjMoment.More -> DjScript(appString(R.string.ai_dj_line_more, moment.request), title, moment.request)
    }
}

// replaces the cover while the dj talks, listens or works out a request
@Composable
fun AiDjStage(state: DjMode.State, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "aiDjStage")
    val scale = pulse.animateFloat(0.86f, 1.14f, infiniteRepeatable(tween(if (state.listening) 520 else 1100), RepeatMode.Reverse), label = "aiDjPulse")
    val caption = when {
        state.listening -> appString(R.string.ai_dj_listening)
        state.thinking -> appString(R.string.ai_dj_thinking)
        else -> state.speech?.text.orEmpty()
    }
    Column(modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = 0.55f }
                .background(Brush.radialGradient(listOf(colors.primary, Color.Transparent)), CircleShape))
            Box(Modifier.size(88.dp).paperClip(CircleShape).background(Brush.linearGradient(listOf(colors.primary, colors.tertiary))), contentAlignment = Alignment.Center) {
                if (state.thinking) CircularProgressIndicator(Modifier.size(34.dp), color = colors.onPrimary, strokeWidth = 3.dp)
                else Icon(if (state.listening) Icons.Filled.Mic else Icons.Filled.AutoAwesome, null, tint = colors.onPrimary, modifier = Modifier.size(42.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(caption, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        val stats = state.speech?.stats?.takeUnless { state.listening || state.thinking }
        if (stats != null) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stats.hoursThisWeek >= 2) AiDjStat(stats.hoursThisWeek.toString(), appString(R.string.ai_dj_stat_hours), Modifier.weight(1f))
                stats.topArtist?.let { AiDjStat(it, appString(R.string.ai_dj_stat_artist), Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AiDjStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.paperClip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AiDjPlayerButton(state: DjMode.State, onPress: () -> Unit, onHold: () -> Unit, onRelease: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val tint = LocalContentColor.current
    val label = appString(R.string.ai_dj_control)
    Box(
        Modifier.size(40.dp).paperClip(CircleShape).background(if (state.listening) colors.primary else Color.Transparent)
            .semantics { contentDescription = label; role = Role.Button; onClick { press(); true } }
            .pointerInput(Unit) {
                var holding = false
                detectTapGestures(
                    onPress = { tryAwaitRelease(); if (holding) { holding = false; release() } },
                    onLongPress = { holding = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress); hold() },
                    onTap = { press() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (state.listening) Icons.Filled.Mic else Icons.Filled.AutoAwesome, null, tint = if (state.listening) colors.onPrimary else tint, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDjSheet(
    state: DjMode.State,
    spoken: Boolean,
    speakerModels: DjModelStore?,
    voiceModels: DjModelStore?,
    onSteer: (String) -> Unit,
    onSwitch: () -> Unit,
    onStop: () -> Unit,
    onSpoken: (Boolean) -> Unit,
    onDownloadSpeaker: () -> Unit,
    onDownloadVoice: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiDjQueueBanner(state, { onSteer(it); onDismiss() }, { onSwitch(); onDismiss() }, { onStop(); onDismiss() }, autoFocus = true)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.ai_dj_spoken), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(spoken, onSpoken)
            }
            speakerModels?.let { AiDjModelLine(it, appString(R.string.ai_dj_speaker_ready), appString(R.string.ai_dj_speaker_missing, storageSize(it.spec.bytes)), onDownloadSpeaker) }
            voiceModels?.let { AiDjModelLine(it, appString(R.string.ai_dj_hold_hint), appString(R.string.ai_dj_voice_missing, storageSize(it.spec.bytes)), onDownloadVoice) }
        }
    }
}

@Composable
private fun AiDjModelLine(store: DjModelStore, ready: String, missing: String, onDownload: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val model by store.state.collectAsStateWithLifecycle()
    when (val m = model) {
        DjModelState.Ready -> Text(ready, style = MaterialTheme.typography.bodySmall, color = muted)
        is DjModelState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(progress = { m.bytes.toFloat() / m.total }, modifier = Modifier.fillMaxWidth())
            Text(appString(R.string.ai_dj_model_downloading, storageSize(m.bytes), storageSize(m.total)), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        DjModelState.Verifying -> Text(appString(R.string.ai_dj_model_verifying), style = MaterialTheme.typography.bodySmall, color = muted)
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (m as? DjModelState.Failed)?.let { Text(aiDjFailure(it.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(missing, style = MaterialTheme.typography.bodySmall, color = muted)
            FilledTonalButton(onClick = onDownload) { Text(appString(R.string.ai_dj_model_download)) }
        }
    }
}
