package com.aurora.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.TrackSort
import com.aurora.music.data.TrackSortOrder
import com.aurora.music.localization.appString

val TrackSort.label: String get() = appString(when (this) {
    TrackSort.CUSTOM -> R.string.track_sort_custom
    TrackSort.TITLE -> R.string.track_sort_title
    TrackSort.ARTIST -> R.string.track_sort_artist
    TrackSort.ALBUM -> R.string.track_sort_album
    TrackSort.RECENTLY_ADDED -> R.string.track_sort_recent
    TrackSort.DURATION -> R.string.track_sort_duration
})

/** The funnel on a playlist or Liked Songs; it lights up in the accent while a sort other than custom is on. */
@Composable
fun TrackSortButton(order: TrackSortOrder, accent: Color, enabled: Boolean = true, onPick: (TrackSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(
            if (order.isDefault) Icons.Outlined.FilterAlt else Icons.Filled.FilterAlt,
            appString(R.string.track_sort_button),
            tint = if (order.isDefault) MaterialTheme.colorScheme.onSurfaceVariant else accent,
            modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled) { open = true }.pointerHoverIcon(PointerIcon.Hand).padding(12.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(appString(R.string.track_sort_heading), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            TrackSort.entries.forEach { choice ->
                val active = order.by == choice
                DropdownMenuItem(
                    text = { Text(choice.label, fontWeight = if (active) FontWeight.SemiBold else null, color = if (active) accent else Color.Unspecified) },
                    // the active choice stays open so a second tap can flip it
                    onClick = { onPick(choice); if (!active) open = false },
                    trailingIcon = if (active) ({
                        Icon(if (order.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            appString(if (order.descending) R.string.track_sort_descending else R.string.track_sort_ascending), tint = accent)
                    }) else null,
                )
            }
        }
    }
}
