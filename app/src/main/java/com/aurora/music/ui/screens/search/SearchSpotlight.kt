package com.aurora.music.ui.screens.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.model.Playlist
import com.aurora.music.model.accent
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.screens.home.Hero
import com.aurora.music.ui.screens.home.IconPill
import com.aurora.music.ui.screens.home.QuickGrid
import com.aurora.music.ui.screens.home.SpotlightPill
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.glassCard
import com.aurora.music.ui.theme.glassChip
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.MotionTokens
import com.aurora.music.util.accentFor
import java.util.Locale

private const val PREVIEW_SONGS = 4

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchSpotlight(p: SearchParts) {
    val colors = MaterialTheme.colorScheme
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tablet = LocalWindowLayout.current.useNavigationRail
    val gutter = if (tablet) 24.dp else 20.dp
    val glass = isGlassTheme()
    val hit = p.pane.hits.firstOrNull()
    val tint = animateColorAsState(if (hit == null) colors.surfaceContainerHigh else spotlightTint(hit.art, hit.accent), Motion.state(MotionTokens.SLOW), label = "searchTint")
    val block = MaterialTheme.shapes.extraLarge.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
    val clock = rememberEntrance()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth()
                // a tablet keeps the page behind the field, as home keeps it behind the greeting
                .then(when {
                    tablet -> Modifier
                    glass -> Modifier.glassCard(block, tint.value)
                    else -> Modifier.clip(block).drawBehind { drawRect(tint.value) }
                })
                .padding(top = topInset + 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow(listOf(appString(R.string.text_search_bce064), p.source).filter { it.isNotBlank() }.joinToString(" · ").uppercase(Locale.getDefault()),
                colors.onSurface.copy(alpha = 0.85f), Modifier.padding(horizontal = gutter), maxLines = 1)
            Row(
                Modifier.padding(horizontal = gutter).widthIn(max = 720.dp).fillMaxWidth().height(56.dp)
                    .then(if (glass) Modifier.glassCard(CircleShape) else Modifier.paperFrame(CircleShape, { background(colors.onSurface.copy(alpha = 0.12f)) }))
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                p.Field(style, Modifier.weight(1f)) {
                    Text(appString(R.string.text_artists_albums_or_songs_d1863c), style = style, color = colors.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                IconPill(p.endIcon, p.endLabel, p.onEnd)
            }
            // centred, and wrapped onto a second line rather than run off the edge
            if (p.hasChoices) FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { p.Choices { label, selected, onClick -> Pill(label, selected, onClick) } }
        }
        SearchProgress(p)
        SearchPanes(p, Modifier.weight(1f), idle = { Idle(p, it, gutter, tablet, clock) }, results = { Results(p, it, gutter, tablet, clock) })
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    if (!isGlassTheme()) return SpotlightPill(label, selected, onClick = onClick)
    val colors = MaterialTheme.colorScheme
    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (selected) colors.onPrimary else colors.onSurface, maxLines = 1,
        modifier = Modifier.glassChip(selected).selectable(selected = selected, role = Role.Tab, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Idle(p: SearchParts, shown: SearchPane, gutter: Dp, tablet: Boolean, clock: Animatable<Float, AnimationVector1D>) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = p.bottom), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        if (shown.recents.isNotEmpty()) item(key = "recent") {
            Column(Modifier.rise(clock, 1).padding(horizontal = gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(appString(R.string.text_recent_76eec7), Modifier.weight(1f))
                    Text(appString(R.string.text_clear_719ea3), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                        modifier = Modifier.paperClip(CircleShape).clickable(role = Role.Button, onClick = p.onClearRecents).padding(horizontal = 10.dp, vertical = 10.dp))
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.recents.forEach { query -> RecentPill(query, onClick = { p.onRecent(query) }, onRemove = { p.onRemoveRecent(query) }) }
                }
            }
        }
        if (p.jumpBackIn.isNotEmpty()) item(key = "jump") {
            Column(Modifier.rise(clock, 2).padding(horizontal = gutter)) {
                SectionHeader(appString(R.string.text_jump_back_in_1d9181))
                Spacer(Modifier.height(14.dp))
                QuickGrid(p.jumpBackIn.take(6), if (tablet) 3 else 2, if (tablet) 72.dp else 58.dp, p.onOpen)
            }
        }
    }
}

@Composable
private fun RecentPill(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.height(44.dp).searchCard(paperSquare(CircleShape)).clickable(onClick = onClick).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.History, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(query, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = colors.onSurfaceVariant,
            modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onRemove).padding(12.dp))
    }
}

