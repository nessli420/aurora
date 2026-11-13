package com.aurora.music.ui.screens.player

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.ui.theme.paperClip
import kotlinx.coroutines.flow.map
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.SpeakerNotes
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.SpeakerNotesOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import com.aurora.music.ui.theme.FilledTonalButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aurora.music.data.MockData
import com.aurora.music.data.isPodcast
import com.aurora.music.ai.isDjSpeech
import com.aurora.music.data.isRadio
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import com.aurora.music.data.SeekStyle
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.CoverKey
import com.aurora.music.ui.components.Waveform
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraBackdrop
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.liquidGlass
import com.kyant.backdrop.backdrops.layerBackdrop
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.RepeatMode
import kotlinx.coroutines.delay
import com.aurora.music.model.accent

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    surfaceOffset: androidx.compose.runtime.MutableFloatState? = null,
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
    djControl: (@Composable () -> Unit)? = null,
    djStage: (@Composable (Modifier) -> Unit)? = null,
    onOpenMix: () -> Unit = {},
    onPostComment: (String) -> Unit = {},
    onToggleComments: (Boolean) -> Unit = {},
    onOpenRelated: (() -> Unit)? = null,
    videoPlayer: androidx.media3.common.Player? = null,
    onVideoQualityChange: (Int?) -> Unit = {},
    onRequestVideo: (canvas: Boolean) -> Unit = {},
    onVideoVisibleChange: (Boolean) -> Unit = {},
    gestures: com.aurora.music.data.GesturePrefs = com.aurora.music.data.GesturePrefs(),
    requestedPane: PlayerPane? = null,
    onPaneRequestHandled: () -> Unit = {},
    paneContent: (@Composable (PlayerPane, Modifier) -> Unit)? = null,
    paneActions: (@Composable (PlayerPane) -> Unit)? = null,
    onSplitChange: (Float) -> Unit = {},
) {
    val song = state.current
    val openAlbum = onGoToAlbum.takeIf { song.albumId.isNotBlank() }
    val openArtist = onGoToArtist.takeIf { song.artistId.isNotBlank() }
    val ui = LocalUiPrefs.current
    val classic = ui.themeStyle == ThemeStyle.AURORA
    val glassStyle = ui.themeStyle == ThemeStyle.GLASS
    val playerBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val glassBackdrop = playerBackdrop.takeIf { glassStyle && com.aurora.music.ui.theme.liveGlassSupported }
    val windowLayout = com.aurora.music.ui.layout.LocalWindowLayout.current
    val tablet = windowLayout.useNavigationRail && paneContent != null
    var showLyrics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showCover by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showVideo by androidx.compose.runtime.saveable.rememberSaveable(song.id) { mutableStateOf(false) }
    var sidePaneName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(PlayerPane.LYRICS.name) }
    var portraitTabName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var showComments by remember { mutableStateOf(false) }
    var focusComment by remember { mutableStateOf<String?>(null) }
    if (showComments && state.supportsComments) CommentsSheet(state, focusComment, onDismiss = { showComments = false; focusComment = null },
        onSeek = onSeek, onPost = onPostComment)
    val panes = LocalPlayerPanes.current
    val playbackPosition = com.aurora.music.ui.components.LocalPlaybackPosition.current
    val skipBy = { seconds: Int ->
        if (state.durationSec > 0) onSeek(((playbackPosition.value + seconds) / state.durationSec).coerceIn(0f, 1f))
    }
    val sidePane = PlayerPane.valueOf(sidePaneName).takeIf { it in panes } ?: PlayerPane.LYRICS
    val portraitTab = portraitTabName?.let { PlayerPane.valueOf(it) }?.takeIf { it in panes }
    LaunchedEffect(requestedPane) {
        when {
            requestedPane == null -> return@LaunchedEffect
            tablet -> { sidePaneName = requestedPane.name; portraitTabName = requestedPane.name }
            requestedPane == PlayerPane.LYRICS -> { showVideo = false; showLyrics = true }
            else -> onOpenQueue()
        }
        onPaneRequestHandled()
    }
    var requestedVideo by remember(song.id) { mutableStateOf(false) }
    var fullscreenVideo by androidx.compose.runtime.saveable.rememberSaveable(song.id) { mutableStateOf(false) }
    var inlineVideoControlsVisible by remember(song.id) { mutableStateOf(false) }
    var inlineVideoInteraction by remember(song.id) { mutableStateOf(0) }
    val playerAccent = MaterialTheme.colorScheme.primary
    val onPlayerAccent = MaterialTheme.colorScheme.onPrimary
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val fullscreenActive = fullscreenVideo && showVideo && state.hasVideo && videoPlayer != null && !state.videoIsCanvas
    val canvasShown = showVideo && state.hasVideo && state.videoIsCanvas && videoPlayer != null
    LaunchedEffect(state.hasVideo, requestedVideo) {
        if (requestedVideo && state.hasVideo) {
            showLyrics = false
            showVideo = true
            requestedVideo = false
        }
    }
    DisposableEffect(showVideo) {
        onVideoVisibleChange(showVideo)
        onDispose { if (showVideo) onVideoVisibleChange(false) }
    }
    LaunchedEffect(showVideo, inlineVideoControlsVisible, inlineVideoInteraction) {
        if (!showVideo || !inlineVideoControlsVisible) return@LaunchedEffect
        delay(3_000)
        inlineVideoControlsVisible = false
    }
    androidx.compose.runtime.DisposableEffect(activity, fullscreenActive) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreenActive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (fullscreenActive) {
                activity?.window?.let {
                    WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
                if (previousOrientation != null) activity.requestedOrientation = previousOrientation
            }
        }
    }
    val g = ui.playerGradient
    val playerBg = MaterialTheme.colorScheme.background
    // the scheme snaps per track; only this wash fades, read in draw so the player doesn't recompose per frame
    val fade by animateColorAsState(playerAccent, tween(450), label = "playerWash")
    // 1 lyrics, 2 cover: once an opaque overlay settles the base stops drawing under it
    val overlay = androidx.compose.animation.core.updateTransition(
        when { fullscreenActive -> 0; showLyrics -> 1; showCover -> 2; else -> 0 }, label = "overlay")
    val lyricsOffset = remember { mutableFloatStateOf(0f) }
    val coverOffset = remember { mutableFloatStateOf(0f) }
    val covered = remember {
        androidx.compose.runtime.derivedStateOf {
            overlay.currentState != 0 && overlay.currentState == overlay.targetState && !overlay.isRunning &&
                lyricsOffset.floatValue == 0f && coverOffset.floatValue == 0f
        }
    }
    val underOverlay = remember { Modifier.graphicsLayer { alpha = if (covered.value) 0f else 1f } }

    // player is outside the scaffold so LocalContentColor defaults to black provide it explicitly
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    DragToDismiss(onDismiss = onCollapse, enabled = gestures.swipeDownDismiss && !showLyrics && !showCover, shown = state.expanded, offsetState = surfaceOffset) {
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (classic) Modifier.background(playerBg).drawWithCache {
                    val wash = Brush.verticalGradient(listOf(fade.copy(alpha = (0.65f * g).coerceIn(0f, 1f)),
                        fade.copy(alpha = (0.22f * g).coerceIn(0f, 1f)), playerBg, playerBg))
                    onDrawBehind { drawRect(wash) }
                }
                else if (glassBackdrop != null) Modifier.background(MaterialTheme.colorScheme.background)
                else Modifier.auroraBackdrop()
            )
            // consume taps so nothing leaks through to the app behind
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        if (glassBackdrop != null) GlassArtBackdrop(song, Modifier.matchParentSize().layerBackdrop(playerBackdrop))
        androidx.compose.animation.AnimatedVisibility(canvasShown, Modifier.matchParentSize(), fadeIn(tween(400)), fadeOut(tween(250))) {
            videoPlayer?.let { canvasPlayer ->
                Box(Modifier.fillMaxSize()) {
                    PlaybackVideo(canvasPlayer, song.artworkUrl, playerAccent, Modifier.fillMaxSize(), cornerRadius = 0.dp, fill = true)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = .45f), .3f to Color.Transparent,
                        .55f to Color.Black.copy(alpha = .3f), 1f to Color.Black.copy(alpha = .88f))))
                }
            }
        }
        CanvasTint(canvasShown) {
        val moreMenu: @Composable () -> Unit = {
            var showMenu by remember { mutableStateOf(false) }
            Box {
                Icon(
                    Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d),
                    modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { showMenu = true }.padding(8.dp),
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
                    if (onOpenRelated != null && state.hasTrack) DropdownMenuItem(
                        text = { Text(appString(R.string.discovery_related)) },
                        onClick = { showMenu = false; onOpenRelated() },
                        leadingIcon = { Icon(Icons.Filled.Explore, null) },
                    )
                    if (state.hasTrack) com.aurora.music.ui.components.RepostMenuItem(song) { showMenu = false }
                    if (state.hasTrack) com.aurora.music.ui.components.MatchMenuItem(song) { showMenu = false }
                    if (state.supportsComments) DropdownMenuItem(
                        text = { Text(appString(R.string.player_comments)) },
                        onClick = { showMenu = false; showComments = true },
                        leadingIcon = { Icon(Icons.Filled.ChatBubble, null) },
                    )
                    if (state.commentsAvailable) DropdownMenuItem(
                        text = { Text(appString(R.string.player_soundcloud_comments)) },
                        onClick = { onToggleComments(!state.commentsEnabled) },
                        leadingIcon = { Icon(if (state.commentsEnabled) Icons.Filled.SpeakerNotes else Icons.Filled.SpeakerNotesOff, null) },
                        trailingIcon = if (state.commentsEnabled) ({ Icon(Icons.Filled.Check, null) }) else null,
                    )
                    MusicHapticsMenuItem()
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
                    if (PlayerPane.QUEUE in panes) DropdownMenuItem(
                        text = { Text(appString(R.string.text_view_queue_827a90)) },
                        onClick = {
                            showMenu = false
                            if (tablet) { sidePaneName = PlayerPane.QUEUE.name; portraitTabName = PlayerPane.QUEUE.name } else onOpenQueue()
                        },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    )
                }
            }
        }
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
                        .paperClip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val showVideoModeControl = (state.hasTrack && !state.isMix && !song.isRadio() && !song.isPodcast() &&
                        (state.supportsMusicVideo || state.supportsCanvas)) || state.hasVideo
                    val headerTint = LocalContentColor.current
                    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                    val videoToggle: @Composable () -> Unit = {
                        // tap is the youtube music video, long press the service's canvas
                        Box(
                            modifier = Modifier.size(40.dp).paperClip(CircleShape).combinedClickable(
                                role = Role.Button,
                                onClickLabel = appString(if (showVideo) R.string.text_audio_acdac2 else R.string.text_video_bc17c1),
                                onLongClickLabel = if (state.supportsCanvas) appString(R.string.canvas_open) else null,
                                onLongClick = if (!state.supportsCanvas) null else ({
                                    if (!state.videoLoading) {
                                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        fullscreenVideo = false
                                        showVideo = false
                                        showLyrics = false
                                        requestedVideo = true
                                        onRequestVideo(true)
                                    }
                                }),
                                onClick = {
                                    if (showVideo) {
                                        fullscreenVideo = false
                                        showVideo = false
                                    } else if (state.hasVideo && videoPlayer != null && !state.videoIsCanvas && state.supportsMusicVideo) {
                                        showLyrics = false
                                        showVideo = true
                                        inlineVideoControlsVisible = false
                                    } else if (!state.videoLoading && (state.supportsMusicVideo || state.supportsCanvas)) {
                                        // with music videos switched off the tap brings the canvas instead
                                        requestedVideo = true
                                        onRequestVideo(!state.supportsMusicVideo)
                                    }
                                },
                            ).semantics {
                                contentDescription = appString(
                                    if (showVideo) R.string.text_audio_acdac2 else R.string.text_video_bc17c1,
                                )
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.videoLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp),
                                color = headerTint, strokeWidth = 2.dp)
                            else Icon(if (showVideo) Icons.Filled.MusicNote else Icons.Filled.VideoLibrary,
                                contentDescription = null, tint = headerTint, modifier = Modifier.size(22.dp))
                        }
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188),
                        modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onCollapse).padding(6.dp),
                    )
                    if (tablet) {
                        Spacer(Modifier.width((if (showVideoModeControl) 120.dp else 80.dp) + if (djControl != null) 40.dp else 0.dp))
                    } else if (djControl != null) {
                        if (showVideoModeControl) videoToggle() else Spacer(Modifier.width(40.dp))
                        djControl()
                    } else if (showVideoModeControl) {
                        Spacer(Modifier.width(20.dp))
                        videoToggle()
                        Spacer(Modifier.width(20.dp))
                    } else {
                        Spacer(Modifier.width(80.dp))
                    }
                    // balances trailing icons so PLAYING FROM stays centered
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(appString(R.string.text_playing_from_5f4dc3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                        MarqueeTitle(song.album.ifBlank { appString(R.string.text_aurora_eeee9b) })
                    }
                    if (tablet && showVideoModeControl) videoToggle()
                    if (tablet) djControl?.invoke()
                    // cast route picker tvs/chromecast show here not in the local-output sheet
                    PlayerCastButton(Modifier.size(40.dp))
                    Icon(
                        Icons.Filled.Speaker, appString(R.string.text_output_device_709178),
                        modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onOpenOutput).padding(8.dp),
                    )
                    moreMenu()
                }
            }

            Spacer(Modifier.height(8.dp))

        }
        val coverArt: @Composable (Modifier) -> Unit = { modifier ->
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
                    .pointerInput(gestures.doubleTapPause) {
                        detectTapGestures(
                            onDoubleTap = if (gestures.doubleTapPause) ({ onTogglePlay() }) else null,
                            onTap = { if (!showVideo) showCover = true },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val artSide = minOf(maxWidth, maxHeight) * ui.playerArtSize.coerceIn(0.5f, 1f)
                val videoWidth = minOf(maxWidth, maxHeight * 16f / 9f)
                AnimatedContent(
                    targetState = showVideo,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "artVsVideo",
                ) { video ->
                    if (video && state.hasVideo && videoPlayer != null && state.videoIsCanvas) {
                        // the canvas fills the whole player behind this slot
                        Spacer(Modifier.size(artSide))
                    } else if (video && state.hasVideo && videoPlayer != null) {
                        Box(Modifier.width(videoWidth).aspectRatio(16f / 9f)) {
                            PlaybackVideo(videoPlayer, song.artworkUrl, playerAccent, Modifier.fillMaxSize())
                            Box(
                                Modifier.fillMaxSize().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        inlineVideoControlsVisible = !inlineVideoControlsVisible
                                        if (inlineVideoControlsVisible) inlineVideoInteraction++
                                    },
                                ),
                            )
                            AnimatedVisibility(
                                visible = inlineVideoControlsVisible,
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(420)),
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (state.canSelectVideoQuality) VideoQualityControl(
                                        state.videoQualityHeight,
                                        onVideoQualityChange,
                                        onInteraction = { inlineVideoInteraction++ },
                                    )
                                    IconButton(
                                        onClick = { fullscreenVideo = true },
                                        modifier = Modifier.size(44.dp).paperClip(CircleShape)
                                            .background(Color.Black.copy(alpha = .5f))
                                            .semantics { contentDescription = appString(R.string.video_fullscreen_enter) },
                                    ) { Icon(Icons.Filled.Fullscreen, null, tint = Color.White) }
                                }
                            }
                        }
                    } else {
                        val artModifier = Modifier.size(artSide)
                        val cover = CoverKey(song.album, song.artist, song.path)
                        if (classic) {
                            Artwork(song.artworkUrl, song.accent, artModifier, corner = 20.dp, animated = cover)
                        } else if (glassStyle) {
                            Artwork(song.artworkUrl, song.accent, artModifier.shadow(30.dp, RoundedCornerShape(16.dp),
                                ambientColor = Color.Black.copy(alpha = .5f), spotColor = Color.Black.copy(alpha = .5f)), corner = 16.dp, animated = cover)
                        } else {
                            Box(artModifier.auroraPanel(MaterialTheme.shapes.large, emphasized = true).padding(6.dp)) {
                                Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize(), corner = 20.dp, animated = cover)
                            }
                        }
                    }
                }
            }
        }
        val artwork: @Composable (Modifier) -> Unit = { modifier ->
            if (djStage != null) djStage(modifier) else coverArt(modifier)
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
                        isPlaying = !covered.value,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    MarqueeTitle(song.title, MaterialTheme.typography.headlineMedium, fontWeight = if (classic) FontWeight.Bold else MaterialTheme.typography.headlineMedium.fontWeight,
                        onClickLabel = appString(R.string.text_go_to_album_e2d3b3), onClick = openAlbum)
                    Spacer(Modifier.height(2.dp))
                    MarqueeTitle(song.artist, MaterialTheme.typography.titleMedium, MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = null,
                        onClickLabel = appString(R.string.text_go_to_artist_d8f70c), onClick = openArtist)
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
                if (com.aurora.music.ui.components.LocalCanLike.current(song.id)) {
                    com.aurora.music.ui.components.LikeHeart(song, onToggleLike, Modifier.size(48.dp).paperClip(CircleShape)) {
                        Icon(
                            imageVector = if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = appString(R.string.text_like_c7e02c),
                            tint = likeTint,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }

            // youtube music and spotify carry no file facts, so the decoder's view of the stream fills in
            val streamFormat by (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.aurora.music.AuroraApplication)
                .container.streamFormat.collectAsStateWithLifecycle()
            val badge = formatBadge(song).ifEmpty {
                streamFormat?.takeIf { it.mediaId == song.id }?.let { formatBadge(song.copy(suffix = codecLabel(it.codec),
                    sampleRateHz = it.sampleRateHz, bitDepth = it.bitDepth, bitrateKbps = it.bitrateKbps)) }.orEmpty()
            }
            val source = sourceLabel(song)
            if (!song.isDjSpeech()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .paperClip(RoundedCornerShape(12.dp))
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
                            if (classic) Modifier.paperClip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
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
                            if (classic) Modifier.paperClip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            else Modifier.auroraPanel(MaterialTheme.shapes.extraSmall)
                        ).padding(horizontal = 10.dp, vertical = 3.dp),
                    ) { Text(badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                }
            }

            Spacer(Modifier.height(8.dp))

            SeekBar(
                durationSec = state.durationSec,
                isLive = state.isLive,
                accent = playerAccent,
                seed = song.id.hashCode(),
                seekStyle = ui.playerSeekStyle,
                waveBars = ui.playerWaveBars,
                onSeek = onSeek,
                comments = state.comments,
                onOpenComment = { focusComment = it; showComments = true },
            )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth().then(
                    if (classic) Modifier
                    else if (glassStyle) Modifier.liquidGlass(glassBackdrop, com.kyant.shapes.Capsule(), refraction = 20.dp).padding(horizontal = 8.dp, vertical = 8.dp)
                    else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true)
                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.skipButtons) Icon(
                    Icons.Filled.Replay10, appString(R.string.skip_back_seconds, com.aurora.music.data.LongForm.SKIP_SEC),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable { skipBy(-com.aurora.music.data.LongForm.SKIP_SEC) }.padding(8.dp),
                ) else if (djControl != null) Spacer(Modifier.size(44.dp)) else Icon(
                    // a second tap on shuffle mixes autoplay picks in, and the button wears autoplay's sign
                    if (state.isMix) Icons.Filled.GraphicEq else if (state.smartShuffle) Icons.Filled.AllInclusive else Icons.Filled.Shuffle,
                    if (state.isMix) appString(R.string.text_edit_mix_transitions_76ee01)
                    else if (state.smartShuffle) appString(R.string.shuffle_autoplay) else appString(R.string.text_shuffle_5b772b),
                    tint = if (state.shuffle || state.isMix) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable(onClick = if (state.isMix) onOpenMix else onToggleShuffle).padding(8.dp),
                )
                Icon(
                    Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(56.dp).paperClip(CircleShape).clickable(onClick = onPrevious).padding(6.dp),
                )
                if (glassStyle) com.aurora.music.ui.components.glass.GlassButton(
                    onClick = onTogglePlay, backdrop = glassBackdrop, modifier = Modifier.size(68.dp), tint = playerAccent,
                    contentDescription = appString(R.string.text_play_pause_14a1d0),
                ) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = onPlayerAccent, modifier = Modifier.size(36.dp))
                } else Box(
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
                    modifier = Modifier.size(56.dp).paperClip(CircleShape).clickable(onClick = onNext).padding(6.dp),
                )
                if (state.skipButtons) Icon(
                    Icons.Filled.Forward10, appString(R.string.skip_forward_seconds, com.aurora.music.data.LongForm.SKIP_SEC),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable { skipBy(com.aurora.music.data.LongForm.SKIP_SEC) }.padding(8.dp),
                ) else if (djControl != null) Spacer(Modifier.size(44.dp)) else Icon(
                    imageVector = if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = appString(R.string.text_repeat_659eba),
                    tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable(onClick = onCycleRepeat).padding(8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (ui.playerShowUtilities) {
                val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
                val reverbStore = remember { (appContext as com.aurora.music.AuroraApplication).container.settingsStore }
                val reverbOn by remember(reverbStore) { reverbStore.reverbPrefs.map { it.enabled } }.collectAsStateWithLifecycle(initialValue = false)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = if (tablet) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BottomUtil(
                        Icons.Filled.Speed, appString(R.string.text_speed_x_72da98, ("%.1f".format(state.speed))), onOpenSpeedPitch,
                        active = kotlin.math.abs(state.speed - 1f) > 0.001f || reverbOn ||
                            (!state.matchPitch && kotlin.math.abs(state.pitch) > 0.001f),
                    )
                    if (tablet) {
                        BottomUtil(Icons.Filled.Bedtime, appString(R.string.text_sleep_timer_e90613), onOpenSleep,
                            active = state.sleepTimerMinutes > 0 || state.sleepEndOfTrack)
                    } else {
                        BottomUtil(
                            Icons.Filled.Lyrics,
                            appString(R.string.text_lyrics_8670cb),
                            { showLyrics = !showLyrics },
                            active = showLyrics,
                        )
                        if (PlayerPane.QUEUE in panes) BottomUtil(Icons.AutoMirrored.Filled.QueueMusic, appString(R.string.text_queue_d325fc), onOpenQueue)
                    }
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
        } else if (tablet && paneContent != null) {
            val paneCard: @Composable (PlayerPane, Modifier) -> Unit = { selected, modifier ->
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "playerPane",
                    modifier = modifier,
                ) { target -> Box(Modifier.fillMaxSize()) { paneContent(target, Modifier.fillMaxSize()) } }
            }
            val paneSurface = if (classic) Modifier.paperClip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge)
            val paneHeaderActions: @Composable (PlayerPane) -> Unit = { pane ->
                if (pane == PlayerPane.LYRICS) {
                    IconButton(onClick = { showVideo = false; showLyrics = true }) {
                        Icon(Icons.Filled.OpenInFull, appString(R.string.tablet_fullscreen_lyrics),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                } else paneActions?.invoke(pane)
            }
            val spacing = ui.tabletPanelSpacing.dp.coerceAtLeast(4.dp)
            if (landscape) {
                var split by remember(ui.tabletPlayerSplit) { mutableFloatStateOf(ui.tabletPlayerSplit) }
                val splitRange = com.aurora.music.data.TabletSetting.PLAYER_SPLIT.range
                Column(
                    Modifier.align(Alignment.TopCenter).then(underOverlay).widthIn(max = 1440.dp).fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility).padding(horizontal = 32.dp),
                ) {
                    header()
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(bottom = 24.dp)) {
                        val totalPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
                        Row(Modifier.fillMaxSize()) {
                            BoxWithConstraints(Modifier.weight(split).fillMaxHeight()) {
                                val artSide = (maxHeight - 340.dp).coerceIn(160.dp, 460.dp).coerceAtMost(maxWidth)
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                                    artwork(Modifier.fillMaxWidth().height(artSide))
                                    Spacer(Modifier.height(20.dp))
                                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) { controls() }
                                }
                            }
                            PaneDivider(
                                onDrag = { dx -> if (totalPx > 0f) split = (split + dx / totalPx).coerceIn(splitRange) },
                                onDragEnd = { onSplitChange(split) },
                            )
                            Column(Modifier.weight(1f - split).fillMaxHeight().then(paneSurface).padding(spacing)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    PaneSwitcher(sidePane, { sidePaneName = it.name })
                                    Spacer(Modifier.weight(1f))
                                    paneHeaderActions(sidePane)
                                }
                                Spacer(Modifier.height(spacing))
                                paneCard(sidePane, Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.TopCenter).then(underOverlay).widthIn(max = 720.dp).fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility).padding(horizontal = 32.dp),
                ) {
                    header()
                    Box(Modifier.fillMaxWidth()) {
                        PlayerTabs(portraitTab, { portraitTabName = it?.name }, Modifier.align(Alignment.Center))
                        if (portraitTab != null) Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                            paneHeaderActions(portraitTab)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (portraitTab == null) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            artwork(Modifier.sizeIn(maxWidth = 600.dp, maxHeight = 600.dp).fillMaxSize())
                        }
                        Spacer(Modifier.height(16.dp))
                        controls()
                    } else {
                        paneCard(portraitTab, Modifier.weight(1f).fillMaxWidth().then(paneSurface).padding(spacing))
                        Spacer(Modifier.height(16.dp))
                        CompactTransport(state, playerAccent, onPlayerAccent, onPrevious, onTogglePlay, onNext, onSeek,
                            ui.playerSeekStyle, ui.playerWaveBars, openAlbum, openArtist)
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
            LyricsOverlay(overlay, lyricsOffset, landscape, state, onClose = { showLyrics = false }, onTogglePlay, onPrevious, onNext, onSeek)
        } else {
            Column(
                Modifier.align(Alignment.TopCenter).then(underOverlay).widthIn(max = if (landscape) 1280.dp else 640.dp)
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
            LyricsOverlay(overlay, lyricsOffset, false, state, onClose = { showLyrics = false }, onTogglePlay, onPrevious, onNext, onSeek)
        }
        CoverOverlay(overlay, coverOffset, state, onClose = { showCover = false }, onTogglePlay, onPrevious, onNext, onSeek, onToggleLike, moreMenu,
            openAlbum, openArtist)
        }
    }
    }
    }
}

