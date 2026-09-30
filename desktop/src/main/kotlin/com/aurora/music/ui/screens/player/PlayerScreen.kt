package com.aurora.music.ui.screens.player

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import com.aurora.music.data.GesturePrefs
import com.aurora.music.data.SeekStyle
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.player.RepeatMode
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.VolumeControl
import com.aurora.music.ui.components.Waveform
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraBackdrop
import com.aurora.music.ui.theme.auroraPanel
import java.awt.Cursor
import com.aurora.music.model.accent

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
    onGoToAlbum: () -> Unit,
    onGoToArtist: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenVisualizer: () -> Unit,
    onOpenSignalPath: () -> Unit,
    onSonicRadio: () -> Unit,
    onAutoDj: () -> Unit,
    paneContent: @Composable (PlayerPane, Modifier) -> Unit,
    gestures: GesturePrefs = GesturePrefs(),
    requestedPane: PlayerPane? = null,
    onPaneRequestHandled: () -> Unit = {},
    paneActions: (@Composable (PlayerPane) -> Unit)? = null,
    onSplitChange: (Float) -> Unit = {},
    volume: Float = LocalPlayer.current.volume.collectAsState().value,
    onVolumeChange: (Float) -> Unit = LocalPlayer.current::setVolume,
    onToggleMute: () -> Unit = LocalPlayer.current::toggleMute,
) {
    val song = state.current
    val ui = LocalUiPrefs.current
    val classic = ui.themeStyle == ThemeStyle.AURORA
    var showLyrics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var pickedPane by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var lyricsMissing by remember { mutableStateOf(false) }
    val sidePane = pickedPane?.let(PlayerPane::valueOf) ?: if (lyricsMissing) PlayerPane.QUEUE else PlayerPane.LYRICS
    val reportLyrics = remember { { available: Boolean -> lyricsMissing = !available } }
    LaunchedEffect(requestedPane) {
        if (requestedPane == null) return@LaunchedEffect
        pickedPane = requestedPane.name
        onPaneRequestHandled()
    }
    var showMenu by remember { mutableStateOf(false) }
    val playerAccent = MaterialTheme.colorScheme.primary
    val onPlayerAccent = MaterialTheme.colorScheme.onPrimary
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
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(
                if (classic) Modifier.background(MaterialTheme.colorScheme.background).background(bg)
                else Modifier.auroraBackdrop()
            )
            // consume taps so nothing leaks through to the app behind
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        val short = maxHeight < 700.dp
        val header: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = if (short) 8.dp else 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188),
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onCollapse).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
                )
                // balances trailing icons so PLAYING FROM stays centered
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(appString(R.string.text_playing_from_5f4dc3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                    MarqueeTitle(song.album.ifBlank { appString(R.string.text_aurora_eeee9b) })
                }
                Icon(
                    Icons.Filled.Speaker, appString(R.string.text_output_device_709178),
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onOpenOutput).pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                )
                Box {
                    Icon(
                        Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d),
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { showMenu = true }.pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                    )
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
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
                            onClick = { showMenu = false; pickedPane = PlayerPane.QUEUE.name },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                        )
                    }
                }
            }
        }
        val artwork: @Composable (Modifier) -> Unit = { modifier ->
            Box(
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
                if (classic) {
                    Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize(), corner = 20.dp)
                } else {
                    Box(Modifier.fillMaxSize().auroraPanel(MaterialTheme.shapes.large, emphasized = true).padding(6.dp)) {
                        Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize(), corner = 20.dp)
                    }
                }
            }
        }
        val controls: @Composable (Dp) -> Unit = { volumeWidth ->
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
                    modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onToggleLike).pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                )
            }

            val badge = formatBadge(song)
            val source = sourceLabel(song)
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = appString(R.string.text_open_signal_path_29e042), onClick = onOpenSignalPath)
                    .pointerHoverIcon(PointerIcon.Hand)
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
                    Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b),
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onToggleShuffle).pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                )
                Icon(
                    Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onPrevious).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
                )
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(if (classic) CircleShape else MaterialTheme.shapes.large)
                        .background(playerAccent)
                        .clickable(onClick = onTogglePlay)
                        .pointerHoverIcon(PointerIcon.Hand),
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
                    modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onNext).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
                )
                Icon(
                    imageVector = if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = appString(R.string.text_repeat_659eba),
                    tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onCycleRepeat).pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.playerShowUtilities) {
                    BottomUtil(
                        Icons.Filled.Speed, appString(R.string.text_speed_x_72da98, ("%.1f".format(state.speed))), onOpenSpeedPitch,
                        active = kotlin.math.abs(state.speed - 1f) > 0.001f,
                    )
                    BottomUtil(Icons.Filled.Bedtime, appString(R.string.text_sleep_timer_e90613), onOpenSleep,
                        active = state.sleepTimerMinutes > 0 || state.sleepEndOfTrack)
                }
                Spacer(Modifier.weight(1f))
                VolumeControl(volume, onVolumeChange, onToggleMute, volumeWidth, fill = playerAccent)
            }
        }
        val paneSurface = if (classic) Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge)
        val spacing = ui.tabletPanelSpacing.dp.coerceAtLeast(4.dp)
        var split by remember(ui.tabletPlayerSplit) { mutableFloatStateOf(ui.tabletPlayerSplit) }
        val splitRange = com.aurora.music.data.TabletSetting.PLAYER_SPLIT.range
        val density = androidx.compose.ui.platform.LocalDensity.current
        Column(Modifier.fillMaxSize()) {
            header()
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(start = 32.dp, end = 32.dp, bottom = if (short) 16.dp else 24.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                BoxWithConstraints(Modifier.widthIn(max = BodyMaxWidth).fillMaxSize()) {
                    val totalPx = with(density) { maxWidth.toPx() }
                    Row(Modifier.fillMaxSize()) {
                        BoxWithConstraints(Modifier.weight(split).fillMaxHeight()) {
                            val gap = if (short) 12.dp else 20.dp
                            var controlsHeight by remember { mutableStateOf(0.dp) }
                            val reserve = if (controlsHeight > 0.dp) controlsHeight else ControlsEstimate
                            val artScale = ui.playerArtSize.coerceIn(0.5f, 1.2f) / DefaultArtSize
                            val fit = (maxHeight - reserve - gap).coerceAtMost(maxWidth - 32.dp)
                            val artSide = minOf(fit * artScale.coerceAtMost(1f), MaxArt * artScale).coerceAtLeast(MinArt).coerceAtMost(maxWidth)
                            val controlsWidth = artSide.coerceAtLeast(420.dp).coerceAtMost(maxWidth)
                            Column(
                                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                artwork(Modifier.size(artSide))
                                Spacer(Modifier.height(gap))
                                Column(Modifier.width(controlsWidth).onSizeChanged { controlsHeight = with(density) { it.height.toDp() } }) {
                                    controls(if (controlsWidth >= 480.dp) 160.dp else 120.dp)
                                }
                            }
                        }
                        PaneDivider(
                            onDrag = { dx -> if (totalPx > 0f) split = (split + dx / totalPx).coerceIn(splitRange) },
                            onDragEnd = { onSplitChange(split) },
                        )
                        Column(Modifier.weight(1f - split).fillMaxHeight().then(paneSurface).padding(spacing)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                PaneSwitcher(sidePane, { pickedPane = it.name })
                                Spacer(Modifier.weight(1f))
                                if (sidePane == PlayerPane.LYRICS) {
                                    IconButton(onClick = { showLyrics = true }) {
                                        Icon(Icons.Filled.OpenInFull, appString(R.string.tablet_fullscreen_lyrics),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    }
                                } else paneActions?.invoke(sidePane)
                            }
                            Spacer(Modifier.height(spacing))
                            AnimatedContent(
                                targetState = sidePane,
                                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                                label = "playerPane",
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) { target ->
                                Box(Modifier.fillMaxSize()) {
                                    CompositionLocalProvider(LocalLyricsAvailability provides if (pickedPane == null) reportLyrics else null) {
                                        paneContent(target, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        LyricsOverlay(showLyrics, state, onClose = { showLyrics = false }, onTogglePlay, onPrevious, onNext, onSeek)
    }
    }
}

private const val DefaultArtSize = 0.86f
private val MaxArt = 640.dp
private val MinArt = 160.dp
private val ControlsEstimate = 330.dp
private val BodyMaxWidth = 1680.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LyricsOverlay(
    visible: Boolean,
    state: PlayerUiState,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(320)) + androidx.compose.animation.scaleIn(tween(380), initialScale = .96f),
        exit = fadeOut(tween(240)) + androidx.compose.animation.scaleOut(tween(260), targetScale = .97f),
    ) {
        LyricsScreen(state, onClose = onClose, onTogglePlay, onPrevious, onNext, onSeek)
    }
    BackHandler(enabled = visible) { onClose() }
}

@Composable
private fun PaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (dragging) 0.5f else if (hovered) 0.32f else 0.16f, tween(160), label = "dividerAlpha")
    val length by androidx.compose.animation.core.animateDpAsState(if (dragging) 64.dp else 36.dp, tween(160), label = "dividerLength")
    val description = appString(R.string.tablet_resize_panels)
    Box(
        Modifier.width(24.dp).fillMaxHeight()
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; end() },
                    onDragCancel = { dragging = false; end() },
                ) { change, amount -> change.consume(); drag(amount) }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(length).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)))
    }
}

