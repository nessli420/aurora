package com.aurora.music.ui.screens.player

import com.aurora.music.data.LongForm
import com.aurora.music.ui.theme.paperClip
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.SpeakerNotes
import androidx.compose.material.icons.filled.SpeakerNotesOff
import androidx.compose.material.icons.filled.Lyrics
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.ColorScheme
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import com.aurora.music.data.GesturePrefs
import com.aurora.music.data.SeekStyle
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.player.RepeatMode
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.desktop.ui.LocalWindowShown
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.HoverTooltip
import com.aurora.music.ui.components.CoverKey
import com.aurora.music.ui.components.PaneDivider
import com.aurora.music.ui.components.VolumeControl
import com.aurora.music.ui.components.Waveform
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.theme.LocalGlass
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraBackdrop
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.glassEdge
import com.aurora.music.ui.theme.glassSheen
import com.aurora.music.model.accent
import com.aurora.music.ui.theme.themedCorner
import com.aurora.music.ui.theme.playerColorScheme
import com.aurora.music.ui.theme.readableAccent
import com.aurora.music.ui.theme.styleColorScheme
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onOpenMix: (() -> Unit)? = null,
    video: com.aurora.music.desktop.video.VideoPlayer? = null,
    onRequestVideo: (canvas: Boolean) -> Unit = {},
    onVideoVisibleChange: (Boolean) -> Unit = {},
    onVideoQualityChange: (Int?) -> Unit = {},
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
    djControl: (@Composable () -> Unit)? = null,
    djStage: (@Composable (Modifier) -> Unit)? = null,
    paneContent: @Composable (PlayerPane, Modifier) -> Unit,
    gestures: GesturePrefs = GesturePrefs(),
    requestedPane: PlayerPane? = null,
    onPaneRequestHandled: () -> Unit = {},
    paneActions: (@Composable (PlayerPane) -> Unit)? = null,
    onSplitChange: (Float) -> Unit = {},
    onImmersiveLyrics: (Boolean) -> Unit = {},
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
    volume: Float = LocalPlayer.current.volume.collectAsState().value,
    onVolumeChange: (Float) -> Unit = LocalPlayer.current::setVolume,
    onToggleMute: () -> Unit = LocalPlayer.current::toggleMute,
) {
    val song = state.current
    val openAlbum = onGoToAlbum.takeIf { song.albumId.isNotBlank() }
    val openArtist = onGoToArtist.takeIf { song.artistId.isNotBlank() }
    val ui = LocalUiPrefs.current
    val classic = ui.themeStyle == ThemeStyle.AURORA
    val skipBy = { seconds: Int ->
        if (state.durationSec > 0) onSeek(((state.positionSec + seconds) / state.durationSec).coerceIn(0f, 1f))
    }
    var showLyrics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showCover by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var pickedPane by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var paneOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val sidePane = pickedPane?.let(PlayerPane::valueOf)?.takeIf { it in LocalPlayerPanes.current } ?: PlayerPane.LYRICS
    LaunchedEffect(requestedPane) {
        if (requestedPane == null) return@LaunchedEffect
        pickedPane = requestedPane.name
        paneOpen = true
        onPaneRequestHandled()
    }
    val comments = rememberTrackComments(song)
    var showComments by remember { mutableStateOf(false) }
    var focusComment by remember { mutableStateOf<String?>(null) }

    var showVideo by androidx.compose.runtime.saveable.rememberSaveable(song.id) { mutableStateOf(false) }
    var requestedCanvas by remember(song.id) { mutableStateOf<Boolean?>(null) }
    var fullscreenVideo by androidx.compose.runtime.saveable.rememberSaveable(song.id) { mutableStateOf(false) }
    val fullscreenActive = fullscreenVideo && showVideo && video != null && state.hasVideo && !state.videoIsCanvas
    LaunchedEffect(state.hasVideo, state.videoIsCanvas, requestedCanvas) {
        if (requestedCanvas != null && state.hasVideo && state.videoIsCanvas == requestedCanvas) { requestedCanvas = null; showVideo = true }
    }
    val canVideo = state.hasTrack && !state.isMix && (state.supportsMusicVideo || state.supportsCanvas || state.hasVideo)
    fun openVideo(canvas: Boolean) {
        when {
            state.hasVideo && state.videoIsCanvas == canvas -> showVideo = true
            canvas && !state.supportsCanvas -> onRequestVideo(true)
            else -> { showVideo = false; requestedCanvas = canvas; onRequestVideo(canvas) }
        }
    }
    fun togglePane(pane: PlayerPane) {
        if (paneOpen && sidePane == pane) paneOpen = false else { pickedPane = pane.name; paneOpen = true }
    }
    val canvasPlayer = video?.takeIf { showVideo && state.hasVideo && state.videoIsCanvas }
    val stageBounds = remember { mutableStateOf(Rect.Zero) }
    val windowShown = LocalWindowShown.current
    DisposableEffect(showVideo, windowShown, showLyrics) {
        val visible = showVideo && windowShown && !showLyrics
        onVideoVisibleChange(visible)
        onDispose { if (visible) onVideoVisibleChange(false) }
    }
    MaterialTheme(colorScheme = if (canvasPlayer != null) canvasColors() else MaterialTheme.colorScheme) {
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
        val moreMenu: @Composable () -> Unit = {
            var showMenu by remember { mutableStateOf(false) }
            Box {
                Icon(
                    Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d),
                    modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { showMenu = true }.pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                )
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (onOpenMix != null) DropdownMenuItem(text = { Text(appString(R.string.text_mix_studio_668363)) },
                        onClick = { showMenu = false; onOpenMix() }, leadingIcon = { Icon(Icons.Filled.GraphicEq, null) })
                    if (state.hasTrack && state.supportsCanvas && canvasPlayer == null) DropdownMenuItem(
                        text = { Text(appString(R.string.canvas_open)) },
                        enabled = !state.videoLoading,
                        onClick = { showMenu = false; openVideo(canvas = true) },
                        leadingIcon = { Icon(Icons.Filled.Animation, null) },
                    )
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
                    if (state.hasTrack) com.aurora.music.ui.components.RepostMenuItem(song) { showMenu = false }
                    if (comments.supported) DropdownMenuItem(
                        text = { Text(appString(R.string.player_comments)) },
                        onClick = { showMenu = false; focusComment = null; showComments = true },
                        leadingIcon = { Icon(Icons.Filled.ChatBubble, null) },
                    )
                    if (comments.available) DropdownMenuItem(
                        text = { Text(appString(R.string.player_soundcloud_comments)) },
                        onClick = { comments.setEnabled(!comments.enabled) },
                        leadingIcon = { Icon(if (comments.enabled) Icons.Filled.SpeakerNotes else Icons.Filled.SpeakerNotesOff, null) },
                        trailingIcon = if (comments.enabled) ({ Icon(Icons.Filled.Check, null) }) else null,
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
                }
            }
        }
        val header: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = if (short) 8.dp else 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188),
                    modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onCollapse).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
                )
                // balances trailing icons so PLAYING FROM stays centered
                Spacer(Modifier.width(if (canVideo) 200.dp else 160.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(appString(R.string.text_playing_from_5f4dc3), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                    MarqueeTitle(song.album.ifBlank { appString(R.string.text_aurora_eeee9b) })
                }
                HeaderButton(Icons.Filled.Lyrics, appString(R.string.text_lyrics_8670cb), active = paneOpen && sidePane == PlayerPane.LYRICS) { togglePane(PlayerPane.LYRICS) }
                if (djControl == null) HeaderButton(Icons.AutoMirrored.Filled.QueueMusic, appString(R.string.text_queue_d325fc), active = paneOpen && sidePane != PlayerPane.LYRICS) { togglePane(PlayerPane.QUEUE) }
                if (canVideo) {
                    val videoButton = @Composable {
                        HeaderButton(Icons.Filled.VideoLibrary, appString(if (showVideo) R.string.text_audio_acdac2 else R.string.text_video_bc17c1),
                            active = showVideo, enabled = !state.videoLoading,
                            onDoubleClick = { if (canvasPlayer != null) showVideo = false else openVideo(canvas = true) },
                        ) { if (showVideo) showVideo = false else openVideo(canvas = !state.supportsMusicVideo) }
                    }
                    if (state.supportsCanvas) TooltipArea(tooltip = { HoverTooltip(appString(R.string.canvas_double_click)) }, delayMillis = 600) { videoButton() }
                    else videoButton()
                }
                djControl?.invoke()
                HeaderButton(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    appString(if (fullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen)) { onFullscreenChange(!fullscreen) }
                HeaderButton(Icons.Filled.Speaker, appString(R.string.text_output_device_709178), onClick = onOpenOutput)
                moreMenu()
            }
        }
        val coverArt: @Composable (Modifier) -> Unit = { modifier ->
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
                    .then(if (canvasPlayer != null) Modifier.onGloballyPositioned { stageBounds.value = it.boundsInWindow() } else Modifier)
                    .pointerInput(gestures.doubleTapPause) {
                        detectTapGestures(
                            onDoubleTap = if (gestures.doubleTapPause) ({ onTogglePlay() }) else null,
                            onTap = { if (!showVideo) showCover = true },
                        )
                    }
                    .then(if (!showVideo) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (showVideo && video != null && state.hasVideo) {
                    Box(Modifier.fillMaxSize()) {
                        VideoSurface(video, Modifier.fillMaxSize(), corner = 20.dp, fill = state.videoIsCanvas)
                        if (!state.videoIsCanvas) Row(Modifier.align(Alignment.BottomEnd).padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            VideoQualityControl(state.videoQualityHeight, onVideoQualityChange)
                            IconButton(onClick = { fullscreenVideo = true },
                                modifier = Modifier.size(36.dp).paperClip(CircleShape).background(Color.Black.copy(alpha = .5f)).pointerHoverIcon(PointerIcon.Hand)) {
                                Icon(Icons.Filled.Fullscreen, appString(R.string.video_fullscreen_enter), tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                } else if (classic) {
                    Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize(), corner = 20.dp, animated = CoverKey.of(song))
                } else {
                    Box(Modifier.fillMaxSize().auroraPanel(MaterialTheme.shapes.large, emphasized = true).padding(6.dp)) {
                        Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize(), corner = 20.dp, animated = CoverKey.of(song))
                    }
                }
            }
        }
        val artwork: @Composable (Modifier) -> Unit = { modifier ->
            if (djStage != null) djStage(modifier) else coverArt(modifier)
        }
        val controls: @Composable (Dp) -> Unit = { volumeWidth ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canvasPlayer != null) {
                    Artwork(song.artworkUrl, song.accent, Modifier.size(56.dp), corner = themedCorner(10.dp))
                    Spacer(Modifier.width(14.dp))
                } else if (state.isPlaying) {
                    com.aurora.music.ui.components.LottieEqualizer(
                        modifier = Modifier.size(28.dp)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(playerAccent, blendMode = BlendMode.SrcIn)
                            },
                        isPlaying = true,
                        // stepped by the position ticks, so the bars add no frames of their own
                        phase = state.positionSec / 2f % 1f,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    SongTitles(song, MaterialTheme.typography.headlineMedium, MaterialTheme.typography.titleMedium, openAlbum, openArtist,
                        titleWeight = if (classic) FontWeight.Bold else MaterialTheme.typography.headlineMedium.fontWeight, gap = 2.dp)
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

            val badge = formatBadge(song)
            val source = sourceLabel(song)
            if (!song.id.startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX)) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .paperClip(RoundedCornerShape(themedCorner(12.dp)))
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
                            if (classic) Modifier.paperClip(RoundedCornerShape(50)).background(if (LocalGlass.current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)).glassEdge(RoundedCornerShape(50))
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
                            if (classic) Modifier.paperClip(RoundedCornerShape(50)).background(if (LocalGlass.current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)).glassEdge(RoundedCornerShape(50))
                            else Modifier.auroraPanel(MaterialTheme.shapes.extraSmall)
                        ).padding(horizontal = 10.dp, vertical = 3.dp),
                    ) { Text(badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
                }
            }

            Spacer(Modifier.height(8.dp))

            CommentTrack(comments.list, state.durationSec, state.positionSec, playerAccent) { focusComment = it; showComments = true }
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
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth().then(
                    if (classic) Modifier else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true)
                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.skipButtons) Icon(
                    Icons.Filled.Replay10, appString(R.string.skip_back_seconds, LongForm.SKIP_SEC),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable { skipBy(-LongForm.SKIP_SEC) }.pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                ) else if (djControl != null) Spacer(Modifier.size(44.dp)) else Icon(
                    // a second tap on shuffle mixes autoplay picks in, and the button wears autoplay's sign
                    if (state.smartShuffle) Icons.Filled.AllInclusive else Icons.Filled.Shuffle,
                    appString(if (state.smartShuffle) R.string.shuffle_autoplay else R.string.text_shuffle_5b772b),
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable(onClick = onToggleShuffle).pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                )
                Icon(
                    Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(56.dp).paperClip(CircleShape).clickable(onClick = onPrevious).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
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
                    modifier = Modifier.size(56.dp).paperClip(CircleShape).clickable(onClick = onNext).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
                )
                if (state.skipButtons) Icon(
                    Icons.Filled.Forward10, appString(R.string.skip_forward_seconds, LongForm.SKIP_SEC),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable { skipBy(LongForm.SKIP_SEC) }.pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                ) else if (djControl != null) Spacer(Modifier.size(44.dp)) else Icon(
                    imageVector = if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = appString(R.string.text_repeat_659eba),
                    tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable(onClick = onCycleRepeat).pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.playerShowUtilities) {
                        BottomUtil(
                            Icons.Filled.Speed, appString(R.string.text_speed_x_72da98, ("%.1f".format(state.speed))), onOpenSpeedPitch,
                            active = kotlin.math.abs(state.speed - 1f) > 0.001f, modifier = Modifier.weight(1f, fill = false),
                        )
                        BottomUtil(Icons.Filled.Bedtime, appString(R.string.text_sleep_timer_e90613), onOpenSleep,
                            active = state.sleepTimerMinutes > 0 || state.sleepEndOfTrack, modifier = Modifier.weight(1f, fill = false))
                    }
                }
                VolumeControl(volume, onVolumeChange, onToggleMute, volumeWidth, Modifier.padding(start = 8.dp), fill = playerAccent)
            }
        }
        val paneSurface = if (classic && LocalGlass.current) Modifier.paperClip(RoundedCornerShape(themedCorner(28.dp))).background(glassSheen()).glassEdge(RoundedCornerShape(themedCorner(28.dp)))
            else if (classic) Modifier.paperClip(RoundedCornerShape(themedCorner(28.dp))).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge)
        val spacing = ui.tabletPanelSpacing.dp.coerceAtLeast(4.dp)
        var split by remember(ui.tabletPlayerSplit) { mutableFloatStateOf(ui.tabletPlayerSplit) }
        val splitRange = com.aurora.music.data.TabletSetting.PLAYER_SPLIT.range
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.animation.AnimatedVisibility(canvasPlayer != null, Modifier.matchParentSize(), enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
            (canvasPlayer ?: video)?.let { CanvasAmbient(it, stageBounds, Modifier.fillMaxSize()) }
        }
        Column(Modifier.fillMaxSize()) {
            header()
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(start = 32.dp, end = 32.dp, bottom = if (short) 16.dp else 24.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                BoxWithConstraints(Modifier.widthIn(max = BodyMaxWidth).fillMaxSize()) {
                    val total = maxWidth
                    Row(Modifier.fillMaxSize()) {
                        BoxWithConstraints(Modifier.weight(if (paneOpen) split else 1f).fillMaxHeight()) {
                            val gap = if (short) 12.dp else 20.dp
                            var controlsHeight by remember { mutableStateOf(0.dp) }
                            val reserve = if (controlsHeight > 0.dp) controlsHeight else ControlsEstimate
                            val artScale = ui.playerArtSize.coerceIn(0.5f, 1.2f) / DefaultArtSize
                            val fit = (maxHeight - reserve - gap).coerceAtMost(maxWidth - 32.dp)
                            val artSide = minOf(fit * artScale.coerceAtMost(1f), MaxArt * artScale).coerceAtLeast(MinArt).coerceAtMost(maxWidth)
                            val controlsWidth = artSide.coerceAtLeast(420.dp).coerceAtMost(maxWidth)
                            // a portrait canvas stands beside the controls; only a cramped column crops it into the artwork square
                            val stageGap = if (short) 28.dp else 48.dp
                            val stageControls = (maxWidth * 0.4f).coerceIn(400.dp, 520.dp)
                            val aspect = canvasPlayer?.let { it.width.toFloat() / it.height }?.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.5f, 1f) ?: (9f / 16f)
                            val stageHeight = minOf(maxHeight, (maxWidth - stageControls - stageGap) / aspect)
                            if (canvasPlayer != null && stageHeight * aspect >= MinCanvasWidth) Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(stageGap, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                artwork(Modifier.width(stageHeight * aspect).height(stageHeight).shadow(32.dp, RoundedCornerShape(20.dp)))
                                Column(Modifier.width(stageControls)) { controls((stageControls - 300.dp).coerceIn(104.dp, 160.dp)) }
                            } else Column(
                                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                artwork(Modifier.size(artSide))
                                Spacer(Modifier.height(gap))
                                Column(Modifier.width(controlsWidth).onSizeChanged { controlsHeight = with(density) { it.height.toDp() } }) {
                                    controls((controlsWidth - 300.dp).coerceIn(104.dp, 160.dp))
                                }
                            }
                        }
                        if (paneOpen) PaneDivider(
                            onDrag = { dx -> if (total > 0.dp) split = (split + dx / total).coerceIn(splitRange) },
                            onDragEnd = { onSplitChange(split) },
                            width = 24.dp,
                        )
                        if (paneOpen) Column(Modifier.weight(1f - split).fillMaxHeight().then(paneSurface).padding(spacing)) {
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
                                Box(Modifier.fillMaxSize()) { paneContent(target, Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
        LyricsOverlay(showLyrics, state, onClose = { showLyrics = false }, onTogglePlay, onPrevious, onNext, onSeek)
        CoverOverlay(showCover && !showLyrics, state, onClose = { showCover = false }, onTogglePlay, onPrevious, onNext, onSeek, onToggleLike,
            volume, onVolumeChange, onToggleMute, moreMenu, openAlbum, openArtist)
        if (fullscreenActive && video != null) FullscreenMusicVideo(
            video, state, playerAccent,
            onAudio = { fullscreenVideo = false; showVideo = false },
            onExit = { fullscreenVideo = false },
            onVideoQualityChange = onVideoQualityChange,
            onTogglePlay = onTogglePlay, onPrevious = onPrevious, onNext = onNext, onSeek = onSeek,
        )
        val immersive by rememberUpdatedState(onImmersiveLyrics)
        val takesScreen = showLyrics || fullscreenActive
        LaunchedEffect(takesScreen) { immersive(takesScreen) }
        if (showComments && comments.supported) CommentsSheet(comments, playerAccent, state.positionSec, state.durationSec, focusComment,
            onSeek = onSeek, onDismiss = { showComments = false; focusComment = null })
    }
    }
    }
}

private const val DefaultArtSize = 0.86f
private val MaxArt = 640.dp
private val MinArt = 160.dp
private val MinCanvasWidth = 200.dp
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

// apple-music style full-art view: the cover fills the window and melts into its own colours behind the controls
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CoverOverlay(
    visible: Boolean,
    state: PlayerUiState,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    menu: @Composable () -> Unit,
    onOpenAlbum: (() -> Unit)?,
    onOpenArtist: (() -> Unit)?,
) {
    androidx.compose.animation.AnimatedVisibility(visible, enter = fadeIn(tween(320)), exit = fadeOut(tween(240))) {
        MaterialTheme(colorScheme = canvasColors()) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            val song = state.current
            val noRipple = remember { MutableInteractionSource() }
            val settle = remember(song.id) { androidx.compose.animation.core.Animatable(1.12f) }
            LaunchedEffect(song.id) { settle.animateTo(1f, tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            val drift = coverDrift(state.isPlaying)
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF17191D)).clickable(noRipple, indication = null) {}) {
                LyricsBackdrop(song, state.isPlaying)
                val wide = maxWidth > maxHeight
                val side = if (wide) maxHeight else maxWidth
                val fade = if (wide) Brush.horizontalGradient(.6f to Color.Black, 1f to Color.Transparent)
                    else Brush.verticalGradient(.6f to Color.Black, 1f to Color.Transparent)
                Artwork(
                    song.artworkUrl, song.accent,
                    Modifier.size(side)
                        .clickable(noRipple, indication = null, onClick = onClose).pointerHoverIcon(PointerIcon.Hand)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
                        .drawWithContent { drawContent(); drawRect(fade, blendMode = BlendMode.DstIn) }
                        .graphicsLayer { kenBurns(drift.floatValue, settle.value) },
                    corner = 0.dp, animated = CoverKey.of(song),
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188),
                    modifier = Modifier.padding(16.dp).size(40.dp).paperClip(CircleShape).background(Color.Black.copy(alpha = .3f))
                        .clickable(onClick = onClose).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
                )
                Box(Modifier.align(Alignment.TopEnd).padding(16.dp).paperClip(CircleShape).background(Color.Black.copy(alpha = .3f))) { menu() }
                val controlsWidth = if (wide) (maxWidth - side * .8f).coerceIn(360.dp, 560.dp) else maxWidth
                Column(
                    Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter).width(controlsWidth)
                        .padding(horizontal = 40.dp, vertical = 32.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SongTitles(song, MaterialTheme.typography.headlineMedium, MaterialTheme.typography.titleMedium, onOpenAlbum, onOpenArtist)
                        }
                        if (com.aurora.music.ui.components.LocalCanLike.current(song.id)) {
                            com.aurora.music.ui.components.LikeHeart(song, onToggleLike, Modifier.size(48.dp).paperClip(CircleShape)) {
                                Icon(if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    appString(R.string.text_like_c7e02c), Modifier.size(28.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SeekBar(state.progress, state.positionSec.toInt(), state.durationSec, state.isLive, Color.White, song.id.hashCode(),
                        LocalUiPrefs.current.playerSeekStyle, LocalUiPrefs.current.playerWaveBars, onSeek)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942),
                            modifier = Modifier.size(64.dp).paperClip(CircleShape).clickable(onClick = onPrevious).pointerHoverIcon(PointerIcon.Hand).padding(8.dp))
                        Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, appString(R.string.text_play_pause_14a1d0),
                            modifier = Modifier.size(80.dp).paperClip(CircleShape).clickable(onClick = onTogglePlay).pointerHoverIcon(PointerIcon.Hand).padding(8.dp))
                        Icon(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819),
                            modifier = Modifier.size(64.dp).paperClip(CircleShape).clickable(onClick = onNext).pointerHoverIcon(PointerIcon.Hand).padding(8.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    VolumeControl(volume, onVolumeChange, onToggleMute, controlsWidth - 140.dp, Modifier.align(Alignment.CenterHorizontally), fill = Color.White)
                }
            }
        }
        }
    }
    BackHandler(enabled = visible) { onClose() }
}

