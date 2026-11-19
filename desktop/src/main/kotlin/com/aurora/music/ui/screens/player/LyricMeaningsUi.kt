package com.aurora.music.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.aurora.music.R
import com.aurora.music.data.GeniusReferent
import com.aurora.music.data.GeniusSong
import com.aurora.music.data.LyricMeanings
import com.aurora.music.data.Lyrics
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.Space
import com.aurora.music.ui.theme.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// the setting is read per song, so switching it on shows notes from the next lyrics opened
@Composable
internal fun defaultMeaningsLoader(): suspend (Song) -> GeniusSong? {
    val container = LocalDesktopContainer.current
    return remember(container) { { song -> if (container.settingsStore.lyricMeanings.first()) container.genius.lookup(song.artist, song.title) else null } }
}

// genius notes for the song on screen; a miss or failure just shows none
@Composable
internal fun rememberMeanings(song: Song, lyrics: Lyrics?, load: suspend (Song) -> GeniusSong?): LyricMeanings? {
    val found by produceState<GeniusSong?>(null, song.id, song.artist, song.title) {
        value = null
        if (song.id.isNotBlank()) value = load(song)
    }
    val placed by produceState<LyricMeanings?>(null, found, lyrics) {
        val genius = found
        value = genius?.let { withContext(Dispatchers.Default) { LyricMeanings.place(it, lyrics?.lines.orEmpty().map { l -> l.displayText }) } }
    }
    return placed
}

internal val LyricMeanings.hasAbout get() = song.description.isNotBlank()

internal fun sourceLabel(source: String?, meanings: LyricMeanings?): String? =
    listOfNotNull(source?.takeIf { it.isNotBlank() }, "Genius".takeIf { meanings != null && (meanings.spans.isNotEmpty() || meanings.hasAbout) })
        .joinToString(" · ").ifBlank { null }

internal const val MEANING_MARK = "genius"

internal fun AnnotatedString.withMeaningMark(): AnnotatedString = buildAnnotatedString {
    append(this@withMeaningMark)
    append(' ')
    appendInlineContent(MEANING_MARK, "*")
}

// a small glyph after an annotated line, dimmed with its line; it fades in when the notes arrive
internal fun meaningMarkContent(lineAlpha: Float) = mapOf(MEANING_MARK to InlineTextContent(Placeholder(.8.em, .8.em, PlaceholderVerticalAlign.TextCenter)) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(if (shown) lineAlpha else 0f, Motion.enter(), label = "meaningMark")
    val ink = LocalLyricsInk.current.takeOrElse { Color.White }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(ink.copy(alpha = .16f), CircleShape)
        .pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.TipsAndUpdates, null, Modifier.fillMaxSize(.66f), tint = ink.copy(alpha = .8f))
    }
})

private fun credit(authors: List<String>): String =
    authors.take(3).joinToString(", ") + if (authors.size > 3) " +${authors.size - 3}" else ""

private class MeaningEntry(val quote: String?, val body: String, val authors: List<String>, val verified: Boolean, val url: String)

@Composable
internal fun GeniusSheets(meanings: LyricMeanings?, explain: List<GeniusReferent>?, about: Boolean, onDismiss: () -> Unit) {
    val song = meanings?.song ?: return
    when {
        explain != null -> MeaningSheet(appString(R.string.genius_explain),
            explain.flatMap { ref -> ref.notes.mapIndexed { i, n -> MeaningEntry(ref.fragment.takeIf { i == 0 }, n.body, n.authors, n.verified, ref.url.ifBlank { song.url }) } },
            onDismiss)
        about && song.description.isNotBlank() -> MeaningSheet(appString(R.string.genius_about),
            listOf(MeaningEntry(null, song.description, song.describedBy, false, song.url)), onDismiss)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MeaningSheet(title: String, entries: List<MeaningEntry>, onDismiss: () -> Unit) {
    val links = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(),
        containerColor = com.aurora.music.ui.theme.solidColors().surface) {
        LazyColumn(Modifier.fillMaxWidth().widthIn(max = 760.dp).align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.TipsAndUpdates, null, Modifier.size(20.dp), tint = colors.primary)
                    Spacer(Modifier.width(Space.s))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            items(entries.size) { index ->
                val entry = entries[index]
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    entry.quote?.let { quote ->
                        Text(quote.replace(Regex("\\s*\\n\\s*"), " / "), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                            color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().drawBehind {
                                drawLine(colors.primary, Offset(0f, 0f), Offset(0f, size.height), 3.dp.toPx())
                            }.padding(start = Space.m))
                    }
                    Text(entry.body, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (entry.authors.isNotEmpty()) Text(appString(R.string.genius_by, credit(entry.authors)),
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            if (entry.verified) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Verified, null, Modifier.size(14.dp), tint = colors.primary)
                                Spacer(Modifier.width(Space.xs))
                                Text(appString(R.string.genius_verified), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                            }
                        }
                        if (entry.url.isNotBlank()) TextButton(onClick = { runCatching { links.openUri(entry.url) } },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                            Text(appString(R.string.genius_view))
                            Spacer(Modifier.width(Space.xs))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                        }
                    }
                    if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(top = Space.s), color = colors.outlineVariant)
                }
            }
            item {
                Text(appString(R.string.genius_attribution), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
