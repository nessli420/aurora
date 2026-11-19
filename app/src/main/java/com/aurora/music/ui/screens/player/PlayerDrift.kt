package com.aurora.music.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.progressRing
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion

private val Round = 1000.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DriftPlayer(p: PlayerParts, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val state = p.state
    val song = state.current
    val now = p.view == StyledView.NOW
    Column(modifier.flatStage().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
        AnimatedContent(
            targetState = p.view,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            label = "driftView",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { view ->
            Column(Modifier.fillMaxSize()) {
                when (view) {
                    StyledView.NOW -> {
                        Column(Modifier.padding(horizontal = 20.dp)) { p.header() }
                        DriftCover(p, Modifier.weight(1f).fillMaxWidth())
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Column(Modifier.weight(1f)) {
                                    SongTitles(song, MaterialTheme.typography.displayLarge, MaterialTheme.typography.titleMedium, p.openAlbum, p.openArtist,
                                        titleWeight = MaterialTheme.typography.displayLarge.fontWeight, gap = 4.dp)
                                    BpmLine(state)
                                }
                                PlayerLike(state, p.onToggleLike, glyph = 28.dp)
                            }
                            val open = { p.onView(StyledView.LYRICS) }
                            if (rememberLyrics(song)?.lines?.isNotEmpty() == true) LyricsPane(state, p.onSeek,
                                Modifier.fillMaxWidth().height(44.dp), ink = colors.primary, onGlance = open)
                            else Text(
                                appString(R.string.text_lyrics_8670cb),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(MaterialTheme.shapes.small)
                                    .clickable(onClickLabel = appString(R.string.text_lyrics_8670cb), role = Role.Button, onClick = open)
                                    .wrapContentHeight(),
                            )
                            p.chips()
                        }
                    }
                    StyledView.LYRICS -> {
                        DriftTop(p, track = true) { LyricsTranslateKey() }
                        LyricsPane(state, p.onSeek, Modifier.weight(1f).fillMaxWidth(), compact = false, ink = colors.onSurface)
                    }
                    StyledView.QUEUE -> {
                        DriftTop(p, track = false) { p.queueActions() }
                        Text(appString(R.string.text_queue_d325fc), style = MaterialTheme.typography.displayLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                        p.queue(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            p.seek(!now, colors.primary)
            Spacer(Modifier.height(8.dp))
            val play by animateDpAsState(if (now) 72.dp else 56.dp, Motion.state(), label = "driftPlay")
            p.transport(Modifier.size(play).clip(playShape()), colors.primary, colors.onPrimary, true)
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(now) {
                Column {
                    p.utilities()
                    if (p.hasQueue) UpNextStrip(p)
                }
            }
        }
    }
}

@Composable
private fun DriftCover(p: PlayerParts, modifier: Modifier) {
    val state = p.state
    if (p.wideArt) {
        p.artwork(modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        return
    }
    val scale = LocalUiPrefs.current.playerArtSize.coerceIn(0.5f, 1f)
    BoxWithConstraints(modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        val ring = minOf(maxWidth - 96.dp, maxHeight).coerceAtLeast(0.dp) * scale
        val peek = ring * 0.53f
        state.queue.getOrNull(state.currentIndex - 1)?.let { Peek(it, Modifier.align(Alignment.CenterStart).offset(x = -peek * 0.56f).size(peek)) }
        state.queue.getOrNull(state.currentIndex + 1)?.let { Peek(it, Modifier.align(Alignment.CenterEnd).offset(x = peek * 0.56f).size(peek)) }
        Box(Modifier.size(ring).progressRing(state, 5.dp, MaterialTheme.colorScheme.primary).padding(10.dp)) { p.artwork(Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Peek(song: Song, modifier: Modifier) = Artwork(song.artworkUrl, song.accent, modifier.alpha(0.4f), corner = Round, decodeSize = 256)

@Composable
private fun DriftTop(p: PlayerParts, track: Boolean, keys: @Composable () -> Unit) {
    val state = p.state
    NowStrip(
        p, Modifier.padding(start = 22.dp, end = 12.dp, top = 8.dp, bottom = 16.dp), track,
        cover = {
            Box(Modifier.size(52.dp).progressRing(state, 3.dp, MaterialTheme.colorScheme.primary).padding(5.dp)) {
                Artwork(state.current.artworkUrl, state.current.accent, Modifier.fillMaxSize(), corner = Round)
            }
        },
    ) {
        keys()
        PlayerKey(Icons.Filled.Close, appString(R.string.text_close_bbfa77)) { p.onView(StyledView.NOW) }
    }
}

@Composable
private fun UpNextStrip(p: PlayerParts) {
    val state = p.state
    val colors = MaterialTheme.colorScheme
    val next = (state.currentIndex + 1).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().height(56.dp).drawBehind { drawRect(colors.outlineVariant, size = Size(size.width, 1.dp.toPx())) }
            .clickable(onClickLabel = appString(R.string.text_view_queue_827a90), role = Role.Button) { p.onView(StyledView.QUEUE) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(appString(R.string.text_queue_d325fc), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        for (i in next until minOf(next + 5, state.queue.size)) {
            Artwork(state.queue[i].artworkUrl, state.queue[i].accent, Modifier.size(34.dp), corner = Round, decodeSize = 96)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