// over a canvas the player reads white like spotify's, whatever the app theme
@Composable
private fun CanvasTint(active: Boolean, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = if (!active) base else base.copy(onSurface = Color.White, onBackground = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = .78f))
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides if (active) Color.White else LocalContentColor.current, content = content)
    }
}

@Composable
private fun LyricsOverlay(
    overlay: androidx.compose.animation.core.Transition<Int>,
    offset: androidx.compose.runtime.MutableFloatState,
    wide: Boolean,
    state: PlayerUiState,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val visible = overlay.targetState == 1
    overlay.AnimatedVisibility(
        visible = { it == 1 },
        enter = if (wide) fadeIn(tween(320)) + androidx.compose.animation.scaleIn(tween(380), initialScale = .96f)
            else fadeIn(tween(320)) + androidx.compose.animation.slideInVertically(tween(420)) { it / 10 },
        exit = if (wide) fadeOut(tween(240)) + androidx.compose.animation.scaleOut(tween(260), targetScale = .97f)
            else fadeOut(tween(220)) + androidx.compose.animation.slideOutVertically(tween(280)) { it / 12 },
    ) {
        DragToDismiss(onDismiss = onClose, enabled = !wide, shown = visible, offsetState = offset) {
            LyricsScreen(state, onClose = onClose, onTogglePlay, onPrevious, onNext, onSeek)
        }
    }
    androidx.activity.compose.BackHandler(enabled = visible) { onClose() }
}

