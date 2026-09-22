package com.aurora.music.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aurora.music.data.MockData
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import com.aurora.music.data.SeekStyle
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Waveform
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraBackdrop
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.RepeatMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenSpeedPitch: () -> Unit,
    onOpenQueue: () -> Unit,
    onGoToAlbum: () -> Unit,
    onGoToArtist: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenVisualizer: () -> Unit,
    onOpenSignalPath: () -> Unit,
    onSonicRadio: () -> Unit,
    onAutoDj: () -> Unit,
    onOpenMix: () -> Unit = {},
    videoPlayer: androidx.media3.common.Player? = null,
    onVideoQualityChange: (Int?) -> Unit = {},
    gestures: com.aurora.music.data.GesturePrefs = com.aurora.music.data.GesturePrefs(),
) {
    val song = state.current
    val ui = LocalUiPrefs.current
    val classic = ui.themeStyle == ThemeStyle.AURORA
    var showLyrics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showVideo by androidx.compose.runtime.saveable.rememberSaveable(song.id) { mutableStateOf(false) }
    var fullscreenVideo by androidx.compose.runtime.saveable.rememberSaveable(song.id) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val playerAccent = MaterialTheme.colorScheme.primary
    val onPlayerAccent = MaterialTheme.colorScheme.onPrimary
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val fullscreenActive = fullscreenVideo && showVideo && state.hasVideo && videoPlayer != null
    androidx.compose.runtime.DisposableEffect(activity, fullscreenActive) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreenActive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (fullscreenActive) activity?.window?.let {
                WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    val g = ui.playerGradient
    val bg = Brush.verticalGradient(
        listOf(
            playerAccent.copy(alpha = (0.65f * g).coerceIn(0f, 1f)),
            playerAccent.copy(alpha = (0.22f * g).coerceIn(0f, 1f)),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background,
        )
    )

    // player is outside the scaffold so LocalContentColor defaults to black provide it explicitly
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    DragToDismiss(onDismiss = onCollapse, enabled = gestures.swipeDownDismiss && !showLyrics) {
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (classic) Modifier.background(MaterialTheme.colorScheme.background).background(bg)
                else Modifier.auroraBackdrop()
            )
            // consume taps so nothing leaks through to the app behind
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        val header: @Composable () -> Unit = {
            Column(
                Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .width(40.dp).height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188),
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCollapse).padding(6.dp),
                    )
                    // balances trailing icons so PLAYING FROM stays centered
                    Spacer(Modifier.width(80.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(appString(R.string.text_playing_from_5f4dc3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                        Text(song.album.ifBlank { appString(R.string.text_aurora_eeee9b) }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    }
                    // cast route picker tvs/chromecast show here not in the local-output sheet
                    PlayerCastButton(Modifier.size(40.dp))
                    Icon(
                        Icons.Filled.Speaker, appString(R.string.text_output_device_709178),
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onOpenOutput).padding(8.dp),
                    )
                    Box {
                        Icon(
                            Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d),
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { showMenu = true }.padding(8.dp),
                        )
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text(appString(R.string.text_mix_studio_668363)) }, onClick = { showMenu = false; onOpenMix() }, leadingIcon = { Icon(Icons.Filled.GraphicEq, null) })
                            DropdownMenuItem(
                                text = { Text(appString(R.string.text_sonic_radio_9b7bff)) },
                                onClick = { showMenu = false; onSonicRadio() },
                                leadingIcon = { Icon(Icons.Filled.Radio, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(appString(R.string.text_auto_dj_774a78)) },
                                onClick = { showMenu = false; onAutoDj() },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(appString(R.string.text_visualizer_7177c7)) },
                                onClick = { showMenu = false; onOpenVisualizer() },
                                leadingIcon = { Icon(Icons.Filled.GraphicEq, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(appString(R.string.text_sleep_timer_e90613)) },
                                onClick = { showMenu = false; onOpenSleep() },
                                leadingIcon = { Icon(Icons.Filled.Bedtime, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(appString(R.string.text_go_to_album_e2d3b3)) },
                                enabled = song.albumId.isNotBlank(),
                                onClick = { showMenu = false; onGoToAlbum() },
                                leadingIcon = { Icon(Icons.Filled.Album, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(appString(R.string.text_go_to_artist_d8f70c)) },
                                enabled = song.artistId.isNotBlank(),
                                onClick = { showMenu = false; onGoToArtist() },
                                leadingIcon = { Icon(Icons.Filled.Person, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(appString(R.string.text_view_queue_827a90)) },
                                onClick = { showMenu = false; onOpenQueue() },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

        }
        val artwork: @Composable (Modifier) -> Unit = { modifier ->
            BoxWithConstraints(
                modifier
                    .then(
                        if (gestures.swipeArtwork) Modifier.pointerInput(song.id) {
                            var dx = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = { if (dx < -60f) onNext() else if (dx > 60f) onPrevious(); dx = 0f },
                                onDragCancel = { dx = 0f },
                                onHorizontalDrag = { _, amount -> dx += amount },
                            )
                        } else Modifier
                    )
                    .then(
                        if (gestures.doubleTapPause) Modifier.pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { onTogglePlay() })
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val artSide = minOf(maxWidth, maxHeight) * ui.playerArtSize.coerceIn(0.5f, 1f)
                val videoWidth = minOf(maxWidth, maxHeight * 16f / 9f)
                AnimatedContent(
                    targetState = showVideo,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "artVsVideo",
                ) { video ->
                    if (video && state.hasVideo && videoPlayer != null) {
                        PlaybackVideo(videoPlayer, song.artworkUrl, playerAccent,
                            Modifier.width(videoWidth).aspectRatio(16f / 9f))
                    } else {
                        val artModifier = Modifier.size(artSide)
                        if (classic) {
                            Artwork(song.artworkUrl, song.accent, artModifier, corner = 20.dp)
                        } else {
                            Box(artModifier.auroraPanel(MaterialTheme.shapes.large, emphasized = true).padding(6.dp)) {
                                Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize(), corner = 20.dp)
                            }
                        }
                    }
                }
            }
            if (state.hasVideo && videoPlayer != null) {
                InlineVideoChrome(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
                    showVideo = showVideo,
                    qualityHeight = state.videoQualityHeight,
                    canSelectQuality = state.canSelectVideoQuality,
                    onAudio = { fullscreenVideo = false; showVideo = false },
                    onVideo = { showLyrics = false; showVideo = true },
                    onQualityChange = onVideoQualityChange,
                    onFullscreen = { fullscreenVideo = true },
                )
            }

        }
        val controls: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isPlaying) {
                    com.aurora.music.ui.components.LottieEqualizer(
                        modifier = Modifier.size(28.dp)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(playerAccent, blendMode = BlendMode.SrcIn)
                            },
                        isPlaying = true,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.headlineMedium, fontWeight = if (classic) FontWeight.Bold else MaterialTheme.typography.headlineMedium.fontWeight, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.bpm > 0) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${state.keyName} · ${state.camelot} · ${state.bpm} BPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1,
                        )
                    }
                }
                val likeTint by animateColorAsState(
                    if (state.isCurrentLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "like",
                )
                Icon(
                    imageVector = if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = appString(R.string.text_like_c7e02c),
                    tint = likeTint,
                    modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onToggleLike).padding(8.dp),
                )
            }

            val badge = formatBadge(song)
            val source = sourceLabel(song)
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = appString(R.string.text_open_signal_path_29e042), onClick = onOpenSignalPath)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Icon(Icons.Filled.Route, null, modifier = Modifier.size(16.dp), tint = playerAccent)
                    Spacer(Modifier.width(4.dp))
                    Text(appString(R.string.text_signal_path_c3e29b), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = playerAccent)
                }
                if (source != null) {
                    Box(
                        Modifier.then(
                            if (classic) Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            else Modifier.auroraPanel(MaterialTheme.shapes.extraSmall)
                        ).padding(horizontal = 10.dp, vertical = 3.dp),
                    ) { Text(source, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (isLossless(song.suffix)) {
                    Box(
                        Modifier.clip(if (classic) RoundedCornerShape(50) else MaterialTheme.shapes.extraSmall).background(playerAccent).padding(horizontal = 8.dp, vertical = 3.dp),
                    ) { Text(appString(R.string.text_lossless_32e74a), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = onPlayerAccent) }
                }
                if (badge.isNotEmpty()) {
                    Box(
                        Modifier.then(
                            if (classic) Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            else Modifier.auroraPanel(MaterialTheme.shapes.extraSmall)
                        ).padding(horizontal = 10.dp, vertical = 3.dp),
                    ) { Text(badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                }
            }

            Spacer(Modifier.height(8.dp))

            SeekBar(
                progress = state.progress,
                positionSec = state.positionSec.toInt(),
                durationSec = state.durationSec,
                isLive = state.isLive,
                accent = playerAccent,
                seed = song.id.hashCode(),
                seekStyle = ui.playerSeekStyle,
                waveBars = ui.playerWaveBars,
                onSeek = onSeek,
            )

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth().then(
                    if (classic) Modifier else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true)
                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (state.isMix) Icons.Filled.GraphicEq else Icons.Filled.Shuffle,
                    if (state.isMix) appString(R.string.text_edit_mix_transitions_76ee01) else appString(R.string.text_shuffle_5b772b),
                    tint = if (state.shuffle || state.isMix) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = if (state.isMix) onOpenMix else onToggleShuffle).padding(8.dp),
                )
                Icon(
                    Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onPrevious).padding(6.dp),
                )
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(if (classic) CircleShape else MaterialTheme.shapes.large)
                        .background(playerAccent)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = appString(R.string.text_play_pause_14a1d0),
                        tint = onPlayerAccent,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Icon(
                    Icons.Filled.SkipNext, appString(R.string.text_next_bc9819),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onNext).padding(6.dp),
                )
                Icon(
                    imageVector = if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = appString(R.string.text_repeat_659eba),
                    tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onCycleRepeat).padding(8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (ui.playerShowUtilities) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BottomUtil(
                        Icons.Filled.Speed, appString(R.string.text_speed_x_72da98, ("%.1f".format(state.speed))), onOpenSpeedPitch,
                        active = kotlin.math.abs(state.speed - 1f) > 0.001f ||
                            (!state.matchPitch && kotlin.math.abs(state.pitch) > 0.001f),
                    )
                    BottomUtil(
                        Icons.Filled.Lyrics,
                        appString(R.string.text_lyrics_8670cb),
                        { showLyrics = !showLyrics },
                        active = showLyrics,
                    )
                    BottomUtil(Icons.AutoMirrored.Filled.QueueMusic, appString(R.string.text_queue_d325fc), onOpenQueue)
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
        val landscape = com.aurora.music.ui.layout.LocalWindowLayout.current.useLandscapePlayer
        if (fullscreenActive) {
            FullscreenMusicVideo(
                player = videoPlayer,
                artworkUrl = song.artworkUrl,
                accent = playerAccent,
                state = state,
                qualityHeight = state.videoQualityHeight,
                canSelectQuality = state.canSelectVideoQuality,
                onAudio = { fullscreenVideo = false; showVideo = false },
                onExitFullscreen = { fullscreenVideo = false },
                onVideoQualityChange = onVideoQualityChange,
                onTogglePlay = onTogglePlay,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeek = onSeek,
            )
            androidx.activity.compose.BackHandler(enabled = fullscreenActive) { fullscreenVideo = false }
        } else {
            Column(
                Modifier.align(Alignment.TopCenter).widthIn(max = if (landscape) 1280.dp else 640.dp)
                    .fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
                    .padding(horizontal = if (landscape) 32.dp else 20.dp),
            ) {
                header()
                if (landscape) {
                    Row(Modifier.fillMaxWidth().weight(1f).padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        artwork(Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) { controls() }
                    }
                } else {
                    artwork(Modifier.fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(16.dp))
                    controls()
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showLyrics,
                enter = fadeIn(tween(320)) + androidx.compose.animation.slideInVertically(tween(420)) { it / 10 },
                exit = fadeOut(tween(220)) + androidx.compose.animation.slideOutVertically(tween(280)) { it / 12 },
            ) {
                DragToDismiss(onDismiss = { showLyrics = false }) {
                    LyricsScreen(state, onClose = { showLyrics = false }, onTogglePlay, onPrevious, onNext, onSeek)
                }
            }
            androidx.activity.compose.BackHandler(enabled = showLyrics) { showLyrics = false }
        }
    }
    }
    }
}

