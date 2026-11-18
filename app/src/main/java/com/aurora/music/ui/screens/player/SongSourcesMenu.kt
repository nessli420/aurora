package com.aurora.music.ui.screens.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Album as AlbumOutlined
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.aurora.music.R
import com.aurora.music.ai.isDjSpeech
import com.aurora.music.data.ServerType
import com.aurora.music.data.SongLink
import com.aurora.music.data.SourceCopy
import com.aurora.music.data.isPodcast
import com.aurora.music.data.isRadio
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.ui.components.SongMenu
import com.aurora.music.ui.components.SongMenuSection
import com.aurora.music.util.TrackMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// what the title and artist menus can do with the playing song; the app supplies it
class SongSources(
    val copies: suspend (Song) -> List<SourceCopy>,
    val releaseType: suspend (Song) -> String?,
    val canRematch: (Song) -> Boolean,
    val playFrom: (Song) -> Unit,
    val rematch: (Song) -> Unit,
    val open: (kind: String, id: String) -> Unit,
    val manySources: () -> Boolean,
    val linksSongs: () -> Boolean,
    val links: suspend (Song) -> List<SongLink>,
    val copyLink: (SongLink) -> Unit,
)

suspend fun SongSources.lookup(song: Song): List<SourceCopy> =
    try { copies(song) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }

val LocalSongSources = staticCompositionLocalOf<SongSources?> { null }

// the app's own colours under the player's white canvas tint, so a menu over it stays readable
internal val LocalUntintedColors = staticCompositionLocalOf<ColorScheme?> { null }

enum class SongMenuPage { ROOT, SOURCE, ALBUM, ARTIST, LINK }

fun songMenusFor(song: Song): Boolean = song.id.isNotBlank() && !song.isRadio() && !song.isPodcast() && !song.isDjSpeech() &&
    !song.id.startsWith("aurora-mix:")

// a few tracks stand in for an album or artist page on the other sources, as one title can be spelled differently there
fun sourcesAnchors(tracks: List<Song>, album: Boolean, artist: String): List<Song> =
    (if (album) tracks.filter { it.albumId.isNotBlank() }
    else tracks.filter { it.artistId.isNotBlank() && TrackMatch.identity(it.artist) == TrackMatch.identity(artist) }).take(3)

private fun SourceCopy.opens(page: SongMenuPage) = (if (page == SongMenuPage.ALBUM) song.albumId else song.artistId).isNotBlank()

@Composable
internal fun SongSourcesMenu(song: Song, sources: SongSources, start: SongMenuPage, found: List<SourceCopy>? = null, here: Boolean = false, onDismiss: () -> Unit) =
    MaterialTheme(colorScheme = LocalUntintedColors.current ?: MaterialTheme.colorScheme) { SourcesMenu(song, sources, start, found, here, onDismiss) }