// apple-music style full-art view: the cover fills the screen and melts into its own colours behind the controls
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverOverlay(
    overlay: androidx.compose.animation.core.Transition<Int>,
    offset: androidx.compose.runtime.MutableFloatState,
    state: PlayerUiState,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit,
    menu: @Composable () -> Unit,
    onOpenAlbum: (() -> Unit)?,
    onOpenArtist: (() -> Unit)?,
) {
    val visible = overlay.targetState == 2
    overlay.AnimatedVisibility(visible = { it == 2 }, enter = fadeIn(tween(320)), exit = fadeOut(tween(240))) {
        DragToDismiss(onDismiss = onClose, shown = visible, offsetState = offset) {
            Box(Modifier.fillMaxSize()) {
            CanvasTint(true) {
                val song = state.current
                val ui = LocalUiPrefs.current
                val noRipple = remember { MutableInteractionSource() }
                val context = androidx.compose.ui.platform.LocalContext.current
                // "remove animations" in the system settings keeps the cover still
                val motion = remember { android.provider.Settings.Global.getFloat(context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
                val settle = remember(song.id) { androidx.compose.animation.core.Animatable(if (motion) 1.12f else 1f) }
                LaunchedEffect(song.id) { settle.animateTo(1f, tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                val drift = coverDrift(state.isPlaying && motion)
                BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF17191D)).clickable(noRipple, indication = null) {}) {
                    LyricsBackdrop(song)
                    val wide = maxWidth > maxHeight
                    val side = if (wide) maxHeight else maxWidth
                    val fade = if (wide) Brush.horizontalGradient(.6f to Color.Black, 1f to Color.Transparent)
                        else Brush.verticalGradient(.6f to Color.Black, 1f to Color.Transparent)
                    Artwork(
                        song.artworkUrl, song.accent,
                        Modifier.size(side)
                            .clickable(noRipple, indication = null, onClick = onClose)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
                            .drawWithContent { drawContent(); drawRect(fade, blendMode = BlendMode.DstIn) }
                            .graphicsLayer { kenBurns(drift.floatValue, settle.value) },
                        corner = 0.dp, animated = CoverKey(song.album, song.artist, song.path),
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188),
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility).padding(12.dp)
                            .size(40.dp).paperClip(CircleShape).background(Color.Black.copy(alpha = .3f)).clickable(onClick = onClose).padding(6.dp),
                    )
                    Column(
                        Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter)
                            .then(if (wide) Modifier.width((maxWidth - side * .8f).coerceIn(320.dp, 520.dp)) else Modifier.fillMaxWidth())
                            .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
                            .padding(horizontal = 28.dp, vertical = 24.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                MarqueeTitle(song.title, MaterialTheme.typography.headlineSmall,
                                    onClickLabel = appString(R.string.text_go_to_album_e2d3b3), onClick = onOpenAlbum)
                                MarqueeTitle(song.artist, MaterialTheme.typography.titleMedium, MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = null,
                                    onClickLabel = appString(R.string.text_go_to_artist_d8f70c), onClick = onOpenArtist)
                            }
                            if (com.aurora.music.ui.components.LocalCanLike.current(song.id)) {
                                com.aurora.music.ui.components.LikeHeart(song, onToggleLike, Modifier.size(48.dp).paperClip(CircleShape)) {
                                    Icon(if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        appString(R.string.text_like_c7e02c), Modifier.size(28.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        SeekBar(state.durationSec, state.isLive, Color.White, song.id.hashCode(), ui.playerSeekStyle, ui.playerWaveBars, onSeek)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942),
                                modifier = Modifier.size(64.dp).paperClip(CircleShape).clickable(onClick = onPrevious).padding(8.dp))
                            Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, appString(R.string.text_play_pause_14a1d0),
                                modifier = Modifier.size(80.dp).paperClip(CircleShape).clickable(onClick = onTogglePlay).padding(8.dp))
                            Icon(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819),
                                modifier = Modifier.size(64.dp).paperClip(CircleShape).clickable(onClick = onNext).padding(8.dp))
                        }
                    }
                }
            }
            // outside the white tint so the menu keeps the app's own colours
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility).padding(12.dp)
                    .paperClip(CircleShape).background(Color.Black.copy(alpha = .3f))) { menu() }
            }
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = visible) { onClose() }
}

