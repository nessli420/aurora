package com.aurora.music.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ThemeStyle
import com.aurora.music.model.accent
import com.aurora.music.ui.screens.home.ConsoleModule
import com.aurora.music.ui.screens.home.SignalModule
import com.aurora.music.ui.screens.home.spotSurface
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.themedCorner
import com.aurora.music.util.MotionTokens

private val Gap = 14.dp

@Composable
internal fun PlayerConsole(p: PlayerParts) {
    val colors = MaterialTheme.colorScheme
    val panes = LocalPlayerPanes.current
    var beside by remember { mutableStateOf(PlayerPane.LYRICS) }
    Column(
        Modifier.fillMaxSize().then(if (p.flat) Modifier.background(colors.background) else Modifier)
            .padding(start = 22.dp, top = 12.dp, end = 22.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        PlayerTopBar(p) {
            val ui = LocalUiPrefs.current
            Box(
                Modifier.padding(end = 8.dp).width(if (ui.playerShowUtilities) 440.dp else 170.dp)
                    .then(if (ui.themeStyle == ThemeStyle.AURORA) Modifier.spotSurface(MaterialTheme.shapes.medium, outlined = true) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) { p.utilities(104.dp) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 1100.dp
            val left = if (wide) 330.dp else 280.dp
            val cover = minOf(left - 36.dp, maxHeight - 420.dp).coerceAtLeast(96.dp)
            val queueBeside = wide && PlayerPane.QUEUE in panes
            PlayerViews(p.paneOpen) { open ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    Column(Modifier.width(left).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                        NowPlaying(p, cover, if (open) Modifier.weight(1f) else Modifier)
                        if (open) Deck(p) else SignalModule(p.onOpenSignalPath, Modifier.weight(1f), stacked = true)
                    }
                    if (open) PaneModule(p, p.pane, panes, { p.showPane(it, true) }, true, Modifier.weight(1f).fillMaxHeight())
                    else Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                        Deck(p)
                        val others = panes - PlayerPane.QUEUE
                        if (queueBeside) PaneModule(p, beside.takeIf { it in others } ?: PlayerPane.LYRICS, others, { beside = it }, false, Modifier.weight(1f))
                        else PaneModule(p, p.pane, panes, { p.showPane(it, false) }, false, Modifier.weight(1f))
                    }
                    if (!open && queueBeside) PaneModule(p, PlayerPane.QUEUE, listOf(PlayerPane.QUEUE), {}, false, Modifier.width(400.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(p: PlayerParts, cover: Dp, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val song = p.state.current
    val tint by animateColorAsState(
        lerp(colors.surface, spotlightTint(song.artworkUrl, song.accent), LocalUiPrefs.current.playerGradient.coerceIn(0f, 1f)),
        Motion.state(MotionTokens.SLOW), label = "nowTint",
    )
    ConsoleModule(modifier.fillMaxWidth(), tint = tint, lift = false) {
        OnCoverTint {
            p.cover(Modifier.align(Alignment.CenterHorizontally).size(cover), Modifier, null)
            Spacer(Modifier.height(14.dp))
            p.titles()
            Spacer(Modifier.height(6.dp))
            p.chips()
        }
    }
}

@Composable
private fun Deck(p: PlayerParts) {
    val colors = MaterialTheme.colorScheme
    ConsoleModule(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 22.dp, vertical = 14.dp), lift = false) {
        p.seek()
        Spacer(Modifier.height(8.dp))
        p.transport(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 380.dp).fillMaxWidth(), colors.inverseSurface, colors.inverseOnSurface,
            paperSquare(RoundedCornerShape(themedCorner(18.dp))))
    }
}

@Composable
private fun PaneModule(p: PlayerParts, pane: PlayerPane, panes: List<PlayerPane>, onSelect: (PlayerPane) -> Unit, expanded: Boolean, modifier: Modifier) {
    ConsoleModule(modifier.fillMaxWidth(), padding = PaddingValues(start = 18.dp, top = 8.dp, end = 10.dp, bottom = 10.dp), lift = false) {
        PlayerPaneSlot(p, pane, panes, onSelect, expanded, Modifier.fillMaxSize(), eyebrow = true)
    }
}
