package com.aurora.music.ui.screens.stats

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.pagePadding
import com.aurora.music.util.accentFor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ListeningHistoryScreen(contentPadding: PaddingValues, onBack: () -> Unit, onPlay: (String) -> Unit) {
    val container = LocalDesktopContainer.current
    val history by container.playHistory.history.collectAsStateWithLifecycle()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxWidth()) {
        PageHeader(appString(R.string.text_listening_history_bd9991), Modifier.padding(horizontal = LocalPageGutter.current), onBack = onBack)
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(appString(R.string.text_nothing_played_yet_9503da), style = MaterialTheme.typography.titleMedium)
                    Text(appString(R.string.text_your_recently_played_tracks_will_appear_here_0bdf0b), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }
        val grouped = history.groupBy { dayLabel(it.timestamp) }
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth(), state = listState, contentPadding = pagePadding(contentPadding)) {
                grouped.forEach { (day, events) ->
                    item {
                        Text(day, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                    }
                    items(events.size) { i ->
                        val e = events[i]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = PageMetrics.SongRowHeight).clip(RoundedCornerShape(12.dp)).clickable { onPlay(e.songId) }
                                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(e.artworkUrl, accentFor(e.songId), Modifier.size(40.dp), corner = 8.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(e.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(timeFmt.format(Date(e.timestamp)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = contentPadding.calculateBottomPadding()))
        }
    }
}

private fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> appString(R.string.text_today_24345a)
        yesterday -> appString(R.string.text_yesterday_da2483)
        else -> SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(ts))
    }
}