// playing time, stepped like the lyrics glow: the cover moves under a pixel per step so a dozen redraws a second look smooth
@Composable
private fun coverDrift(running: Boolean): androidx.compose.runtime.FloatState {
    val drift = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val from = drift.floatValue
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) {
            drift.floatValue = from + (androidx.compose.runtime.withFrameMillis { it } - start) / 1000f
            delay(80)
        }
    }
    return drift
}

// a slow ken burns: zooms up to 12% toward a wandering point, so the cover never shows an edge
private fun androidx.compose.ui.graphics.GraphicsLayerScope.kenBurns(seconds: Float, settle: Float) {
    val zoom = (1f - kotlin.math.cos(seconds * 2f * kotlin.math.PI.toFloat() / 40f)) / 2f
    scaleX = settle * (1f + .12f * zoom)
    scaleY = scaleX
    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f + .4f * kotlin.math.sin(seconds / 11f), .5f + .4f * kotlin.math.cos(seconds / 17f))
}

@Composable
private fun PaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (dragging) 0.5f else 0.16f, tween(160), label = "dividerAlpha")
    val length by androidx.compose.animation.core.animateDpAsState(if (dragging) 64.dp else 36.dp, tween(160), label = "dividerLength")
    val description = appString(R.string.tablet_resize_panels)
    Box(
        Modifier.width(24.dp).fillMaxHeight()
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; onDragEnd() },
                    onDragCancel = { dragging = false; onDragEnd() },
                ) { change, amount -> change.consume(); onDrag(amount) }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(length).paperClip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)))
    }
}