// playing time, stepped like the lyrics glow: the cover moves under a pixel per step so a dozen redraws a second look smooth
@Composable
internal fun coverDrift(running: Boolean): androidx.compose.runtime.FloatState {
    val drift = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val from = drift.floatValue
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) {
            drift.floatValue = from + (androidx.compose.runtime.withFrameMillis { it } - start) / 1000f
            kotlinx.coroutines.delay(80)
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
private fun MarqueeTitle(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight? = FontWeight.Bold,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    menu: @Composable () -> Unit = {},
) {
    var fullWidth by remember(text) { androidx.compose.runtime.mutableIntStateOf(0) }
    var shownWidth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val overflowing = fullWidth > shownWidth && shownWidth > 0
    val tap = onClick ?: onMenu
    // each new title scrolls twice then rests, so an idle player stops repainting
    androidx.compose.runtime.key(text) {
        Box {
            Text(
                text, style = style, fontWeight = fontWeight,
                maxLines = 1, softWrap = false, color = color,
                onTextLayout = { fullWidth = it.size.width },
                modifier = Modifier
                    .then(if (tap != null) Modifier.combinedClickable(onClickLabel = onClickLabel, role = Role.Button,
                        onLongClickLabel = onMenu?.let { appString(R.string.song_menu_more) }, onLongClick = onMenu,
                        onDoubleClick = onMenu, onClick = tap).pointerHoverIcon(PointerIcon.Hand) else Modifier)
                    .then(if (onMenu != null) Modifier.pointerInput(onMenu) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    event.changes.forEach { it.consume() }
                                    onMenu()
                                }
                            }
                        }
                    } else Modifier)
                    .onSizeChanged { shownWidth = it.width }
                    .then(if (overflowing) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                        drawContent()
                        // the title rests at its start, so only the right edge fades
                        drawRect(Brush.horizontalGradient(0f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn)
                    } else Modifier)
                    .basicMarquee(iterations = 2, initialDelayMillis = 1500, repeatDelayMillis = 2500),
            )
            menu()
        }
    }
}

