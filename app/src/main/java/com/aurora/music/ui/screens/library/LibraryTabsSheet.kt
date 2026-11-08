package com.aurora.music.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.LibraryTabs
import com.aurora.music.localization.appString
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryTabsSheet(initial: LibraryTabs, canDownload: Boolean, onChange: (LibraryTabs) -> Unit, onDismiss: () -> Unit) {
    var tabs by remember { mutableStateOf(initial) }
    fun update(next: LibraryTabs) {
        tabs = next
        onChange(next)
    }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface, tonalElevation = 0.dp, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(appString(R.string.library_tabs_customize), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text(appString(R.string.library_tabs_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            // downloads stay out of the list where this source cannot download
            val shown = tabs.order.filter { canDownload || it != LibraryFilter.DOWNLOADED }
            shown.forEachIndexed { i, tab ->
                val visible = tab !in tabs.hidden
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(tabIcon(tab), null, tint = if (visible) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(tab.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        color = if (visible) colors.onSurface else colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        IconButton(onClick = { update(tabs.move(tab, -1)) }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, appString(R.string.library_tabs_move_up, tab.label))
                        }
                        IconButton(onClick = { update(tabs.move(tab, 1)) }, enabled = i < shown.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, appString(R.string.library_tabs_move_down, tab.label))
                        }
                    }
                    Switch(checked = visible, onCheckedChange = { update(tabs.toggle(tab)) },
                        enabled = !visible || tabs.visible.count { canDownload || it != LibraryFilter.DOWNLOADED } > 1)
                }
            }
        }
    }
}