@Composable
private fun PlayerVideoModeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "videoModeContainer",
    )
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier.clip(CircleShape).background(container)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = content)
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = content)
    }
}

@Composable
private fun InlineVideoChrome(
    modifier: Modifier,
    showVideo: Boolean,
    qualityHeight: Int?,
    canSelectQuality: Boolean,
    onAudio: () -> Unit,
    onVideo: () -> Unit,
    onQualityChange: (Int?) -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        VideoModeSelector(showVideo, onAudio, onVideo)
        Spacer(Modifier.weight(1f))
        if (showVideo) {
            if (canSelectQuality) VideoQualityControl(qualityHeight, onQualityChange)
            IconButton(
                onClick = onFullscreen,
                modifier = Modifier.padding(start = 6.dp).size(44.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .5f))
                    .semantics { contentDescription = appString(R.string.video_fullscreen_enter) },
            ) { Icon(Icons.Filled.Fullscreen, null, tint = Color.White) }
        }
    }
}

@Composable
private fun VideoModeSelector(showVideo: Boolean, onAudio: () -> Unit, onVideo: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .48f)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerVideoModeButton(appString(R.string.text_audio_acdac2), Icons.Filled.MusicNote, !showVideo, onAudio)
        PlayerVideoModeButton(appString(R.string.text_video_bc17c1), Icons.Filled.VideoLibrary, showVideo, onVideo)
    }
}

