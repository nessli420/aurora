package com.aurora.music.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.video.VideoPlayer
import com.aurora.music.localization.appString
import com.aurora.music.ui.components.formatTime
import kotlinx.coroutines.delay

// a headless toolkit can't make cursors, so the pointer just stays visible there
private val HiddenCursor by lazy {
    runCatching {
        PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
            java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "hidden"))
    }.getOrDefault(PointerIcon.Default)
}

// controls and cursor fade after a few idle seconds; a click plays or pauses and a double click leaves full screen
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FullscreenMusicVideo(
    video: VideoPlayer,
    state: PlayerUiState,
    accent: Color,
    onAudio: () -> Unit,
    onExit: () -> Unit,
    onVideoQualityChange: (Int?) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    val wake = { controlsVisible = true; interaction++ }
    LaunchedEffect(controlsVisible, seeking, interaction) {
        if (!controlsVisible || seeking) return@LaunchedEffect
        delay(3_000)
        controlsVisible = false
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { wake() }
            .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else HiddenCursor),
    ) {
        VideoSurface(video, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { wake(); onTogglePlay() }, onDoubleTap = { onExit() })
        })
        AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopCenter), fadeIn(tween(180)), fadeOut(tween(420))) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Transparent)))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayButton(Icons.Filled.MusicNote, appString(R.string.text_audio_acdac2)) { onAudio() }
                Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.current.title, color = Color.White, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.current.artist, color = Color.White.copy(alpha = .72f),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                VideoQualityControl(state.videoQualityHeight, { wake(); onVideoQualityChange(it) }, Modifier.padding(end = 8.dp))
                OverlayButton(Icons.Filled.FullscreenExit, appString(R.string.video_fullscreen_exit)) { onExit() }
            }
        }
        AnimatedVisibility(controlsVisible, Modifier.align(Alignment.BottomCenter), fadeIn(tween(180)), fadeOut(tween(420))) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f))))
                    .padding(horizontal = 32.dp, vertical = 20.dp),
            ) {
                if (state.durationSec > 0) {
                    Slider(
                        value = state.progress.coerceIn(0f, 1f),
                        onValueChange = { seeking = true; wake(); onSeek(it) },
                        onValueChangeFinished = { seeking = false },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = .3f)),
                        modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(state.positionSec.toInt()), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelMedium)
                        Text(formatTime(state.durationSec), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { wake(); onPrevious() }, Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942), tint = Color.White)
                    }
                    IconButton(
                        onClick = { wake(); onTogglePlay() },
                        modifier = Modifier.padding(horizontal = 18.dp).size(56.dp).clip(CircleShape).background(accent).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            appString(R.string.text_play_pause_14a1d0), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = { wake(); onNext() }, Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819), tint = Color.White)
                    }
                }
            }
        }
    }
    BackHandler { onExit() }
}

@Composable
private fun OverlayButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = .48f)).pointerHoverIcon(PointerIcon.Hand)) {
        Icon(icon, label, tint = Color.White)
    }
}
