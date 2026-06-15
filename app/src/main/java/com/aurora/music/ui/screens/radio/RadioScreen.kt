package com.aurora.music.ui.screens.radio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.data.RadioStation
import com.aurora.music.data.toSong
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.RadioViewModel

private val GENRE_TAGS = listOf(
    "pop", "rock", "jazz", "classical", "news", "electronic", "hip hop",
    "country", "metal", "ambient", "lounge", "dance", "reggae", "blues", "talk",
)

@Composable
fun RadioScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit,
) {
    val vm: RadioViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    val playStation: (RadioStation) -> Unit = { st -> vm.registerPlay(st); onPlay(st.toSong()) }
    val favoriteUuids = favorites.mapTo(HashSet()) { it.uuid }
    val browsing = state.query.isBlank() && state.activeTag.isBlank()
    val listed = if (browsing) state.popular else state.results

    Column(Modifier.fillMaxSize().padding(top = topInset)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Radio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.Add, "Add custom stream",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { showAdd = true }.padding(8.dp),
            )
            Icon(
                if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                if (searchOpen) "Close search" else "Search stations",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable {
                    searchOpen = !searchOpen
                    if (!searchOpen) { query = ""; vm.clearSearch() }
                }.padding(8.dp),
            )
        }

        AnimatedVisibility(visible = searchOpen) {
            TextField(
                value = query,
                onValueChange = { query = it; vm.search(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Search radio stations") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }

        val bottom = contentPadding.calculateBottomPadding() + 24.dp
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = bottom)) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(GENRE_TAGS.size) { i ->
                        val tag = GENRE_TAGS[i]
                        val selected = state.activeTag == tag
                        Text(
                            tag.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { if (selected) vm.loadPopular() else vm.byTag(tag) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            if (favorites.isNotEmpty()) {
                item { SectionLabel("Your stations") }
                items(favorites.size) { i ->
                    val st = favorites[i]
                    StationRow(st, isFavorite = true, onPlay = { playStation(st) }, onToggleFavorite = { vm.toggleFavorite(st) })
                }
            }

            item {
                SectionLabel(
                    when {
                        state.query.isNotBlank() -> "Results"
                        state.activeTag.isNotBlank() -> state.activeTag.replaceFirstChar { it.uppercase() }
                        else -> "Popular worldwide"
                    }
                )
            }

            if (state.loading) {
                item { Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(64.dp)) } }
            } else if (listed.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.failed) "Couldn't reach the radio directory" else "No stations found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(listed.size) { i ->
                    val st = listed[i]
                    StationRow(st, isFavorite = favoriteUuids.contains(st.uuid), onPlay = { playStation(st) }, onToggleFavorite = { vm.toggleFavorite(st) })
                }
            }
        }
    }

    if (showAdd) {
        AddStreamDialog(
            onAdd = { name, url -> vm.addCustom(name, url); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun StationRow(
    station: RadioStation,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp)).clickable(onClick = onPlay).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Artwork(station.faviconUrl.orEmpty(), accentFor("radio:${station.uuid}"), Modifier.size(48.dp), corner = 10.dp)
            if (station.faviconUrl.isNullOrBlank()) {
                Icon(Icons.Filled.Radio, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(station.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                if (station.genre.isNotBlank()) add(station.genre)
                station.codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                station.bitrate?.takeIf { it > 0 }?.let { add("${it}k") }
            }.joinToString(" • ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            if (isFavorite) "Unfavorite" else "Favorite",
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggleFavorite).padding(8.dp),
        )
        Icon(
            Icons.Filled.PlayArrow, "Play",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onPlay).padding(8.dp),
        )
    }
}

@Composable
private fun AddStreamDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add radio stream", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Stream URL") }, singleLine = true, placeholder = { Text("https://…") })
            }
        },
        confirmButton = { TextButton(onClick = { if (url.isNotBlank()) onAdd(name, url) }, enabled = url.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