// a click opens the album or artist; a double or right click, or a click with nothing to open, the song's menu
@Composable
private fun SongTitles(
    song: com.aurora.music.model.Song,
    titleStyle: androidx.compose.ui.text.TextStyle,
    artistStyle: androidx.compose.ui.text.TextStyle,
    openAlbum: (() -> Unit)?,
    openArtist: (() -> Unit)?,
    titleWeight: FontWeight? = FontWeight.Bold,
    gap: Dp = 0.dp,
) {
    val sources = LocalSongSources.current?.takeIf { songMenusFor(song) }
    var menu by remember(song.id) { mutableStateOf<SongMenuPage?>(null) }
    MarqueeTitle(song.title, titleStyle, fontWeight = titleWeight, onClickLabel = appString(R.string.text_go_to_album_e2d3b3), onClick = openAlbum,
        onMenu = sources?.let { { menu = SongMenuPage.ROOT } }) {
        if (menu == SongMenuPage.ROOT && sources != null) SongSourcesMenu(song, sources, SongMenuPage.ROOT) { menu = null }
    }
    Spacer(Modifier.height(gap))
    MarqueeTitle(song.artist, artistStyle, MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = null,
        onClickLabel = appString(R.string.text_go_to_artist_d8f70c), onClick = openArtist,
        onMenu = sources?.let { { menu = SongMenuPage.ARTIST } }) {
        if (menu == SongMenuPage.ARTIST && sources != null) SongSourcesMenu(song, sources, SongMenuPage.ARTIST) { menu = null }
    }
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
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, enabled: Boolean = true,
    onDoubleClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Icon(
        icon, label,
        tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = if (enabled) 1f else .38f),
        modifier = Modifier.size(40.dp).paperClip(CircleShape).combinedClickable(enabled = enabled, role = Role.Button, onDoubleClick = onDoubleClick, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
    )
}

