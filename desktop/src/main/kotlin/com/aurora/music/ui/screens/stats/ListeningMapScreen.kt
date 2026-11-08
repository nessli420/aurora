package com.aurora.music.ui.screens.stats

import androidx.compose.foundation.background
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.DetailedPlay
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.RecapWindow
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.appString
import com.aurora.music.ui.components.MapMarker
import com.aurora.music.ui.components.MapPoint
import com.aurora.music.ui.components.TileMap
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.roundToLong

// plays within roughly 30 m share one dot so a long session at home stays readable
private data class Spot(val key: Pair<Long, Long>, val point: MapPoint, val plays: List<DetailedPlay>)

private fun spots(plays: List<DetailedPlay>): List<Spot> = plays.groupBy {
    (it.context.latitude!! / CELL).roundToLong() to (it.context.longitude!! / CELL).roundToLong()
}.map { (key, list) ->
    Spot(key, MapPoint(list.map { it.context.latitude!! }.average(), list.map { it.context.longitude!! }.average()), list.sortedByDescending { it.startedAt })
}

private const val CELL = 0.0003

@Composable
fun ListeningMapScreen(contentPadding: PaddingValues, onBack: () -> Unit, songId: String? = null, at: Long? = null,
    tiles: com.aurora.music.ui.components.MapTiles = LocalDesktopContainer.current.mapTiles) {
    val container = LocalDesktopContainer.current
    val scope = rememberCoroutineScope()
    var window by remember { mutableStateOf(RecapWindow.containing(RecapPeriod.MONTH, LocalDate.now())) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val plays by produceState<List<DetailedPlay>?>(null, window, songId, refresh) {
        value = if (songId != null) container.listeningDetails.located(songId = songId)
        else container.listeningDetails.located(window.start.atStartOfDay(zone).toInstant().toEpochMilli(),
            window.end.atStartOfDay(zone).toInstant().toEpochMilli())
    }
    val spots = remember(plays) { spots(plays.orEmpty()) }
    val highlighted = remember(plays, at) { at?.let { t -> plays.orEmpty().minByOrNull { kotlin.math.abs(it.startedAt - t) } } }
    val focus = selected?.let { key -> spots.firstOrNull { it.key == key } }
        ?: highlighted?.let { h -> spots.firstOrNull { s -> s.plays.any { it.id == h.id } } }
    val markers = remember(spots, focus, highlighted) {
        spots.map { MapMarker(it.key, it.point, it.key == focus?.key || it.plays.any { p -> p.id == highlighted?.id }) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36),
                modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onBack).padding(8.dp))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(highlighted?.track?.title ?: appString(R.string.map_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (songId != null) Text(appString(R.string.map_song_subtitle, plays.orEmpty().size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.DeleteForever, appString(R.string.insights_delete_locations), tint = MaterialTheme.colorScheme.error)
            }
        }
        if (songId == null) PeriodPicker(window) { window = it; selected = null }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).paperClip(RoundedCornerShape(20.dp))) {
            TileMap(tiles, markers, focus?.point, onSelect = { selected = it }, modifier = Modifier.fillMaxSize())
            if (plays != null && spots.isEmpty()) Text(appString(R.string.map_empty),
                Modifier.align(Alignment.Center).paperClip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
        val listed = focus?.plays ?: if (songId != null) plays.orEmpty().sortedByDescending { it.startedAt } else emptyList()
        Spacer(Modifier.height(4.dp))
        if (listed.isEmpty() && spots.isNotEmpty()) Text(appString(R.string.map_tap_hint), Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (listed.isNotEmpty()) {
            Text(appString(R.string.map_plays_here, listed.size), Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                items(listed, key = { it.id }) { play ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = spots.firstOrNull { s -> s.plays.any { it.id == play.id } }?.key
                    }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(play.track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (play.id == highlighted?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(play.track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(play.startedAt)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
    }
    if (confirmDelete) AlertDialog(containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh,
        onDismissRequest = { confirmDelete = false },
        title = { Text(appString(R.string.insights_delete_locations)) },
        text = { Text(appString(R.string.insights_delete_locations_body)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    container.listeningDetails.clearLocations()
                    selected = null
                    refresh++
                }
            }) { Text(appString(R.string.insights_delete_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}
