package com.aurora.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.BarStyle
import com.aurora.music.data.MiniProgress
import com.aurora.music.data.MiniStyle
import com.aurora.music.data.ThemeStyle
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.navigation.NavMenuItem
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.theme.LocalContextAccent
import com.aurora.music.ui.theme.LocalGlassBackdrop
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.chromeScrim
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.likePop
import com.aurora.music.ui.theme.liquidGlass
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.util.MotionTokens
import com.aurora.music.viewmodel.PlayerUiState

@Composable
fun activeBarStyle(): Int = LocalUiPrefs.current.bars

private class BarTab(val label: String, val icon: ImageVector, val selected: Boolean, val route: String?, val onClick: () -> Unit)

@Composable
fun StyledBars(
    style: Int,
    state: PlayerUiState,
    currentRoute: String?,
    tabs: List<NavMenuItem>,
    moreTab: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLike: () -> Unit,
    onNext: () -> Unit,
    onNavigate: (String) -> Unit,
    onMore: () -> Unit,
    onLongPress: (String) -> Unit,
    notices: (@Composable () -> Unit)?,
    navMenu: @Composable () -> Unit,
) {
    val items = tabs.map { tab ->
        val selected = currentRoute == tab.route
        BarTab(tab.shortLabel, if (selected) tab.selectedIcon else tab.icon, selected, tab.route) { onNavigate(tab.route) }
    } + if (moreTab) listOf(BarTab(appString(R.string.text_more_4bab2d), Icons.Filled.MoreHoriz, false, null, onMore)) else emptyList()
    val miniEnter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 3 }
    if (style == BarStyle.CONSOLE) Column(Modifier.fillMaxWidth()) {
        if (notices != null) Column(
            Modifier.fillMaxWidth().chromeScrim().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { notices() }
        // the empty pointer input keeps taps on the block's gaps from reaching the page under it
        Column(Modifier.fillMaxWidth().consoleSurface().pointerInput(Unit) {}.windowInsetsPadding(WindowInsets.navigationBars)) {
            AnimatedVisibility(state.showsBar, enter = miniEnter, exit = ExitTransition.None) {
                ConsoleMini(state, onExpand, onTogglePlay, onNext)
            }
            Box {
                ConsoleNav(items, onLongPress)
                Box(Modifier.align(Alignment.TopStart).padding(start = 8.dp)) { navMenu() }
            }
        }
    } else Column(
        Modifier.fillMaxWidth().chromeScrim().windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        notices?.invoke()
        if (style == BarStyle.SPOTLIGHT) AnimatedVisibility(state.showsBar, enter = miniEnter, exit = ExitTransition.None) {
            Column {
                SpotlightMini(state, onExpand, onTogglePlay, onToggleLike, onNext)
                Spacer(Modifier.height(10.dp))
            }
        }
        Box {
            if (style == BarStyle.SPOTLIGHT) SpotlightNav(items, onLongPress) else DriftCapsule(state, items, onExpand, onTogglePlay, onLongPress)
            Box(Modifier.align(Alignment.TopStart).padding(start = 8.dp)) { navMenu() }
        }
    }
}

@Composable
private fun SpotlightMini(state: PlayerUiState, onExpand: () -> Unit, onTogglePlay: () -> Unit, onToggleLike: () -> Unit, onNext: () -> Unit) {
    val song = state.current
    val isMix = song.id.startsWith("aurora-mix:")
    val ui = LocalUiPrefs.current
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = miniSize(64.dp, 56.dp, 76.dp))
            .tinted(if (ui.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(22.dp) else MaterialTheme.shapes.large, coverTint(state))
            .edgeProgress(state, top = false, inset = 16.dp, track = colors.onSurface.copy(alpha = 0.22f), fill = colors.onSurface)
            .clickable(onClick = onExpand)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artworkUrl, song.accent, Modifier.size(miniSize(46.dp, 40.dp, 56.dp)), corner = 12.dp)
        Spacer(Modifier.width(10.dp))
        TrackText(state, Modifier.weight(1f), artist = colors.onSurface.copy(alpha = 0.8f))
        if (ui.miniStyle != MiniStyle.COMPACT && !isMix && LocalCanLike.current(song.id)) {
            val likeTint by animateColorAsState(if (state.isCurrentLiked) barAccent() else colors.onSurface, label = "barLike")
            LikeHeart(song, onToggleLike, Modifier.size(44.dp).paperClip(CircleShape)) {
                Icon(
                    if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    appString(R.string.text_like_c7e02c),
                    tint = likeTint,
                    modifier = Modifier.size(24.dp).likePop(state.isCurrentLiked, song.id),
                )
            }
        }
        PlayButton(state.isPlaying, filled = true, onClick = onTogglePlay)
        if (!isMix) NextButton(onNext)
    }
}