// canvas mode is always dark: white type over a moving picture stays legible whatever the theme
@Composable
private fun canvasColors(): ColorScheme {
    val colors = MaterialTheme.colorScheme
    if (colors.onBackground.luminance() > 0.5f) return colors
    val style = LocalUiPrefs.current.themeStyle
    return if (style == ThemeStyle.AURORA) playerColorScheme(readableAccent(colors.primary, darkBackground = true), dark = true)
    else styleColorScheme(style, dark = true)
}

// the canvas's own colour fills the player and a blurred copy glows out from behind the card
@Composable
private fun CanvasAmbient(player: com.aurora.music.desktop.video.VideoPlayer, stage: State<Rect>, modifier: Modifier) {
    val frame = player.frame.collectAsState()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.positionInWindow() }.drawBehind {
        frame.value
        drawRect(dimmed(Color(player.tone), 0.06f))
    }) {
        Box(Modifier.fillMaxSize().blur(64.dp, BlurredEdgeTreatment.Rectangle).drawBehind {
            val image = frame.value ?: return@drawBehind
            val card = stage.value.translate(-origin)
            val glow = if (card.isEmpty) size.toRect()
                else Rect(card.center - Offset(card.width * .75f, card.height * .75f), card.center + Offset(card.width * .75f, card.height * .75f))
            drawFrame(image, crop = true, area = glow, alpha = .9f)
        })
        Box(Modifier.fillMaxSize().drawBehind {
            frame.value
            val light = Color(player.tone).luminance()
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .2f + light * .4f), Color.Black.copy(alpha = .45f + light * .4f))))
        })
    }
}