@Composable
private fun CompactTransport(
    state: PlayerUiState,
    accent: Color,
    onAccent: Color,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    seekStyle: Int = SeekStyle.BAR,
    waveBars: Int = 0,
    onOpenAlbum: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
) {
    val song = state.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(56.dp), corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MarqueeTitle(song.title, MaterialTheme.typography.titleMedium,
                    onClickLabel = appString(R.string.text_go_to_album_e2d3b3), onClick = onOpenAlbum)
                MarqueeTitle(song.artist, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = null,
                    onClickLabel = appString(R.string.text_go_to_artist_d8f70c), onClick = onOpenArtist)
            }
            if (!state.isMix) IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942), tint = MaterialTheme.colorScheme.onSurface)
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = onAccent),
            ) {
                Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    appString(R.string.text_play_pause_14a1d0), modifier = Modifier.size(30.dp))
            }
            if (!state.isMix) IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        SeekBar(
            durationSec = state.durationSec,
            isLive = state.isLive,
            accent = accent,
            seed = song.id.hashCode(),
            seekStyle = seekStyle,
            waveBars = waveBars,
            onSeek = onSeek,
        )
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
        Modifier.paperClip(CircleShape).background(container)
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
private fun VideoQualityControl(
    qualityHeight: Int?,
    onQualityChange: (Int?) -> Unit,
    onInteraction: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val label = qualityHeight?.let { "${it}p" } ?: appString(R.string.video_quality_auto)
    Box {
        FilledTonalButton(
            onClick = { onInteraction(); expanded = true },
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
            listOf(null, 360, 480, 720, 1080, 1440, 2160, 4320).forEach { height ->
                val selected = height == qualityHeight
                DropdownMenuItem(
                    text = { Text(height?.let { "${it}p" } ?: appString(R.string.video_quality_auto)) },
                    leadingIcon = { if (selected) Icon(Icons.Filled.Check, null) },
                    onClick = { expanded = false; onInteraction(); onQualityChange(height) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
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
    var controlsVisible by remember { mutableStateOf(true) }
    var seeking by remember { mutableStateOf(false) }
    var interactionVersion by remember { mutableStateOf(0) }
    val registerInteraction: () -> Unit = {
        controlsVisible = true
        interactionVersion++
    }

    LaunchedEffect(controlsVisible, seeking, interactionVersion) {
        if (!controlsVisible || seeking) return@LaunchedEffect
        delay(3_000)
        controlsVisible = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        PlaybackVideo(player, artworkUrl, accent, Modifier.fillMaxSize(), cornerRadius = 0.dp)
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (controlsVisible) {
                        controlsVisible = false
                    } else {
                        registerInteraction()
                    }
                },
            ),
        )
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(420)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.paperClip(CircleShape).background(Color.Black.copy(alpha = .48f)).padding(4.dp)) {
                    PlayerVideoModeButton(appString(R.string.text_audio_acdac2), Icons.Filled.MusicNote, false) {
                        registerInteraction()
                        onAudio()
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.current.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.current.artist, color = Color.White.copy(alpha = .72f),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (canSelectQuality) VideoQualityControl(
                    qualityHeight,
                    onQualityChange = { registerInteraction(); onVideoQualityChange(it) },
                    onInteraction = registerInteraction,
                )
                IconButton(
                    onClick = { registerInteraction(); onExitFullscreen() },
                    modifier = Modifier.padding(start = 4.dp).size(44.dp).paperClip(CircleShape)
                        .background(Color.Black.copy(alpha = .48f))
                        .semantics { contentDescription = appString(R.string.video_fullscreen_exit) },
                ) { Icon(Icons.Filled.FullscreenExit, null, tint = Color.White) }
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(420)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f))))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                SeekBar(
                    durationSec = state.durationSec,
                    isLive = state.isLive,
                    accent = Color.White,
                    seed = state.current.id.hashCode(),
                    seekStyle = SeekStyle.BAR,
                    waveBars = 0,
                    onSeek = { registerInteraction(); onSeek(it) },
                    onInteraction = registerInteraction,
                    onSeekingChanged = { seeking = it },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { registerInteraction(); onPrevious() }) {
                        Icon(Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942), tint = Color.White)
                    }
                    IconButton(
                        onClick = { registerInteraction(); onTogglePlay() },
                        modifier = Modifier.padding(horizontal = 18.dp).size(54.dp).paperClip(CircleShape).background(accent),
                    ) {
                        Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            appString(R.string.text_play_pause_14a1d0), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = { registerInteraction(); onNext() }) {
                        Icon(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819), tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarqueeTitle(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight? = FontWeight.Bold,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    var fullWidth by remember(text) { androidx.compose.runtime.mutableIntStateOf(0) }
    var shownWidth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val overflowing = fullWidth > shownWidth && shownWidth > 0
    Text(
        text, style = style, fontWeight = fontWeight,
        maxLines = 1, softWrap = false, color = color,
        onTextLayout = { fullWidth = it.size.width },
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .onSizeChanged { shownWidth = it.width }
            .then(if (overflowing) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn)
            } else Modifier)
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2500),
    )
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

