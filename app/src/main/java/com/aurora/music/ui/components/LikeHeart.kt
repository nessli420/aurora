package com.aurora.music.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.LikeOption
import com.aurora.music.data.LikedSource
import com.aurora.music.data.likedSourcesId
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LikeMenu(
    val options: suspend (Song) -> List<LikeOption>,
    val set: suspend (Song, LikeOption, Boolean) -> List<LikeOption>,
    val likedSources: () -> List<LikedSource> = { emptyList() },
)

val LocalLikeMenu = staticCompositionLocalOf<LikeMenu?> { null }

// a tap likes on the source the song plays from; a long press lists every account it can be liked on
@Composable
fun LikeHeart(song: Song, onToggle: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val menu = LocalLikeMenu.current
    var open by remember(song.id) { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(onClick = onToggle, onLongClickLabel = appString(R.string.like_more_sources),
            onLongClick = if (menu == null) null else ({ open = true })),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (open && menu != null) LikeSourcesMenu(song, menu) { open = false }
    }
}

@Composable
private fun LikeSourcesMenu(song: Song, menu: LikeMenu, onDismiss: () -> Unit) {
    var options by remember(song.id) { mutableStateOf<List<LikeOption>?>(null) }
    var busy by remember(song.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(song.id) {
        options = try { menu.options(song) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        if (options.isNullOrEmpty()) onDismiss()
    }
    val colors = MaterialTheme.colorScheme
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Text(appString(R.string.like_on), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val list = options
        if (list == null) Box(Modifier.padding(16.dp).size(width = 160.dp, height = 24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        list?.forEach { option ->
            val note = when {
                option.guest -> R.string.like_guest
                option.id == null -> R.string.like_no_match
                option.playing -> R.string.like_now_playing
                else -> null
            }
            DropdownMenuItem(
                text = {
                    Column {
                        Text(option.label)
                        note?.let { Text(appString(it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                    }
                },
                leadingIcon = {
                    if (busy == option.key) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(if (option.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null,
                        tint = if (option.liked) colors.primary else colors.onSurfaceVariant)
                },
                enabled = option.available && busy == null,
                onClick = {
                    busy = option.key
                    scope.launch {
                        try { options = menu.set(song, option, !option.liked) } finally { busy = null }
                    }
                },
            )
        }
    }
}

// which sources a liked songs list holds; all start picked, open hands back the liked id
@Composable
fun LikedSourcesMenu(sources: List<LikedSource>, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var picked by remember(sources) { mutableStateOf(sources.map { it.key }.toSet()) }
    val colors = MaterialTheme.colorScheme
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Text(appString(R.string.liked_sources_title), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        sources.forEach { source ->
            val on = source.key in picked
            DropdownMenuItem(
                text = {
                    Column {
                        Text(source.label)
                        Text(appPlural(R.plurals.track_count, source.count), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                },
                leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                onClick = { picked = if (on) picked - source.key else picked + source.key },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(appString(R.string.liked_sources_open), color = if (picked.isEmpty()) colors.onSurfaceVariant else colors.primary) },
            enabled = picked.isNotEmpty(),
            onClick = { onOpen(likedSourcesId(sources.map { it.key }.filter { it in picked }, sources.map { it.key })) },
        )
    }
}
