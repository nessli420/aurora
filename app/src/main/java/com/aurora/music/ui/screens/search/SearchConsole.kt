package com.aurora.music.ui.screens.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.SearchResults
import com.aurora.music.localization.appString
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.FormatChip
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.screens.home.ConsoleModule
import com.aurora.music.ui.screens.home.Hero
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.glassCard
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor
import java.util.Locale

private val Gap = 10.dp
private const val PREVIEW = 4

@Composable
internal fun SearchConsole(p: SearchParts) {
    val colors = MaterialTheme.colorScheme
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tablet = LocalWindowLayout.current.useNavigationRail
    val gutter = if (tablet) 24.dp else 16.dp
    val field = MaterialTheme.shapes.medium
    val clock = rememberEntrance()
    Column(Modifier.fillMaxSize().padding(top = topInset + 8.dp)) {
        Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(Gap)) {
            Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                ConsoleLabel(appString(R.string.text_search_bce064), Modifier.weight(1f))
                ConsoleLabel(if (p.hasResults) counted(appString(R.string.text_results_612e12), p.count(SearchFilter.ALL, p.state.results)) else p.source)
            }
            Row(
                Modifier.fillMaxWidth().height(56.dp).searchCard(field, outlined = true)
                    .then(if (p.hasQuery) Modifier.border(1.dp, colors.primary, field) else Modifier).padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                p.Field(style, Modifier.weight(1f)) {
                    Text(appString(R.string.text_artists_albums_or_songs_d1863c), style = style, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(40.dp).keycap().clickable(role = Role.Button, onClick = p.onEnd), contentAlignment = Alignment.Center) {
                    Icon(p.endIcon, p.endLabel, tint = colors.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            if (p.state.sources.size > 1) Sources(p)
            SearchProgress(p)
        }
        SearchPanes(p, Modifier.weight(1f), idle = { Idle(p, it, gutter, clock) }, results = { Results(p, it, gutter, tablet, clock) })
    }
}

private fun counted(label: String, count: Int) = "$label · $count"

@Composable
private fun Modifier.keycap(): Modifier {
    val shape = MaterialTheme.shapes.small
    return if (isGlassTheme()) glassCard(shape) else paperFrame(shape, { background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)) })
}

@Composable
private fun Modifier.rule(): Modifier {
    val line = MaterialTheme.colorScheme.outlineVariant
    return drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
}

@Composable
private fun Module(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    padding: PaddingValues = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!isGlassTheme()) return ConsoleModule(modifier, tint, padding, onClick, content)
    Column(modifier.glassCard(MaterialTheme.shapes.large, tint).then(if (onClick != null) Modifier.pressClickable(onClick = onClick) else Modifier).padding(padding), content = content)
}

@Composable
private fun ModuleLabel(text: String, modifier: Modifier = Modifier) =
    Row(modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) { ConsoleLabel(text) }

@Composable
private fun Chip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1, softWrap = false,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.35f), paperSquare(MaterialTheme.shapes.extraSmall)).padding(horizontal = 7.dp, vertical = 3.dp))