private fun codecLabel(codec: String): String = when (codec) {
    "MP4A-LATM", "MP4A" -> "AAC"
    "MPEG" -> "MP3"
    "RAW" -> "PCM"
    else -> codec
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
            if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) Modifier.paperClip(RoundedCornerShape(50))
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
private fun SeekBar(
    durationSec: Int,
    isLive: Boolean,
    accent: Color,
    seed: Int,
    seekStyle: Int,
    waveBars: Int,
    onSeek: (Float) -> Unit,
    onInteraction: () -> Unit = {},
    onSeekingChanged: (Boolean) -> Unit = {},
    comments: List<com.aurora.music.model.TrackComment> = emptyList(),
    onOpenComment: (String) -> Unit = {},
) {
    val position = com.aurora.music.ui.components.LocalPlaybackPosition.current.value
    val positionSec = position.toInt()
    val progress = if (durationSec == 0) 0f else (position / durationSec).coerceIn(0f, 1f)
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val currentOnInteraction by rememberUpdatedState(onInteraction)
    val currentOnSeekingChanged by rememberUpdatedState(onSeekingChanged)
    LaunchedEffect(sliderInteractionSource) {
        sliderInteractionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    currentOnInteraction()
                    currentOnSeekingChanged(true)
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    currentOnInteraction()
                    currentOnSeekingChanged(false)
                }
            }
        }
    }
    Column {
        if (durationSec <= 0) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLive) Box(Modifier.size(8.dp).paperClip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(if (isLive) appString(R.string.text_live_6990f0) else appString(R.string.text_streaming_1e8325), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = accent)
                Spacer(Modifier.weight(1f))
                Text(formatTime(positionSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        CommentTrack(comments, durationSec, position, accent, onOpenComment)
        if (seekStyle == SeekStyle.BAR) {
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = { onInteraction(); onSeek(it) },
                onValueChangeFinished = onInteraction,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                interactionSource = sliderInteractionSource,
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

// apple-music style: the cover itself, blurred into a soft wash the glass controls can bend
@Composable
private fun GlassArtBackdrop(song: com.aurora.music.model.Song, modifier: Modifier) {
    val dark = com.aurora.music.ui.theme.glassIsDark()
    val base = MaterialTheme.colorScheme.background
    Box(modifier.background(base).clipToBounds()) {
        Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }
            .blur(110.dp, BlurredEdgeTreatment.Rectangle), corner = 0.dp, decodeSize = 128)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            if (dark) listOf(Color.Black.copy(alpha = .12f), Color.Black.copy(alpha = .3f), Color.Black.copy(alpha = .5f))
            else listOf(Color.White.copy(alpha = .1f), Color.White.copy(alpha = .3f), Color.White.copy(alpha = .55f)))))
    }
}

