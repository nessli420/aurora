package com.aurora.music.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.Lyrics
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.components.progressRing
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperSquare

@Composable
internal fun PlayerDrift(p: PlayerParts) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().then(if (p.flat) Modifier.background(colors.background) else Modifier)) {
        val gutter = if (maxWidth >= 1100.dp) 64.dp else 36.dp
        val ring = minOf(maxWidth * 0.31f, maxHeight - 300.dp, 440.dp).coerceAtLeast(160.dp)
        PlayerViews(p.paneOpen) { open -> if (open) Focus(p, gutter) else Now(p, gutter, ring) }
    }
}

@Composable
private fun Tools(p: PlayerParts) {
    Box(Modifier.padding(end = 8.dp).width(if (LocalUiPrefs.current.playerShowUtilities) 400.dp else 170.dp)) { p.utilities(104.dp) }
}

@Composable
private fun Now(p: PlayerParts, gutter: Dp, ring: Dp) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val state = p.state
    val queue = PlayerPane.QUEUE in LocalPlayerPanes.current
    Box(Modifier.fillMaxSize()) {
        if (!p.video) {
            Peek(state.queue.getOrNull(state.currentIndex - 1), ring * 0.45f, p.onPrevious, Modifier.align(Alignment.TopEnd).padding(top = 84.dp))
            Peek(state.queue.getOrNull(state.currentIndex + 1), ring * 0.45f, p.onNext, Modifier.align(Alignment.BottomEnd).padding(bottom = if (queue) 150.dp else 40.dp))
        }
        Column(Modifier.fillMaxSize()) {
            PlayerTopBar(p, Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 8.dp)) { Tools(p) }
            Row(Modifier.weight(1f).padding(horizontal = gutter)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.durationSec > 0) Text(
                            "${formatTime(state.positionSec)} / ${formatTime(state.durationSec)}",
                            style = type.labelLarge, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(end = 16.dp),
                        )
                        Box(Modifier.weight(1f)) { p.chips() }
                    }
                    SongTitles(state.current, type.displayLarge, type.headlineMedium.copy(fontWeight = FontWeight.Normal), p.openAlbum, p.openArtist,
                        titleWeight = FontWeight.Black, gap = 6.dp, fitTitle = true)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        p.transport(Modifier.width(340.dp), colors.primary, colors.onPrimary, paperSquare(CircleShape))
                        Spacer(Modifier.width(8.dp))
                        p.like()
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.widthIn(max = 560.dp)) { p.seek() }
                    Spacer(Modifier.height(16.dp))
                    NowLines(p, Modifier.weight(1f))
                }
                Spacer(Modifier.width(40.dp))
                Box(Modifier.width(ring + 40.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.size(ring).then(if (p.video) Modifier else Modifier.progressRing(state.progress, colors.primary, colors.outlineVariant, isPaperTheme(), 10.dp))) {
                        p.cover(Modifier.fillMaxSize().padding(if (p.video) 0.dp else 20.dp), if (p.video) Modifier else Modifier.paperClip(CircleShape), p.onTogglePlay.takeUnless { p.video })
                        if (!p.video) IconButton(onClick = p.onShowCover, modifier = Modifier.align(Alignment.BottomEnd).offset(y = 44.dp).pointerHoverIcon(PointerIcon.Hand)) {
                            Icon(Icons.Filled.OpenInFull, appString(R.string.text_artwork_bfbeaf), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (queue) Filmstrip(p, Modifier.padding(start = gutter, end = gutter, bottom = 22.dp))
        }
    }
}

@Composable
private fun Peek(song: Song?, size: Dp, onClick: () -> Unit, modifier: Modifier) {
    if (song == null) return
    Artwork(
        song.artworkUrl, song.accent,
        modifier.offset(x = size * 0.43f).size(size).alpha(0.35f).paperClip(CircleShape).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        corner = 0.dp,
    )
}

@Composable
private fun NowLines(p: PlayerParts, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val song = p.state.current
    val repository = LocalDesktopContainer.current.lyricsRepository
    val lyrics by produceState<Lyrics?>(null, song.id, song.playbackSource?.providerId) { value = if (song.id.isBlank()) null else repository.lyricsFor(song) }
    val lines = lyrics?.takeIf { it.synced }?.lines.orEmpty()
    val at = lines.indexOfLast { it.startMs >= 0 && it.startMs <= p.state.positionSec * 1000 }
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val size = minOf(maxHeight.value / 4.6f, maxWidth.value / 19f).coerceIn(16f, 38f)
        Column(
            Modifier.paperClip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = PlayerPane.LYRICS.label, role = Role.Button) { p.showPane(PlayerPane.LYRICS, true) }.pointerHoverIcon(PointerIcon.Hand),
        ) {
            if (lines.isEmpty()) Text(PlayerPane.LYRICS.label, style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            else for (step in -1..1) Text(
                lines.getOrNull(at + step)?.displayText.orEmpty(),
                fontSize = size.sp, lineHeight = (size * 1.2f).sp, fontWeight = FontWeight.Black, letterSpacing = (-0.03).em, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = colors.onSurface.copy(alpha = if (step == 0) 1f else if (step < 0) 0.26f else 0.46f), modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun Filmstrip(p: PlayerParts, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val state = p.state
    val player = LocalPlayer.current
    BoxWithConstraints(modifier.fillMaxWidth().drawBehind { drawLine(colors.outlineVariant, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }) {
        val shown = ((maxWidth - 140.dp) / 168.dp).toInt().coerceAtLeast(1)
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(120.dp).paperClip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { p.showPane(PlayerPane.QUEUE, true) }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp),
            ) {
                Text(PlayerPane.QUEUE.label, style = type.headlineSmall, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(appPlural(R.plurals.track_count, (state.queue.size - state.currentIndex - 1).coerceAtLeast(0)), style = type.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            for (step in 1..shown) {
                val index = state.currentIndex + step
                val song = state.queue.getOrNull(index)
                if (song == null) Spacer(Modifier.weight(1f)) else Row(
                    Modifier.weight(1f).paperClip(MaterialTheme.shapes.small).clickable { player.jumpTo(index) }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$step", style = type.headlineMedium, fontWeight = FontWeight.Black, color = colors.onSurfaceVariant)
                    Artwork(song.artworkUrl, song.accent, Modifier.padding(horizontal = 10.dp).size(56.dp).paperClip(CircleShape), corner = 0.dp)
                    Column {
                        Text(song.title, style = type.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatTime(song.durationSec), style = type.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Focus(p: PlayerParts, gutter: Dp) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val state = p.state
    val song = state.current
    Column(Modifier.fillMaxSize().padding(start = gutter, top = 18.dp, end = gutter, bottom = 18.dp)) {
        PlayerPaneSlot(p, p.pane, LocalPlayerPanes.current, { p.showPane(it, true) }, expanded = true, Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.size(56.dp).progressRing(state.progress, colors.primary, colors.outlineVariant, isPaperTheme())) {
                Artwork(song.artworkUrl, song.accent,
                    Modifier.fillMaxSize().padding(5.dp).paperClip(CircleShape).clickable(onClick = p.onTogglePlay).pointerHoverIcon(PointerIcon.Hand), corner = 0.dp)
            }
            Column(Modifier.padding(horizontal = 14.dp).widthIn(max = 300.dp)) { SongTitles(song, type.titleLarge, type.bodyMedium, p.openAlbum, p.openArtist) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            p.transport(Modifier.width(320.dp), colors.primary, colors.onPrimary, paperSquare(CircleShape))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) { p.seek() }
            Spacer(Modifier.width(12.dp))
            p.like()
        }
    }
}