@Composable
private fun VideoQualityControl(qualityHeight: Int?, onQualityChange: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = qualityHeight?.let { "${it}p" } ?: appString(R.string.video_quality_auto)
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier.height(44.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = Color.Black.copy(alpha = .5f), contentColor = Color.White,
            ),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(null, 360, 480, 720).forEach { height ->
                val selected = height == qualityHeight
                DropdownMenuItem(
                    text = { Text(height?.let { "${it}p" } ?: appString(R.string.video_quality_auto)) },
                    leadingIcon = { if (selected) Icon(Icons.Filled.Check, null) },
                    onClick = { expanded = false; onQualityChange(height) },
                )
            }
        }
    }
}

@Composable
private fun FullscreenMusicVideo(
    player: androidx.media3.common.Player,
    artworkUrl: String,
    accent: Color,
    state: PlayerUiState,
    qualityHeight: Int?,
    canSelectQuality: Boolean,
    onAudio: () -> Unit,
    onExitFullscreen: () -> Unit,
    onVideoQualityChange: (Int?) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        PlaybackVideo(player, artworkUrl, accent, Modifier.fillMaxSize(), cornerRadius = 0.dp)
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .48f)).padding(4.dp)) {
                PlayerVideoModeButton(appString(R.string.text_audio_acdac2), Icons.Filled.MusicNote, false, onAudio)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.current.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.current.artist, color = Color.White.copy(alpha = .72f),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (canSelectQuality) VideoQualityControl(qualityHeight, onVideoQualityChange)
            IconButton(
                onClick = onExitFullscreen,
                modifier = Modifier.padding(start = 4.dp).size(44.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .48f))
                    .semantics { contentDescription = appString(R.string.video_fullscreen_exit) },
            ) { Icon(Icons.Filled.FullscreenExit, null, tint = Color.White) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f))))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            SeekBar(
                progress = state.progress,
                positionSec = state.positionSec.toInt(),
                durationSec = state.durationSec,
                isLive = state.isLive,
                accent = Color.White,
                seed = state.current.id.hashCode(),
                seekStyle = SeekStyle.BAR,
                waveBars = 0,
                onSeek = onSeek,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942), tint = Color.White) }
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.padding(horizontal = 18.dp).size(54.dp).clip(CircleShape).background(accent),
                ) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        appString(R.string.text_play_pause_14a1d0), tint = MaterialTheme.colorScheme.onPrimary)
                }
                IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819), tint = Color.White) }
            }
        }
    }
}

