package com.aurora.music.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.coverTint
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.screens.home.ConsoleModule
import com.aurora.music.ui.screens.home.spotSurface
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.pressClickable

private val Gap = 10.dp
private val RowHeight = 46.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConsolePlayer(p: PlayerParts, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val state = p.state
    val tint = coverTint(state)
    val tools = LocalUiPrefs.current.playerShowUtilities
    Column(
        modifier.flatStage().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ConsoleKey { PlayerKey(Icons.Filled.KeyboardArrowDown, appString(R.string.text_collapse_9cf188), onClick = p.onCollapse) }
            PlayingFrom(state.current, Modifier.weight(1f).padding(horizontal = 4.dp))
            p.videoToggle?.let { ConsoleKey(it) }
            p.djControl?.let { ConsoleKey(it) }
            ConsoleKey { PlayerCastButton(Modifier.size(40.dp)) }
            // the output cell goes with the tools, so it moves up here when they are hidden
            if (!tools) ConsoleKey { PlayerKey(Icons.Filled.Speaker, appString(R.string.text_output_device_709178), onClick = p.onOpenOutput) }
            ConsoleKey(p.moreMenu)
        }
        AnimatedContent(
            targetState = p.view,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            label = "consoleView",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { view ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                if (view == StyledView.NOW) Deck(p, tint, tools) else {
                    ConsoleModule(tint = tint.value, padding = PaddingValues(start = 10.dp, end = 6.dp)) {
                        NowStrip(p, cover = { Artwork(state.current.artworkUrl, state.current.accent, Modifier.size(44.dp), corner = 10.dp) }) {
                            PlayerLike(state, p.onToggleLike, glyph = 24.dp)
                        }
                    }
                    ConsoleModule(Modifier.weight(1f), padding = PaddingValues(0.dp)) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ConsoleLabel(appString(if (view == StyledView.LYRICS) R.string.text_lyrics_8670cb else R.string.text_queue_d325fc), Modifier.weight(1f))
                            if (view == StyledView.LYRICS) LyricsTranslateKey() else p.queueActions()
                            PlayerKey(Icons.Filled.Close, appString(R.string.text_close_bbfa77)) { p.onView(StyledView.NOW) }
                        }
                        if (view == StyledView.LYRICS) LyricsPane(state, p.onSeek, Modifier.weight(1f).fillMaxWidth(), ink = colors.onSurface)
                        else p.queue(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp))
                    }
                }
            }
        }
        ConsoleModule(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)) {
            p.seek(p.view != StyledView.NOW, colors.primary)
            Spacer(Modifier.height(4.dp))
            p.transport(Modifier.size(64.dp, 56.dp).clip(MaterialTheme.shapes.medium), colors.inverseSurface, colors.inverseOnSurface, false)
        }
    }
}

// shortcut: the fixed modules can outgrow a screen under about 600dp tall, make the deck scroll if such phones matter
@Composable
private fun ColumnScope.Deck(p: PlayerParts, tint: State<Color>, tools: Boolean) {
    val colors = MaterialTheme.colorScheme
    val state = p.state
    val song = state.current
    ConsoleModule(tint = tint.value, padding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)) {
        if (p.wideArt) p.artwork(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!p.wideArt) {
                p.artwork(Modifier.size(96.dp))
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                SongTitles(song, MaterialTheme.typography.headlineSmall, MaterialTheme.typography.bodyMedium, p.openAlbum, p.openArtist, gap = 2.dp)
                BpmLine(state)
            }
            PlayerLike(state, p.onToggleLike, Modifier.padding(start = 10.dp).size(44.dp).clip(MaterialTheme.shapes.small)
                .background(colors.onSurface.copy(alpha = 0.1f)), 24.dp)
        }
        p.chips()
    }
    val hasLyrics = rememberLyrics(song)?.lines?.isNotEmpty() == true
    ConsoleModule(padding = PaddingValues(0.dp), onClick = { p.onView(StyledView.LYRICS) }) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ConsoleLabel(appString(R.string.text_lyrics_8670cb), Modifier.weight(1f))
            Icon(Icons.Filled.OpenInFull, appString(R.string.tablet_fullscreen_lyrics), Modifier.size(44.dp).padding(13.dp))
        }
        if (hasLyrics) LyricsPane(state, p.onSeek, Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 14.dp), ink = colors.onSurface,
            onGlance = { p.onView(StyledView.LYRICS) })
    }
    if (p.hasQueue) BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val next = (state.currentIndex + 1).coerceAtLeast(0)
        val upcoming = (state.queue.size - next).coerceAtLeast(0)
        val rows = ((maxHeight - 44.dp) / RowHeight).toInt().coerceIn(0, upcoming)
        // fills the gap when the height, not the queue, cuts the list
        if (maxHeight >= 44.dp) ConsoleModule(if (rows in 1 until upcoming) Modifier.fillMaxHeight() else Modifier,
            padding = PaddingValues(start = 14.dp, end = 4.dp), onClick = { p.onView(StyledView.QUEUE) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConsoleLabel(if (upcoming == 0) appString(R.string.text_nothing_up_next_295357)
                    else appString(R.string.queue_track_count, appPlural(R.plurals.track_count, upcoming)), Modifier.weight(1f))
                Icon(Icons.Filled.OpenInFull, appString(R.string.text_view_queue_827a90), Modifier.size(44.dp).padding(13.dp))
            }
            repeat(rows) { i ->
                val track = state.queue[next + i]
                Row(Modifier.height(RowHeight).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Artwork(track.artworkUrl, track.accent, Modifier.size(36.dp), corner = 8.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatTime(track.durationSec), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    } else Spacer(Modifier.weight(1f))
    if (tools) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConsoleCell(Icons.Filled.Speed, appString(R.string.text_speed_x_72da98, "%.1f".format(state.speed)), speedActive(state), p.onOpenSpeedPitch)
        ConsoleCell(Icons.Filled.Bedtime, appString(R.string.text_sleep_timer_e90613), state.sleepTimerMinutes > 0 || state.sleepEndOfTrack, p.onOpenSleep)
        ConsoleCell(Icons.Filled.GraphicEq, appString(R.string.text_visualizer_7177c7), false, p.onOpenVisualizer)
        ConsoleCell(Icons.Filled.Speaker, appString(R.string.text_output_4bed33), false, p.onOpenOutput)
    }
}

@Composable
private fun ConsoleKey(content: @Composable () -> Unit) =
    Box(Modifier.size(44.dp).spotSurface(MaterialTheme.shapes.small, MaterialTheme.colorScheme.surface, outlined = true), Alignment.Center) { content() }

@Composable
private fun RowScope.ConsoleCell(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (active) colors.primary else colors.onSurfaceVariant, Motion.state(), label = "consoleCell")
    Column(
        Modifier.weight(1f).height(58.dp).spotSurface(MaterialTheme.shapes.medium, colors.surface, outlined = true)
            .pressClickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
