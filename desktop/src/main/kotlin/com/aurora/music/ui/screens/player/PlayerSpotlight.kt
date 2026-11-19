package com.aurora.music.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aurora.music.model.accent
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.util.MotionTokens

@Composable
internal fun PlayerSpotlight(p: PlayerParts) {
    val colors = MaterialTheme.colorScheme
    val song = p.state.current
    val tint by animateColorAsState(
        lerp(colors.background, spotlightTint(song.artworkUrl, song.accent), LocalUiPrefs.current.playerGradient.coerceIn(0f, 1f)),
        Motion.state(MotionTokens.SLOW), label = "stageTint",
    )
    val panes = LocalPlayerPanes.current
    var beside by remember { mutableStateOf(PlayerPane.LYRICS) }
    OnCoverTint {
        BoxWithConstraints(Modifier.fillMaxSize().then(if (p.canvas) Modifier else Modifier.background(tint))) {
            val wide = maxWidth >= 1100.dp
            val gutter = if (wide) 44.dp else 28.dp
            Column(Modifier.fillMaxSize()) {
                PlayerTopBar(p, Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 8.dp)) {
                    if (!wide && !p.paneOpen) PaneSwitcher(p.pane, { p.showPane(it, false) }, Modifier.padding(end = 8.dp))
                }
                PlayerViews(p.paneOpen, Modifier.weight(1f)) { open ->
                    Row(
                        Modifier.fillMaxSize().padding(start = gutter, end = gutter, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (wide) 48.dp else 32.dp),
                    ) {
                        if (open) {
                            Stage(p, compact = true, Modifier.width(if (wide) 340.dp else 300.dp).fillMaxHeight())
                            PlayerPaneSlot(p, p.pane, panes, { p.showPane(it, true) }, expanded = true, Modifier.weight(1f).fillMaxHeight())
                        } else if (wide) {
                            val others = panes - PlayerPane.QUEUE
                            if (PlayerPane.QUEUE in panes) PlayerPaneSlot(p, PlayerPane.QUEUE, listOf(PlayerPane.QUEUE), {}, expanded = false,
                                Modifier.width(340.dp).fillMaxHeight())
                            Stage(p, compact = false, Modifier.weight(1f).fillMaxHeight())
                            PlayerPaneSlot(p, beside.takeIf { it in others } ?: PlayerPane.LYRICS, others, { beside = it }, expanded = false,
                                Modifier.width(380.dp).fillMaxHeight())
                        } else {
                            Stage(p, compact = false, Modifier.weight(1f).fillMaxHeight())
                            PlayerPaneSlot(p, p.pane, listOf(p.pane), {}, expanded = false, Modifier.weight(0.9f).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stage(p: PlayerParts, compact: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scale = LocalUiPrefs.current.playerArtSize.coerceIn(0.5f, 1.2f) / 0.86f
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, 520.dp)
        var controls by remember { mutableStateOf(if (compact) 240.dp else 330.dp) }
        val art = minOf(maxHeight - controls - 20.dp, width, (if (compact) 260.dp else 340.dp) * scale).coerceAtLeast(120.dp)
        Column(Modifier.width(width).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            p.cover(Modifier.size(art), Modifier, null)
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().onSizeChanged { controls = with(density) { it.height.toDp() } }) {
                p.titles()
                if (!compact) {
                    Spacer(Modifier.height(10.dp))
                    p.chips()
                }
                Spacer(Modifier.height(8.dp))
                p.seek()
                Spacer(Modifier.height(8.dp))
                p.transport(Modifier.fillMaxWidth(), colors.inverseSurface, colors.inverseOnSurface, paperSquare(CircleShape))
                if (!compact) {
                    Spacer(Modifier.height(12.dp))
                    p.utilities((width - 300.dp).coerceIn(104.dp, 160.dp))
                }
            }
        }
    }
}