private fun sourceLabel(song: com.aurora.music.model.Song): String? = when {
    song.streamUrl.isBlank() -> null
    song.streamUrl.startsWith("content://") -> appString(R.string.text_local_dc99d5)
    song.streamUrl.startsWith("file://") -> appString(R.string.text_downloaded_c61970)
    else -> appString(R.string.text_streaming_1e8325)
}

private fun formatBadge(song: com.aurora.music.model.Song): String {
    val parts = mutableListOf<String>()
    if (song.suffix.isNotBlank()) parts.add(song.suffix.uppercase())
    if (song.sampleRateHz > 0) parts.add(appString(R.string.text_1f_khz_92ed69).format(song.sampleRateHz / 1000f))
    if (song.bitDepth > 0) parts.add(appString(R.string.text_bit_fd7850, (song.bitDepth)))
    if (song.bitrateKbps > 0) parts.add(appString(R.string.text_kbps_f89f2e, (song.bitrateKbps)))
    return parts.joinToString(" · ")
}

private fun isLossless(suffix: String): Boolean =
    suffix.lowercase() in setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff", "m4a")

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun BottomUtil(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    Row(
        Modifier.then(
            if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) Modifier.clip(RoundedCornerShape(50))
            else Modifier.auroraPanel(MaterialTheme.shapes.small)
        ).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SeekBar(progress: Float, positionSec: Int, durationSec: Int, isLive: Boolean, accent: Color, seed: Int, seekStyle: Int, waveBars: Int, onSeek: (Float) -> Unit) {
    Column {
        if (durationSec <= 0) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLive) Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(if (isLive) appString(R.string.text_live_6990f0) else appString(R.string.text_streaming_1e8325), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = accent)
                Spacer(Modifier.weight(1f))
                Text(formatTime(positionSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        if (seekStyle == SeekStyle.BAR) {
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = onSeek,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Waveform(
                progress = progress,
                accent = accent,
                onSeek = onSeek,
                seed = seed,
                barCount = waveBars.coerceIn(16, 120),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(durationSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