private fun dimmed(color: Color, target: Float): Color {
    val light = color.luminance()
    if (light <= target) return color
    val keep = (target / light).pow(1 / 2.2f)
    return Color(color.red * keep, color.green * keep, color.blue * keep)
}

private fun DrawScope.drawFrame(image: ImageBitmap, crop: Boolean, area: Rect = size.toRect(), alpha: Float = 1f) {
    val scale = if (crop) maxOf(area.width / image.width, area.height / image.height) else minOf(area.width / image.width, area.height / image.height)
    val shown = Size(minOf(area.width / scale, image.width.toFloat()), minOf(area.height / scale, image.height.toFloat()))
    val target = Size(shown.width * scale, shown.height * scale)
    drawImage(
        image,
        srcOffset = IntOffset(((image.width - shown.width) / 2).roundToInt(), ((image.height - shown.height) / 2).roundToInt()),
        srcSize = IntSize(shown.width.roundToInt(), shown.height.roundToInt()),
        dstOffset = IntOffset((area.left + (area.width - target.width) / 2).roundToInt(), (area.top + (area.height - target.height) / 2).roundToInt()),
        dstSize = IntSize(target.width.roundToInt(), target.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

@Composable
private fun BottomUtil(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.then(
            if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) Modifier.paperClip(RoundedCornerShape(50))
            else Modifier.auroraPanel(MaterialTheme.shapes.small)
        ).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (isLive) Box(Modifier.size(8.dp).paperClip(CircleShape).background(accent))
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

/** The latest decoded frame, letterboxed for music videos or cropped to fill for canvases. */
@Composable
fun VideoSurface(player: com.aurora.music.desktop.video.VideoPlayer, modifier: Modifier = Modifier, corner: Dp = 0.dp, fill: Boolean = false) {
    val frame = player.frame.collectAsState()
    Box(modifier.paperClip(RoundedCornerShape(corner)).background(Color.Black).drawBehind { frame.value?.let { drawFrame(it, crop = fill) } })
}

@Composable
internal fun VideoQualityControl(qualityHeight: Int?, onQualityChange: (Int?) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        com.aurora.music.ui.theme.FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.height(36.dp).pointerHoverIcon(PointerIcon.Hand),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = Color.Black.copy(alpha = .5f), contentColor = Color.White)) {
            Text(qualityHeight?.let { "${it}p" } ?: appString(R.string.video_quality_auto), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(null, 360, 480, 720, 1080, 1440, 2160).forEach { height ->
                DropdownMenuItem(text = { Text(height?.let { "${it}p" } ?: appString(R.string.video_quality_auto)) },
                    leadingIcon = { if (height == qualityHeight) Icon(Icons.Filled.Check, null) },
                    onClick = { expanded = false; onQualityChange(height) })
            }
        }
    }
}
