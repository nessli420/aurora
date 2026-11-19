package com.aurora.music.ui.screens.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.screens.home.DriftCarousel
import com.aurora.music.ui.screens.home.DriftCover
import com.aurora.music.ui.screens.home.DriftGutter
import com.aurora.music.ui.screens.home.DriftTab
import com.aurora.music.ui.screens.home.FanStack
import com.aurora.music.ui.screens.home.IconPill
import com.aurora.music.ui.screens.home.RecentDisc
import com.aurora.music.ui.screens.home.driftType
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor

private const val PREVIEW_SONGS = 4

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchDrift(p: SearchParts) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tablet = LocalWindowLayout.current.useNavigationRail
    val clock = rememberEntrance()
    Column(Modifier.fillMaxSize().padding(top = topInset + 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = DriftGutter), verticalAlignment = Alignment.CenterVertically) {
            val found = if (p.hasResults) "${appString(R.string.text_results_612e12)} · ${p.count(SearchFilter.ALL, p.state.results)}" else ""
            ConsoleLabel(listOf(found, p.source).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            IconPill(p.endIcon, p.endLabel, p.onEnd)
        }
        Headline(p, driftType(MaterialTheme.typography.displayLarge, if (tablet) 1.6f else 1.2f), Modifier.padding(start = DriftGutter, end = DriftGutter, top = 6.dp).rise(clock, 0))
        SearchProgress(p, Modifier.padding(horizontal = DriftGutter))
        // wrapped onto a second line rather than run off the edge
        if (p.hasChoices) FlowRow(
            Modifier.padding(top = 6.dp).rise(clock, 1).fillMaxWidth().padding(horizontal = DriftGutter),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) { p.Choices { label, selected, onClick -> DriftTab(label, selected, onClick = onClick) } }
        SearchPanes(p, Modifier.weight(1f), idle = { Idle(p, it, tablet, clock) }, results = { Results(p, it, tablet, clock) })
    }
}