@Composable
private fun SpotlightNav(tabs: List<BarTab>, onLongPress: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tabShape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) CircleShape else MaterialTheme.shapes.small
    Row(
        Modifier.fillMaxWidth().height(64.dp).barSurface(RoundedCornerShape(32.dp), colors.surface, colors.surfaceContainerHigh).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val on by animateFloatAsState(if (tab.selected) 1f else 0f, Motion.enter(MotionTokens.EMPHASIZED), label = "barPill")
            val content = lerp(colors.onSurfaceVariant, colors.inverseOnSurface, on)
            Row(
                Modifier.weight(1f + 0.7f * on).navHold(tab, onLongPress).height(48.dp).clip(tabShape)
                    .background(colors.inverseSurface.copy(alpha = on))
                    .clickable(onClickLabel = tab.label, role = Role.Tab, onClick = tab.onClick)
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tab.icon, tab.label, tint = content, modifier = Modifier.size(22.dp))
                AnimatedVisibility(visible = tab.selected) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        Text(tab.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = content,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleMini(state: PlayerUiState, onExpand: () -> Unit, onTogglePlay: () -> Unit, onNext: () -> Unit) {
    val song = state.current
    val colors = MaterialTheme.colorScheme
    val divider = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) colors.surfaceContainer else colors.outlineVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = miniSize(60.dp, 52.dp, 72.dp))
            .drawBehind { drawRect(divider, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .edgeProgress(state, top = true, inset = 0.dp, track = colors.outlineVariant, fill = barAccent())
            .clickable(onClick = onExpand)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artworkUrl, song.accent, Modifier.size(miniSize(40.dp, 36.dp, 50.dp)), corner = 9.dp)
        Spacer(Modifier.width(10.dp))
        TrackText(state, Modifier.weight(1f))
        FormatChip(song, Modifier.padding(start = 10.dp))
        PlayButton(state.isPlaying, filled = false, onClick = onTogglePlay)
        if (!song.id.startsWith("aurora-mix:")) NextButton(onNext)
    }
}