// soundcloud-style: the comment just passed pops up, and every commenter sits along the timeline
@Composable
private fun CommentTrack(comments: List<com.aurora.music.model.TrackComment>, durationSec: Int, positionSec: Float, accent: Color,
    onOpen: (String) -> Unit) {
    if (comments.isEmpty() || durationSec <= 0) return
    val passing = comments.lastOrNull { it.timestampMs / 1000f in (positionSec - 4f)..positionSec }
    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.BottomStart) {
        androidx.compose.animation.Crossfade(passing, label = "comment") { comment ->
            if (comment != null) Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { onOpen(comment.id) }.padding(bottom = 2.dp, end = 8.dp)) {
                Artwork(comment.avatarUrl, accent, Modifier.size(24.dp), corner = 12.dp, decodeSize = 64)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(comment.user, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(comment.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    val current by rememberUpdatedState(comments)
    BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp).pointerInput(durationSec) {
        // the avatars are small, so a tap anywhere on the strip opens the comment nearest to it
        detectTapGestures { tap ->
            val atMs = tap.x / size.width * durationSec * 1000f
            current.minByOrNull { kotlin.math.abs(it.timestampMs - atMs) }?.let { onOpen(it.id) }
        }
    }, contentAlignment = Alignment.CenterStart) {
        // one avatar per small slice of the timeline, so a busy track stays readable
        val slices = (maxWidth / 14.dp).toInt().coerceAtLeast(1)
        val shown = remember(comments, slices, durationSec) {
            comments.distinctBy { (it.timestampMs / 1000f / durationSec * slices).toInt().coerceIn(0, slices - 1) }
        }
        shown.forEach { comment ->
            val at = (comment.timestampMs / 1000f / durationSec).coerceIn(0f, 1f)
            Artwork(comment.avatarUrl, accent, Modifier.offset(x = (maxWidth - 14.dp) * at).size(14.dp), corner = 7.dp, decodeSize = 48)
        }
    }
}

// a fixed height keeps the sheet from resizing while rows load and scroll
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(state: PlayerUiState, focusId: String?, onDismiss: () -> Unit, onSeek: (Float) -> Unit, onPost: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val positionSec = com.aurora.music.ui.components.LocalPlaybackPosition.current.value
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(focusId, state.comments.size) {
        val index = state.comments.indexOfFirst { it.id == focusId }
        if (index >= 0) list.scrollToItem(index)
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 16.dp).imePadding()) {
            Text(appString(R.string.player_comments), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                if (state.comments.isEmpty()) item {
                    Text(appString(R.string.comments_empty), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
                items(state.comments.size, key = { state.comments[it].id }) { index ->
                    val comment = state.comments[index]
                    val focused = comment.id == focusId
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                        .clickable { onSeek(state.progressAt(comment.timestampMs / 1000f)) }.padding(8.dp)) {
                        Artwork(comment.avatarUrl, state.current.accent, Modifier.size(32.dp), corner = 16.dp, decodeSize = 96)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(comment.user, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(6.dp))
                                Text(formatTime((comment.timestampMs / 1000).toInt()), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text(comment.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (state.canPostComments) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(appString(R.string.comment_hint, formatTime(positionSec.toInt()))) })
                Spacer(Modifier.width(8.dp))
                com.aurora.music.ui.theme.TextButton(onClick = { onPost(draft); draft = "" }, enabled = draft.isNotBlank()) { Text(appString(R.string.comment_post)) }
            }
        }
    }
}