// here: opened on the album or artist page itself, which looks the copies up and is marked among them
@Composable
private fun SourcesMenu(song: Song, sources: SongSources, start: SongMenuPage, found: List<SourceCopy>?, here: Boolean, onDismiss: () -> Unit) {
    var page by remember(song.id) { mutableStateOf(start) }
    var looked by remember(song.id) { mutableStateOf<List<SourceCopy>?>(null) }
    var release by remember(song.id) { mutableStateOf<String?>(null) }
    var links by remember(song.id) { mutableStateOf<List<SongLink>?>(null) }
    if (start == SongMenuPage.LINK) LaunchedEffect(song.id) {
        val found = try { sources.links(song) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        links = found
        found.singleOrNull()?.let { sources.copyLink(it); onDismiss() }
    } else if (!here) LaunchedEffect(song.id) { looked = sources.lookup(song) }
    val copies = if (here) found else looked
    if (start == SongMenuPage.ROOT && song.albumId.isNotBlank()) LaunchedEffect(song.albumId) {
        release = try { sources.releaseType(song) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val colors = MaterialTheme.colorScheme
    val openRelease = appString(when (release?.lowercase()) {
        "single" -> R.string.song_menu_open_single
        "ep" -> R.string.song_menu_open_ep
        else -> R.string.song_menu_open_album
    })

    @Composable
    fun header(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

    // one menu row tall, so the menu keeps its height when the first result lands
    @Composable
    fun loading() = Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).size(width = 160.dp, height = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }

    @Composable
    fun noMatch() = Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        Text(appString(R.string.like_no_match), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }

    @Composable
    fun choices(keep: (SourceCopy) -> Boolean, row: @Composable (SourceCopy) -> Unit) {
        val list = copies
        when {
            list == null -> loading()
            list.none(keep) -> noMatch()
            else -> list.filter(keep).forEach { row(it) }
        }
    }

    @Composable
    fun label(copy: SourceCopy, note: String?) = Column {
        Text(copy.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }

    SongMenu(expanded = true, onDismissRequest = onDismiss) {
        key(page) { SongMenuSection(0) { when (page) {
            SongMenuPage.ROOT -> {
                DropdownMenuItem(
                    text = { Text(appString(R.string.song_menu_source)) },
                    leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    onClick = { page = SongMenuPage.SOURCE },
                )
                DropdownMenuItem(
                    text = { Text(openRelease) },
                    leadingIcon = { Icon(Icons.Filled.Album, null) },
                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    onClick = { page = SongMenuPage.ALBUM },
                )
            }
            SongMenuPage.SOURCE -> {
                header(appString(R.string.song_menu_play_from))
                choices({ it.song.streamUrl.isNotBlank() }) { copy ->
                    DropdownMenuItem(
                        text = { label(copy, if (copy.playing) appString(R.string.like_now_playing) else "${copy.song.title} · ${copy.song.artist}") },
                        leadingIcon = {
                            Icon(if (copy.playing) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null,
                                tint = if (copy.playing) colors.primary else colors.onSurfaceVariant)
                        },
                        onClick = { onDismiss(); if (!copy.playing) sources.playFrom(copy.song) },
                    )
                }
                if (sources.canRematch(song)) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = colors.outlineVariant.copy(alpha = 0.6f))
                    DropdownMenuItem(
                        text = { Text(appString(R.string.song_menu_rematch)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        onClick = { onDismiss(); sources.rematch(song) },
                    )
                }
            }
            SongMenuPage.ALBUM, SongMenuPage.ARTIST -> {
                val album = page == SongMenuPage.ALBUM
                header(appString(R.string.song_menu_open_in))
                choices({ it.opens(page) }) { copy ->
                    val current = here && copy.song.id == song.id
                    DropdownMenuItem(
                        text = { label(copy, if (album) copy.song.album.ifBlank { null } else copy.song.artist.ifBlank { null }) },
                        leadingIcon = {
                            if (!here) Icon(if (album) Icons.Filled.Album else Icons.Filled.Person, null)
                            else Icon(if (current) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, null,
                                tint = if (current) colors.primary else colors.onSurfaceVariant)
                        },
                        onClick = {
                            onDismiss()
                            if (!current) if (album) sources.open("album", copy.song.albumId) else sources.open("artist", copy.song.artistId)
                        },
                    )
                }
                if (here && copies?.count { it.opens(page) } == 1) noMatch()
            }
            SongMenuPage.LINK -> {
                header(appString(R.string.song_menu_copy_link_from))
                val list = links
                when {
                    list == null -> loading()
                    list.isEmpty() -> noMatch()
                    else -> list.forEach { link ->
                        DropdownMenuItem(
                            text = { Text(link.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(linkIcon(link.type), null) },
                            onClick = { sources.copyLink(link); onDismiss() },
                        )
                    }
                }
            }
        } } }
    }
}

// holding an album or artist title opens the same one on another source, found through one of its tracks
@Composable
fun SourcesTitle(anchors: List<Song>, album: Boolean, title: @Composable (Modifier) -> Unit) {
    val anchor = anchors.firstOrNull()
    val sources = LocalSongSources.current?.takeIf { anchor != null && songMenusFor(anchor) && it.manySources() }
    if (sources == null || anchor == null) return title(Modifier)
    val page = if (album) SongMenuPage.ALBUM else SongMenuPage.ARTIST
    var looking by remember(anchor.id) { mutableStateOf(false) }
    var found by remember(anchor.id) { mutableStateOf<List<SourceCopy>?>(null) }
    var open by remember(anchor.id) { mutableStateOf(false) }
    if (looking) LaunchedEffect(anchor.id) {
        found = coroutineScope { anchors.map { async { sources.lookup(it) } }.awaitAll() }.flatten().filter { it.opens(page) }.distinctBy { it.label }
    }
    val label = appString(R.string.song_menu_open_elsewhere)
    val haptics = LocalHapticFeedback.current
    // the lookup starts on touch, so a hold that already knows of no other source does nothing
    fun hold(): Boolean {
        looking = true
        if (found?.let { list -> list.count { it.opens(page) } < 2 } == true) return false
        open = true
        return true
    }
    Box {
        title(Modifier
            .semantics { onLongClick(label) { hold() } }
            .pointerInput(anchor.id) {
                detectTapGestures(onPress = { looking = true }, onLongPress = { if (hold()) haptics.performHapticFeedback(HapticFeedbackType.LongPress) })
            })
        if (open) SongSourcesMenu(anchor, sources, page, found, here = true) { open = false }
    }
}

@Composable
fun CopyLinkMenuItem(song: Song, onClick: () -> Unit) {
    if (LocalSongSources.current?.takeIf { songMenusFor(song) && it.linksSongs() } == null) return
    DropdownMenuItem(
        text = { Text(appString(R.string.song_menu_copy_link)) },
        onClick = onClick,
        leadingIcon = { Icon(Icons.Filled.Link, null) },
    )
}

// the playing song's other sources and its match, as holding its title offers them
@Composable
fun PlaybackSourceMenuItems(song: Song, pick: () -> Unit, close: () -> Unit) {
    val sources = LocalSongSources.current?.takeIf { songMenusFor(song) } ?: return
    if (sources.manySources()) DropdownMenuItem(
        text = { Text(appString(R.string.song_menu_source)) },
        leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
        trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        onClick = pick,
    )
    if (sources.canRematch(song)) DropdownMenuItem(
        text = { Text(appString(R.string.song_menu_rematch)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        onClick = { close(); sources.rematch(song) },
    )
}

// a page of the sources menu, shown in place of the song menu it was picked from
@Composable
fun SongPageMenu(song: Song, page: SongMenuPage, onDismiss: () -> Unit) {
    val sources = LocalSongSources.current ?: return
    SongSourcesMenu(song, sources, page, onDismiss = onDismiss)
}

// the icon each source has on the sign-in screen
private fun linkIcon(type: ServerType): ImageVector = when (type) {
    ServerType.YOUTUBE_MUSIC -> Icons.Outlined.PlayCircle
    ServerType.SPOTIFY -> Icons.Outlined.MusicNote
    ServerType.SOUNDCLOUD -> Icons.Outlined.GraphicEq
    ServerType.APPLE_MUSIC -> Icons.Outlined.AlbumOutlined
    else -> Icons.Filled.Link
}