@Composable
private fun MarqueeTitle(text: String) {
    var fullWidth by remember(text) { androidx.compose.runtime.mutableIntStateOf(0) }
    var shownWidth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val overflowing = fullWidth > shownWidth && shownWidth > 0
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { fullWidth = it.size.width },
        modifier = Modifier
            .onSizeChanged { shownWidth = it.width }
            .then(if (overflowing) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn)
            } else Modifier)
            .basicMarquee(initialDelayMillis = 1500, repeatDelayMillis = 2500),
    )
}

private fun sourceLabel(song: com.aurora.music.model.Song): String? = when {
    song.streamUrl.isBlank() -> null
    song.playbackSource?.source == RuleSource.DOWNLOAD -> appString(R.string.text_downloaded_c61970)
    song.streamUrl.startsWith("file:") -> appString(R.string.text_local_dc99d5)
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

@Composable
private fun BottomUtil(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    Row(
        Modifier.then(
            if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) Modifier.clip(RoundedCornerShape(50))
            else Modifier.auroraPanel(MaterialTheme.shapes.small)
        ).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SeekBar(
    progress: Float,
    positionSec: Int,
    durationSec: Int,
    isLive: Boolean,
    accent: Color,
    seed: Int,
    seekStyle: Int,
    waveBars: Int,
    onSeek: (Float) -> Unit,
) {
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