@Composable
private fun Results(p: SearchParts, shown: SearchPane, gutter: Dp, tablet: Boolean, clock: Animatable<Float, AnimationVector1D>) {
    val colors = MaterialTheme.colorScheme
    val found = shown.state.results
    val filter = p.filterFor(found)
    val all = filter == SearchFilter.ALL
    val heading = Modifier.padding(start = gutter, end = gutter, top = 20.dp, bottom = 8.dp)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (tablet) ((maxWidth - gutter * 2) / 180.dp).toInt().coerceIn(3, 8) else 2
        val side = gutter - 8.dp
        val tile = (maxWidth - side * 2) / columns
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = p.bottom)) {
            val hit = shown.hits.firstOrNull().takeIf { all }
            if (hit != null) item(key = "top") {
                TopCard(hit, Modifier.rise(clock, 1).padding(start = gutter, end = gutter, top = 14.dp), onOpen = { p.openHit(hit) }, onPlay = { p.playHit(hit) })
            }
            if ((all || filter == SearchFilter.SONGS) && found.songs.isNotEmpty()) {
                item(key = "songs") {
                    SectionHeader(SearchFilter.SONGS.label, heading, action = appString(R.string.text_see_all_2941c5).takeIf { all }, onAction = { p.onFilter(SearchFilter.SONGS) })
                }
                items(if (all) minOf(found.songs.size, PREVIEW_SONGS) else found.songs.size) { i ->
                    p.SongItem(found.songs, i, Modifier.padding(horizontal = side), lead = {
                        Text((i + 1).toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(end = 10.dp).widthIn(min = 22.dp))
                    })
                }
            }
            if ((all || filter == SearchFilter.ALBUMS) && found.albums.isNotEmpty()) {
                item(key = "albums") { SectionHeader(SearchFilter.ALBUMS.label, heading) }
                if (all) item(key = "album-row") {
                    LazyRow(contentPadding = PaddingValues(horizontal = side)) {
                        items(found.albums.size) { i -> AlbumCard(found.albums[i], onClick = { p.onOpen("album", found.albums[i].id) }, width = 148.dp) }
                    }
                } else gridRows(found.albums, columns, Modifier.padding(horizontal = side), 0.dp) { album, cell ->
                    AlbumCard(album, onClick = { p.onOpen("album", album.id) }, modifier = cell, width = tile)
                }
            }
            if (filter == SearchFilter.ARTISTS) {
                item(key = "artists") { SectionHeader(SearchFilter.ARTISTS.label, heading) }
                gridRows(found.artists, if (tablet) columns else 3, Modifier.padding(horizontal = side), 0.dp) { artist, cell ->
                    Column(cell.paperClip(MaterialTheme.shapes.medium).pressClickable { p.onOpen("artist", artist.id) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(artist.imageUrl, accentFor(artist.id), Modifier.fillMaxWidth().aspectRatio(1f), corner = 500.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(artist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
            if (filter == SearchFilter.PLAYLISTS) {
                item(key = "playlists") { SectionHeader(SearchFilter.PLAYLISTS.label, heading) }
                items(found.playlists.size) { i ->
                    val playlist = found.playlists[i]
                    PlaylistRow(playlist, Modifier.padding(horizontal = gutter, vertical = 5.dp)) { p.onOpen("playlist", playlist.id) }
                }
            }
        }
    }
}

@Composable
private fun TopCard(hit: Hero, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val soft = colors.onSurface.copy(alpha = 0.85f)
    Row(
        modifier.fillMaxWidth().searchCard(MaterialTheme.shapes.large, spotlightTint(hit.art, hit.accent)).pressClickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(hit.art, hit.accent, Modifier.size(88.dp), corner = if (hit.kind == "artist") 88.dp else 16.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Eyebrow("${appString(R.string.search_top_result).uppercase(Locale.getDefault())} · ${hit.label}", soft, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(hit.title, style = type.headlineMedium, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = listOf(hit.subtitle, hit.meta).filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = type.bodySmall, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(52.dp).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose).pressClickable(role = Role.Button, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(72.dp).searchCard(MaterialTheme.shapes.medium, spotlightTint(playlist.coverUrl, playlist.accent)).pressClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(playlist.coverUrl, playlist.accent, Modifier.size(72.dp), corner = 0.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playlist.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