@Composable
private fun Headline(p: SearchParts, style: TextStyle, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val square = isPaperTheme()
    var focused by remember { mutableStateOf(false) }
    var edge by remember { mutableFloatStateOf(0f) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measured: (TextLayoutResult) -> Unit = { edge = if (rtl) it.getLineLeft(0) else it.getLineRight(0) }
    p.Field(
        style,
        modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .drawBehind {
                val rule = 1.dp.toPx()
                drawLine(colors.outlineVariant, Offset(0f, size.height - rule / 2), Offset(size.width, size.height - rule / 2), rule)
                if (focused) return@drawBehind
                val room = 14.dp.toPx()
                val gap = 6.dp.toPx()
                val line = size.height - 12.dp.toPx()
                val bar = Size(4.dp.toPx(), line * 0.78f)
                val x = if (rtl) (room + edge - gap - bar.width).coerceAtLeast(0f) else edge.coerceAtMost(size.width - room) + gap
                drawRoundRect(colors.primary, Offset(x, (line - bar.height) / 2), bar, if (square) CornerRadius.Zero else CornerRadius(2.dp.toPx()))
            }
            .padding(end = 14.dp, bottom = 12.dp),
        onTextLayout = measured,
    ) {
        Text(appString(R.string.text_search_bce064), style = style, color = colors.onSurfaceVariant.copy(alpha = 0.45f), maxLines = 1, onTextLayout = measured,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Idle(p: SearchParts, shown: SearchPane, tablet: Boolean, clock: Animatable<Float, AnimationVector1D>) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 14.dp, bottom = p.bottom)) {
        if (shown.recents.isNotEmpty()) {
            item(key = "recent") {
                Row(Modifier.rise(clock, 1).padding(start = DriftGutter, end = DriftGutter - 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ConsoleLabel(appString(R.string.text_recent_76eec7), Modifier.weight(1f))
                    Text(appString(R.string.text_clear_719ea3), style = type.labelLarge, color = colors.onSurfaceVariant,
                        modifier = Modifier.paperClip(CircleShape).clickable(role = Role.Button, onClick = p.onClearRecents).padding(10.dp))
                }
            }
            items(shown.recents.size) { i ->
                val query = shown.recents[i]
                val ink = when {
                    i == 0 -> colors.onSurface
                    i < 3 -> colors.onSurfaceVariant
                    else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
                }
                Row(
                    Modifier.rise(clock, 2).padding(start = DriftGutter - 8.dp, end = DriftGutter - 12.dp).fillMaxWidth()
                        .paperClip(MaterialTheme.shapes.small).clickable { p.onRecent(query) }.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(query, style = driftType(type.headlineLarge, 1.1f), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 3.dp))
                    Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { p.onRemoveRecent(query) }.padding(12.dp))
                }
            }
        }
        if (p.jumpBackIn.isNotEmpty()) item(key = "jump") {
            Column(Modifier.rise(clock, 2).padding(top = if (shown.recents.isEmpty()) 0.dp else 24.dp)) {
                ConsoleLabel(appString(R.string.text_jump_back_in_1d9181), Modifier.padding(horizontal = DriftGutter))
                LazyRow(contentPadding = PaddingValues(start = DriftGutter - 4.dp, end = DriftGutter - 4.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(p.jumpBackIn.size) { i ->
                        val album = p.jumpBackIn[i]
                        RecentDisc(album, false, if (tablet) 112.dp else 80.dp) { p.onOpen("album", album.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Results(p: SearchParts, shown: SearchPane, tablet: Boolean, clock: Animatable<Float, AnimationVector1D>) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val found = shown.state.results
    val filter = p.filterFor(found)
    val all = filter == SearchFilter.ALL
    val side = Modifier.padding(horizontal = DriftGutter - 8.dp)
    // the padding is the room a cover's shadow needs inside the clip
    @Composable
    fun tile(cell: Modifier, onClick: () -> Unit) = cell.paperClip(RoundedCornerShape(14.dp)).pressClickable(onClick = onClick).padding(8.dp)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val cover = (if (tablet) 260.dp else 208.dp).coerceAtMost(width * 0.56f)
        val columns = if (tablet) (width / 220.dp).toInt().coerceIn(2, 6) else 2
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = p.bottom)) {
            if (all && shown.hits.isNotEmpty()) item(key = "top") {
                Column(Modifier.padding(bottom = 14.dp)) { DriftCarousel(shown.hits, width, cover, if (tablet) 24.dp else 6.dp, clock, onOpen = p::openHit, onPlay = p::playHit) }
            }
            if ((all || filter == SearchFilter.SONGS) && found.songs.isNotEmpty()) {
                items(if (all) minOf(found.songs.size, PREVIEW_SONGS) else found.songs.size) { i ->
                    p.SongItem(found.songs, i, side, showArt = false, titleStyle = type.titleMedium.copy(fontWeight = FontWeight.Bold), lead = {
                        Text((i + 1).toString().padStart(2, '0'), style = driftType(type.headlineLarge), color = colors.onSurfaceVariant.copy(alpha = 0.5f), maxLines = 1,
                            modifier = Modifier.width(50.dp))
                    })
                }
            }
            if (filter == SearchFilter.ALBUMS) gridRows(found.albums, columns, side, 0.dp) { album, cell ->
                Column(tile(cell) { p.onOpen("album", album.id) }) {
                    DriftCover(album.artworkUrl, accentFor(album.id), MaterialTheme.shapes.extraLarge, Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(10.dp))
                    Text(album.title, style = type.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artist, style = type.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (filter == SearchFilter.ARTISTS) gridRows(found.artists, columns, side, 0.dp) { artist, cell ->
                Column(tile(cell) { p.onOpen("artist", artist.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(artist.imageUrl, accentFor(artist.id), Modifier.fillMaxWidth().aspectRatio(1f), corner = 500.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(artist.name, style = type.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
            if (filter == SearchFilter.PLAYLISTS) items(found.playlists.size) { i ->
                val playlist = found.playlists[i]
                Row(
                    side.fillMaxWidth().paperClip(RoundedCornerShape(14.dp)).pressClickable { p.onOpen("playlist", playlist.id) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FanStack(playlist.coverUrl, playlist.accent, 60.dp, clock)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.title, style = type.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(playlist.subtitle, style = type.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