@Composable
private fun ConsoleNav(tabs: List<BarTab>, onLongPress: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selected = tabs.indexOfFirst { it.selected }
    // the indicator stays on its last tab and fades while a page outside the bar is open
    val slot = remember { Animatable(selected.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(selected, tabs.size) {
        if (selected >= 0) slot.animateTo(selected.toFloat(), Motion.enter(MotionTokens.EMPHASIZED))
    }
    val shown = animateFloatAsState(if (selected >= 0) 1f else 0f, Motion.state(), label = "barIndicator")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val square = isPaperTheme()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).drawBehind {
            val width = 36.dp.toPx()
            val height = 3.dp.toPx()
            val centre = size.width / tabs.size * (slot.value + 0.5f)
            val x = (if (rtl) size.width - centre else centre) - width / 2f
            clipRect {
                drawRoundRect(colors.primary.copy(alpha = shown.value), Offset(x, -height), Size(width, height * 2f),
                    CornerRadius(if (square) 0f else height))
            }
        }.padding(vertical = 4.dp),
    ) {
        tabs.forEach { tab ->
            val tint by animateColorAsState(if (tab.selected) colors.primary else colors.onSurfaceVariant, Motion.state(), label = "barTab")
            Column(
                Modifier.weight(1f).navHold(tab, onLongPress).heightIn(min = 50.dp).clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = tab.label, role = Role.Tab, onClick = tab.onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Icon(tab.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                Text(tab.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DriftCapsule(state: PlayerUiState, tabs: List<BarTab>, onExpand: () -> Unit, onTogglePlay: () -> Unit, onLongPress: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val height = miniSize(68.dp, 60.dp, 78.dp)
    // more than three tabs leave no room for the title
    val titled = state.showsBar && tabs.size <= 3
    Row(
        Modifier.fillMaxWidth().height(height).barSurface(CircleShape, colors.surfaceContainer, colors.outlineVariant)
            .pointerInput(Unit) {}.padding(start = 7.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(state.showsBar, if (titled) Modifier.weight(1f) else Modifier, enter = fadeIn(Motion.enter()), exit = ExitTransition.None) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    (if (titled) Modifier.weight(1f) else Modifier).paperClip(CircleShape).clickable(onClick = onExpand),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RingCover(state, Modifier.size(height - 14.dp))
                    if (titled) TrackText(state, Modifier.weight(1f).padding(start = 10.dp, end = 4.dp))
                }
                PlayButton(state.isPlaying, filled = false, onClick = onTogglePlay)
                Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 28.dp).background(colors.outlineVariant))
            }
        }
        Row(if (titled) Modifier.width(46.dp * tabs.size) else Modifier.weight(1f)) {
            tabs.forEach { tab ->
                val on = animateFloatAsState(if (tab.selected) 1f else 0f, Motion.enter(MotionTokens.EMPHASIZED), label = "barDot")
                val tint by animateColorAsState(if (tab.selected) colors.onSurface else colors.onSurfaceVariant, Motion.state(), label = "barTab")
                Column(
                    Modifier.weight(1f).navHold(tab, onLongPress).height(52.dp).paperClip(CircleShape)
                        .clickable(onClickLabel = tab.label, role = Role.Tab, onClick = tab.onClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(22.dp))
                    Box(Modifier.size(5.dp).graphicsLayer { alpha = on.value; scaleX = on.value; scaleY = on.value }
                        .paperClip(CircleShape).background(colors.primary))
                }
            }
        }
    }
}

// the cover inside a ring of playback progress; the position is only read while drawing
@Composable
internal fun RingCover(state: PlayerUiState, modifier: Modifier = Modifier) {
    val song = state.current
    val stroke = progressWidth(3.dp, 5.dp)
    Box(modifier.progressRing(state, stroke, barAccent()).padding(if (stroke > 0.dp) stroke + 2.dp else 0.dp)) {
        Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize(), corner = 100.dp)
    }
}

@Composable
internal fun Modifier.progressRing(state: PlayerUiState, stroke: Dp, fill: Color): Modifier {
    val position = LocalPlaybackPosition.current
    val track = MaterialTheme.colorScheme.outlineVariant
    val square = isPaperTheme()
    return drawWithCache {
        val w = stroke.toPx()
        val ring = Path().apply {
            if (square) {
                moveTo(w / 2f, w / 2f)
                lineTo(size.width - w / 2f, w / 2f)
                lineTo(size.width - w / 2f, size.height - w / 2f)
                lineTo(w / 2f, size.height - w / 2f)
                close()
            } else addArc(Rect(w / 2f, w / 2f, size.width - w / 2f, size.height - w / 2f), -90f, 360f)
        }
        val measure = PathMeasure().apply { setPath(ring, false) }
        val done = Path()
        onDrawBehind {
            if (w > 0f) {
                drawPath(ring, track, style = Stroke(w))
                done.reset()
                measure.getSegment(0f, measure.length * state.progressAt(position.value), done, true)
                drawPath(done, fill, style = Stroke(w))
            }
        }
    }
}

@Composable
internal fun FormatChip(song: Song, modifier: Modifier = Modifier) {
    val khz = song.sampleRateHz / 1000f
    val detail = when {
        song.sampleRateHz <= 0 -> if (song.bitDepth > 0) appString(R.string.text_bit_fd7850, song.bitDepth) else ""
        song.bitDepth > 0 -> "${song.bitDepth}/${if (khz % 1f == 0f) khz.toInt() else khz}"
        else -> appString(R.string.text_1f_khz_92ed69).format(khz)
    }
    val label = listOf(song.suffix.uppercase(), detail).filter { it.isNotBlank() }.joinToString(" ")
    if (label.isBlank()) return
    val colors = MaterialTheme.colorScheme
    Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant,
        maxLines = 1, softWrap = false,
        modifier = modifier.border(1.dp, colors.outlineVariant, paperSquare(RoundedCornerShape(7.dp))).padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
internal fun Modifier.dockSurface(style: Int, state: PlayerUiState): Modifier {
    val colors = MaterialTheme.colorScheme
    return when (style) {
        BarStyle.SPOTLIGHT -> tinted(
            if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(22.dp) else MaterialTheme.shapes.extraLarge, coverTint(state))
        // shortcut: the console dock keeps the floating dock's margins, attach it to the screen edges when the rail and page padding around the dock is reworked
        BarStyle.CONSOLE -> consoleSurface().edgeProgress(state, top = true, inset = 0.dp, track = colors.outlineVariant, fill = barAccent())
        else -> barSurface(CircleShape, colors.surfaceContainer, colors.outlineVariant)
    }
}

@Composable
private fun TrackText(state: PlayerUiState, modifier: Modifier, artist: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val title = if (LocalUiPrefs.current.miniStyle == MiniStyle.PROMINENT) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall
    TrackLabel(state.current, modifier) { track ->
        MarqueeText(track.title, title, MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, paused = state.expanded)
        MarqueeText(track.artist, MaterialTheme.typography.bodySmall, artist, paused = state.expanded)
    }
}

@Composable
private fun PlayButton(playing: Boolean, filled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = appString(R.string.text_play_pause_14a1d0)
    Box(
        Modifier.size(44.dp).paperClip(CircleShape).then(if (filled) Modifier.background(colors.inverseSurface) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }.padding(if (filled) 10.dp else 8.dp),
    ) {
        PlayPauseIcon(playing, if (filled) colors.inverseOnSurface else colors.onSurface, Modifier.fillMaxSize())
    }
}

@Composable
private fun NextButton(onClick: () -> Unit) = Icon(
    Icons.Filled.SkipNext, appString(R.string.text_next_bc9819), tint = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.size(44.dp).paperClip(CircleShape).clickable(onClick = onClick).padding(9.dp),
)

@Composable
private fun miniSize(standard: Dp, compact: Dp, prominent: Dp): Dp = when (LocalUiPrefs.current.miniStyle) {
    MiniStyle.COMPACT -> compact
    MiniStyle.PROMINENT -> prominent
    else -> standard
}

@Composable
private fun progressWidth(line: Dp, bar: Dp): Dp = when (LocalUiPrefs.current.miniProgress) {
    MiniProgress.NONE -> 0.dp
    MiniProgress.BAR -> bar
    else -> line
}

@Composable
private fun barAccent(): Color = LocalContextAccent.current.color ?: MaterialTheme.colorScheme.primary

@Composable
internal fun coverTint(state: PlayerUiState): State<Color> = animateColorAsState(
    spotlightTint(state.current.artworkUrl, if (state.hasTrack) state.current.accent else Color.Unspecified),
    Motion.state(MotionTokens.SLOW), label = "barTint",
)

private fun Modifier.navHold(tab: BarTab, onLongPress: (String) -> Unit): Modifier =
    longPressFirst(tab.route, tab.route?.let { route -> { _, _ -> onLongPress(route) } }).semantics { selected = tab.selected }

// the colour is read while drawing, so a track change repaints the bar without recomposing it
@Composable
private fun Modifier.tinted(shape: Shape, tint: State<Color>): Modifier =
    if (isGlassTheme()) liquidGlass(LocalGlassBackdrop.current, shape, surface = tint.value.copy(alpha = 0.6f))
    else paperClip(shape).drawBehind { drawRect(tint.value) }.then(if (isPaperTheme()) Modifier.border(1.dp, PaperTokens.ink) else Modifier)

// outside aurora a floating bar is the theme's own panel, so retro, aero and paper keep their frames
@Composable
private fun Modifier.barSurface(shape: Shape, fill: Color, edge: Color): Modifier = when (LocalUiPrefs.current.themeStyle) {
    ThemeStyle.AURORA -> clip(shape).background(fill).border(1.dp, edge, shape)
    ThemeStyle.GLASS -> liquidGlass(LocalGlassBackdrop.current, shape)
    else -> auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true)
}

@Composable
private fun Modifier.consoleSurface(): Modifier {
    val colors = MaterialTheme.colorScheme
    return when (LocalUiPrefs.current.themeStyle) {
        ThemeStyle.AURORA -> background(colors.surface).drawBehind { drawRect(colors.surfaceContainerHigh, size = Size(size.width, 1.dp.toPx())) }
        ThemeStyle.GLASS -> liquidGlass(LocalGlassBackdrop.current, RectangleShape, shadow = false)
        else -> auroraPanel(RectangleShape, emphasized = true).padding(top = 1.dp)
    }
}

@Composable
private fun Modifier.edgeProgress(state: PlayerUiState, top: Boolean, inset: Dp, track: Color, fill: Color): Modifier {
    val thickness = progressWidth(2.dp, 4.dp)
    if (thickness == 0.dp) return this
    val position = LocalPlaybackPosition.current
    return drawWithContent {
        drawContent()
        val height = thickness.toPx()
        val start = Offset(inset.toPx(), if (top) 0f else size.height - height)
        val width = size.width - start.x * 2f
        drawRect(track, start, Size(width, height))
        drawRect(fill, start, Size(width * state.progressAt(position.value), height))
    }
}
