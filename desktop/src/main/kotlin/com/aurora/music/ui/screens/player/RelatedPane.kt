package com.aurora.music.ui.screens.player

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.HomeData
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.model.Song
import com.aurora.music.ui.components.EmptyState
import com.aurora.music.ui.components.FeedSection
import com.aurora.music.ui.components.LoadingState
import com.aurora.music.ui.theme.Motion
import kotlinx.coroutines.CancellationException

// youtube music's related picks for the playing song: similar songs, similar artists and more from the artist
@Composable
fun RelatedPane(song: Song, onOpenDetail: (String, String) -> Unit, onPlayAll: (List<Song>, Int) -> Unit, modifier: Modifier = Modifier) {
    val repository = LocalDesktopContainer.current.repository
    var related by remember(song.id) { mutableStateOf<HomeData?>(null) }
    LaunchedEffect(song.id) {
        related = try { repository.related(song) } catch (e: CancellationException) { throw e } catch (_: Exception) { HomeData() }
    }
    Crossfade(related, modifier, Motion.enter(), label = "related") { data ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                data == null -> LoadingState()
                data.sections.isEmpty() -> EmptyState(Icons.Filled.Explore, appString(R.string.discovery_related_empty))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    data.sections.forEach { section ->
                        item(key = section.id) { FeedSection(section, 0.dp, onOpenDetail, onPlayAll) }
                    }
                }
            }
        }
    }
}
