package com.aurora.music.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.localization.appString
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.paperClip

internal class PlayerParts(
    val state: PlayerUiState,
    val pane: PlayerPane,
    val paneOpen: Boolean,
    val showPane: (PlayerPane, Boolean) -> Unit,
    val flat: Boolean,
    val canvas: Boolean,
    val video: Boolean,
    val onCollapse: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onOpenSignalPath: () -> Unit,
    val onShowCover: () -> Unit,
    val openAlbum: (() -> Unit)?,
    val openArtist: (() -> Unit)?,
    val headerTail: @Composable () -> Unit,
    val cover: @Composable (Modifier, Modifier, (() -> Unit)?) -> Unit,
    val titles: @Composable () -> Unit,
    val like: @Composable () -> Unit,
    val chips: @Composable () -> Unit,
    val seek: @Composable () -> Unit,
    val transport: @Composable (Modifier, Color, Color, Shape) -> Unit,
    val utilities: @Composable (Dp) -> Unit,
    val paneContent: @Composable (PlayerPane, Modifier) -> Unit,
    val paneActions: (@Composable (PlayerPane) -> Unit)?,
)

// the accent has no sure contrast on a cover's colour, so the marks that use it take the text colour there
@Composable
internal fun OnCoverTint(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = remember(colors) {
            colors.copy(primary = colors.inverseSurface, onPrimary = colors.inverseOnSurface, onSurfaceVariant = colors.onSurface.copy(alpha = 0.62f))
        },
        content = content,
    )
}

@Composable
internal fun PlayerTopBar(p: PlayerParts, modifier: Modifier = Modifier, tools: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188),
            modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = p.onCollapse).pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(appString(R.string.text_playing_from_5f4dc3), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
            MarqueeTitle(p.state.current.album.ifBlank { appString(R.string.text_aurora_eeee9b) })
        }
        tools()
        p.headerTail()
    }
}

@Composable
internal fun PlayerViews(open: Boolean, modifier: Modifier = Modifier, content: @Composable (Boolean) -> Unit) =
    AnimatedContent(open, modifier, transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) }, label = "playerView") { content(it) }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerPaneSlot(
    p: PlayerParts,
    pane: PlayerPane,
    panes: List<PlayerPane>,
    onSelect: (PlayerPane) -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    eyebrow: Boolean = false,
    lead: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            lead()
            Box(Modifier.weight(1f)) {
                if (panes.size > 1) PaneSwitcher(pane, onSelect, Modifier.horizontalScroll(rememberScrollState()), panes)
                else if (eyebrow) ConsoleLabel(pane.label)
                else Text(pane.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            p.paneActions?.invoke(pane)
            IconButton(onClick = { p.showPane(pane, !expanded) }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Icon(
                    if (expanded) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                    appString(if (expanded) R.string.video_fullscreen_exit else R.string.video_fullscreen_enter),
                    tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        CompositionLocalProvider(LocalLyricsInk provides colors.onSurface, LocalLyricsRoomy provides expanded) {
            AnimatedContent(
                pane, Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) }, label = "playerPane",
            ) { target ->
                Box(Modifier.fillMaxSize()) { p.paneContent(target, Modifier.fillMaxSize()) }
            }
        }
    }
    BackHandler(enabled = expanded) { p.showPane(pane, false) }
}
