package com.aurora.music.ui.screens.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.ai.isDjSpeech
import com.aurora.music.data.SourceCopy
import com.aurora.music.data.isPodcast
import com.aurora.music.data.isRadio
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException

// what the title and artist menus can do with the playing song; the app supplies it
class SongSources(
    val copies: suspend (Song) -> List<SourceCopy>,
    val releaseType: suspend (Song) -> String?,
    val canRematch: (Song) -> Boolean,
    val playFrom: (Song) -> Unit,
    val rematch: (Song) -> Unit,
    val open: (kind: String, id: String) -> Unit,
)

val LocalSongSources = staticCompositionLocalOf<SongSources?> { null }

enum class SongMenuPage { ROOT, SOURCE, ALBUM, ARTIST }

fun songMenusFor(song: Song): Boolean = song.id.isNotBlank() && !song.isRadio() && !song.isPodcast() && !song.isDjSpeech() &&
    !song.id.startsWith("aurora-mix:")

@Composable
internal fun SongSourcesMenu(song: Song, sources: SongSources, start: SongMenuPage, onDismiss: () -> Unit) {
    var page by remember(song.id) { mutableStateOf(start) }
    var copies by remember(song.id) { mutableStateOf<List<SourceCopy>?>(null) }
    var release by remember(song.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(song.id) {
        copies = try { sources.copies(song) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
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

    @Composable
    fun choices(keep: (SourceCopy) -> Boolean, row: @Composable (SourceCopy) -> Unit) {
        val list = copies
        when {
            list == null -> Box(Modifier.padding(16.dp).size(width = 160.dp, height = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            list.none(keep) -> header(appString(R.string.like_no_match))
            else -> list.filter(keep).forEach { row(it) }
        }
    }

    @Composable
    fun label(copy: SourceCopy, note: String?) = Column {
        Text(copy.label)
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        when (page) {
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
                    HorizontalDivider()
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
                choices({ (if (album) it.song.albumId else it.song.artistId).isNotBlank() }) { copy ->
                    DropdownMenuItem(
                        text = { label(copy, if (album) copy.song.album.ifBlank { null } else copy.song.artist.ifBlank { null }) },
                        leadingIcon = { Icon(if (album) Icons.Filled.Album else Icons.Filled.Person, null) },
                        onClick = {
                            onDismiss()
                            if (album) sources.open("album", copy.song.albumId) else sources.open("artist", copy.song.artistId)
                        },
                    )
                }
            }
        }
    }
}
