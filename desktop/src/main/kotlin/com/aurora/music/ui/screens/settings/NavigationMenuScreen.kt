package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.appString
import com.aurora.music.navigation.NavLayout
import com.aurora.music.navigation.NavMenu
import com.aurora.music.navigation.NavMenuItem
import com.aurora.music.navigation.NavPlacement
import com.aurora.music.ui.theme.auroraPanel
import kotlinx.coroutines.launch

private val hand = Modifier.pointerHoverIcon(PointerIcon.Hand)

@Composable
fun NavigationMenuScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = LocalDesktopContainer.current.settingsStore
    val prefs by store.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val simpleMode by store.simpleMode.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val layout = remember(prefs.navLayout) { NavMenu.parse(prefs.navLayout) }
    fun save(next: NavLayout) { scope.launch { store.setNavLayout(next.encode()) } }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.nav_menu_title), onBack)
        SettingsList(contentPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "hint") {
                Text(appString(R.string.nav_menu_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            section(appString(R.string.nav_section_main), NavPlacement.MAIN, layout.main, layout, simpleMode, ::save)
            section(appString(R.string.nav_section_more), NavPlacement.MORE, layout.more, layout, simpleMode, ::save)
            section(appString(R.string.nav_section_hidden), NavPlacement.HIDDEN, layout.hidden, layout, simpleMode, ::save)
            item(key = "reset") {
                TextButton(onClick = { scope.launch { store.setNavLayout("") } }, modifier = Modifier.padding(horizontal = 12.dp).then(hand)) {
                    Text(appString(R.string.nav_reset))
                }
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    placement: NavPlacement,
    items: List<NavMenuItem>,
    layout: NavLayout,
    simpleMode: Boolean,
    save: (NavLayout) -> Unit,
) {
    item(key = "title-$placement") { SettingsSectionTitle(title) }
    if (items.isEmpty()) item(key = "empty-$placement") {
        Text(appString(R.string.nav_empty_section), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
    items.forEachIndexed { index, item ->
        item(key = item.id) {
            NavEditRow(
                item = item,
                placement = placement,
                first = index == 0,
                last = index == items.lastIndex,
                simpleMode = simpleMode,
                onShift = { save(layout.shift(item.id, it)) },
                onMove = { save(layout.move(item.id, it)) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun NavEditRow(
    item: NavMenuItem,
    placement: NavPlacement,
    first: Boolean,
    last: Boolean,
    simpleMode: Boolean,
    onShift: (Int) -> Unit,
    onMove: (NavPlacement) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().auroraPanel(MaterialTheme.shapes.medium)
                .heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(item.icon, null, tint = if (placement == NavPlacement.HIDDEN) colors.onSurfaceVariant else colors.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(item.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val note = when {
                    item.locked -> appString(R.string.nav_locked)
                    item.advancedAudio && simpleMode -> appString(R.string.nav_simple_mode_hidden)
                    else -> null
                }
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (placement == NavPlacement.HIDDEN) {
                TextButton(onClick = { onMove(NavPlacement.MAIN) }, modifier = hand) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(appString(R.string.nav_section_main))
                }
                TextButton(onClick = { onMove(NavPlacement.MORE) }, modifier = hand) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(appString(R.string.nav_section_more))
                }
            } else {
                IconButton(onClick = { onShift(-1) }, enabled = !first, modifier = hand) {
                    Icon(Icons.Filled.KeyboardArrowUp, appString(R.string.nav_move_up))
                }
                IconButton(onClick = { onShift(1) }, enabled = !last, modifier = hand) {
                    Icon(Icons.Filled.KeyboardArrowDown, appString(R.string.nav_move_down))
                }
                if (item.locked) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Lock, appString(R.string.nav_locked), tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                } else Box {
                    IconButton(onClick = { menu = true }, modifier = hand) {
                        Icon(Icons.Filled.MoreVert, appString(R.string.nav_placement_options))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (placement == NavPlacement.MORE) DropdownMenuItem(
                            text = { Text(appString(R.string.nav_move_to_main)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) },
                            onClick = { menu = false; onMove(NavPlacement.MAIN) },
                        )
                        if (placement == NavPlacement.MAIN) DropdownMenuItem(
                            text = { Text(appString(R.string.nav_move_to_more)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            onClick = { menu = false; onMove(NavPlacement.MORE) },
                        )
                        DropdownMenuItem(
                            text = { Text(appString(R.string.nav_remove)) },
                            leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, null) },
                            onClick = { menu = false; onMove(NavPlacement.HIDDEN) },
                        )
                    }
                }
            }
        }
    }
}