@Composable
private fun Sources(p: SearchParts) {
    val colors = MaterialTheme.colorScheme
    val sources = p.state.sources
    val shape = MaterialTheme.shapes.small
    // four keys share a row; a fifth starts the next one, so none runs off the edge
    val rows = sources.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { source ->
                    val on = source.id == p.state.selectedSource
                    // the dot sits over the name, so a two-word source keeps its whole name on a phone
                    Column(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 46.dp).searchCard(shape, outlined = true)
                            .then(if (on) Modifier.border(1.dp, colors.primary, shape) else Modifier)
                            .selectable(selected = on, role = Role.RadioButton) { p.onSelectSource(source.id) }.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(7.dp).paperClip(CircleShape).background(if (on) colors.primary else colors.outline))
                        Text(source.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (on) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                if (rows.size > 1) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Idle(p: SearchParts, shown: SearchPane, gutter: Dp, clock: Animatable<Float, AnimationVector1D>) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, top = Gap, bottom = p.bottom),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
        if (shown.recents.isNotEmpty()) item(key = "recent") {
            Module(Modifier.fillMaxWidth().rise(clock, 0), padding = PaddingValues(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
                Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    ConsoleLabel(counted(appString(R.string.text_recent_76eec7), shown.recents.size), Modifier.weight(1f))
                    Text(appString(R.string.text_clear_719ea3).uppercase(Locale.getDefault()), style = type.labelSmall, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.padding(end = 2.dp).keycap().clickable(role = Role.Button, onClick = p.onClearRecents).padding(horizontal = 10.dp, vertical = 9.dp))
                }
                shown.recents.forEach { query ->
                    Row(Modifier.fillMaxWidth().rule().clip(MaterialTheme.shapes.small).clickable { p.onRecent(query) }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.History, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(query, style = type.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { p.onRemoveRecent(query) }.padding(12.dp))
                    }
                }
            }
        }
        if (p.jumpBackIn.isNotEmpty()) item(key = "jump") {
            Module(Modifier.fillMaxWidth().rise(clock, 1)) {
                ModuleLabel(appString(R.string.text_jump_back_in_1d9181))
                p.jumpBackIn.take(PREVIEW).forEach { album ->
                    MediaRow(album.title, album.artist, album.artworkUrl, accentFor(album.id), album.typeLabel.localizedMediaType()) { p.onOpen("album", album.id) }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(title: String, subtitle: String, art: String, accent: Color, chip: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().rule().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = 54.dp).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, accent, Modifier.size(40.dp), corner = 9.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (chip.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Chip(chip)
        }
    }
}

@Composable
private fun Results(p: SearchParts, shown: SearchPane, gutter: Dp, tablet: Boolean, clock: Animatable<Float, AnimationVector1D>) {
    val colors = MaterialTheme.colorScheme
    val found = shown.state.results
    val filter = p.filterFor(found)
    val all = filter == SearchFilter.ALL
    val below = Modifier.padding(bottom = Gap)
    Column(Modifier.fillMaxSize()) {
        Tabs(p, found, filter, Modifier.padding(start = gutter, end = gutter, top = Gap))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, top = Gap, bottom = p.bottom)) {
            val hit = shown.hits.firstOrNull().takeIf { all }
            if (hit != null) item(key = "top") { TopModule(hit, below.rise(clock, 0), onOpen = { p.openHit(hit) }, onPlay = { p.playHit(hit) }) }
            if ((all || filter == SearchFilter.SONGS) && found.songs.isNotEmpty()) {
                val label = counted(SearchFilter.SONGS.label, found.songs.size)
                if (all) item(key = "songs") {
                    Module(below.fillMaxWidth(), padding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
                        Row(Modifier.heightIn(min = 44.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ConsoleLabel(label, Modifier.weight(1f))
                            Icon(Icons.Filled.OpenInFull, appString(R.string.text_see_all_2941c5), tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(role = Role.Button) { p.onFilter(SearchFilter.SONGS) }.padding(11.dp))
                        }
                        repeat(minOf(found.songs.size, PREVIEW)) { i -> SongLine(p, found.songs, i) }
                    }
                } else {
                    item(key = "songs") { ModuleLabel(label, Modifier.padding(horizontal = 2.dp)) }
                    items(found.songs.size) { i -> SongLine(p, found.songs, i) }
                }
            }
            if ((all || filter == SearchFilter.ALBUMS) && found.albums.isNotEmpty()) {
                val label = counted(SearchFilter.ALBUMS.label, found.albums.size)
                if (all) item(key = "albums") {
                    Module(below.fillMaxWidth(), padding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)) {
                        ModuleLabel(label)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            found.albums.take(PREVIEW).chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { album -> AlbumCell(album, Modifier.weight(1f)) { p.onOpen("album", album.id) } }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    item(key = "albums") { ModuleLabel(label, Modifier.padding(horizontal = 2.dp)) }
                    gridRows(found.albums, if (tablet) 4 else 2, Modifier.padding(bottom = 8.dp), 8.dp) { album, cell -> AlbumCell(album, cell) { p.onOpen("album", album.id) } }
                }
            }
            if (filter == SearchFilter.ARTISTS) {
                item(key = "artists") { ModuleLabel(counted(SearchFilter.ARTISTS.label, found.artists.size), Modifier.padding(horizontal = 2.dp)) }
                gridRows(found.artists, if (tablet) 8 else 4, Modifier.padding(bottom = 12.dp), 8.dp) { artist, cell ->
                    Column(cell.paperClip(MaterialTheme.shapes.small).pressClickable { p.onOpen("artist", artist.id) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(artist.imageUrl, accentFor(artist.id), Modifier.fillMaxWidth().aspectRatio(1f), corner = 500.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(artist.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
            if (filter == SearchFilter.PLAYLISTS) {
                item(key = "playlists") { ModuleLabel(counted(SearchFilter.PLAYLISTS.label, found.playlists.size), Modifier.padding(horizontal = 2.dp)) }
                items(found.playlists.size) { i ->
                    val playlist = found.playlists[i]
                    MediaRow(playlist.title, playlist.subtitle, playlist.coverUrl, playlist.accent) { p.onOpen("playlist", playlist.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tabs(p: SearchParts, found: SearchResults, selected: SearchFilter, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    // the count sits under its kind so five tabs fit a phone; a longer language wraps rather than run off the edge
    FlowRow(
        modifier.fillMaxWidth().searchCard(MaterialTheme.shapes.medium, outlined = true).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        p.filters(found).forEach { filter ->
            val on = filter == selected
            val mark by animateFloatAsState(if (on) 1f else 0f, Motion.state(), label = "searchTab")
            Column(
                Modifier.height(46.dp)
                    .drawBehind { drawRect(colors.primary, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()), alpha = mark) }
                    .selectable(selected = on, role = Role.Tab) { p.onFilter(filter) }.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(filter.label.uppercase(Locale.getDefault()), style = type.labelSmall, fontWeight = FontWeight.Bold, color = if (on) colors.onSurface else colors.onSurfaceVariant, maxLines = 1)
                Text(p.count(filter, found).toString(), style = type.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TopModule(hit: Hero, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val soft = colors.onSurface.copy(alpha = 0.85f)
    Module(modifier.fillMaxWidth(), tint = spotlightTint(hit.art, hit.accent), padding = PaddingValues(14.dp), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(hit.art, hit.accent, Modifier.size(72.dp), corner = if (hit.kind == "artist") 72.dp else 16.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow(appString(R.string.search_top_result).uppercase(Locale.getDefault()), soft, maxLines = 1)
                Text(hit.title, style = type.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(hit.label, soft)
                    val line = hit.subtitle.ifBlank { hit.meta }
                    if (line.isNotBlank()) Text(line, style = type.bodySmall, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(52.dp, 46.dp).paperFrame(MaterialTheme.shapes.small, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                    .pressClickable(role = Role.Button, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun SongLine(p: SearchParts, songs: List<Song>, index: Int) {
    val song = songs[index]
    p.SongItem(songs, index, Modifier.rule(),
        lead = {
            Text((index + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.width(28.dp))
        },
        tag = if (song.suffix.isNotBlank() || song.sampleRateHz > 0 || song.bitDepth > 0) ({ FormatChip(song) }) else null)
}

@Composable
private fun AlbumCell(album: Album, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).searchCard(MaterialTheme.shapes.small).pressClickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(40.dp), corner = 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(album.artist, album.year.takeIf { it > 0 }?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
