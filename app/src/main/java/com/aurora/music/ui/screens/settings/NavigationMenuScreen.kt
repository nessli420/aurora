package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
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
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.UiPrefs
import com.aurora.music.localization.appString
import com.aurora.music.navigation.NavLayout
import com.aurora.music.navigation.NavMenu
import com.aurora.music.navigation.NavMenuItem
import com.aurora.music.navigation.NavPlacement
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.theme.auroraPanel
import kotlinx.coroutines.launch

@Composable
fun NavigationMenuScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.settingsStore
    val prefs by store.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val simpleMode by store.simpleMode.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    val layout = remember(prefs.navLayout) { NavMenu.parse(prefs.navLayout) }
    val rail = LocalWindowLayout.current.useNavigationRail
    fun save(next: NavLayout) { scope.launch { store.setNavLayout(next.encode()) } }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.nav_menu_title), onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "bar-title") {
                PartHeader(appString(R.string.nav_tabbar_title), appString(R.string.nav_tabbar_hint),
                    note = if (rail) appString(R.string.nav_tabbar_rail_note) else null)
            }
            item(key = "bar-preview") { TabBarPreview(layout) }
            layout.bar.forEachIndexed { index, tab ->
                item(key = "bar-${tab.id}") {
                    TabEditRow(
                        item = tab,
                        first = index == 0,
                        last = index == layout.bar.lastIndex,
                        removable = layout.canRemoveTab(tab),
                        simpleMode = simpleMode,
                        onShift = { save(layout.shiftTab(tab.id, it)) },
                        onRemove = { save(layout.removeTab(tab.id)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item(key = "bar-add") { AddTabRow(layout) { save(layout.addTab(it)) } }
            item(key = "bar-more") {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.MoreHoriz, appString(R.string.nav_tabbar_more),
                        subtitle = appString(if (!layout.barMore && !layout.canAddTab) R.string.nav_tabbar_full else R.string.nav_tabbar_more_hint),
                        checked = layout.barMore) { save(layout.withBarMore(it)) }
                }
            }
            item(key = "bar-reset") {
                TextButton(onClick = { save(layout.resetTabBar()) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(appString(R.string.nav_tabbar_reset))
                }
            }

            item(key = "side-title") { PartHeader(appString(R.string.nav_sidemenu_title), appString(R.string.nav_menu_hint)) }
            section(appString(R.string.nav_section_main), NavPlacement.MAIN, layout.main, layout, simpleMode, ::save)
            section(appString(R.string.nav_section_more), NavPlacement.MORE, layout.more, layout, simpleMode, ::save)
            section(appString(R.string.nav_section_hidden), NavPlacement.HIDDEN, layout.hidden, layout, simpleMode, ::save)
            item(key = "side-reset") {
                TextButton(onClick = { save(layout.resetSideMenu()) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(appString(R.string.nav_reset))
                }
            }
        }
    }
}

@Composable
private fun PartHeader(title: String, hint: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(4.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (note != null) {
            Spacer(Modifier.size(4.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// a small copy of the bar so the result is visible before leaving settings
@Composable
private fun TabBarPreview(layout: NavLayout) {
    val colors = MaterialTheme.colorScheme
    val tabs = layout.bar.map { it.shortLabel to it.selectedIcon } +
        if (layout.barMore) listOf(appString(R.string.text_more_4bab2d) to Icons.Filled.MoreHoriz) else emptyList()
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true)
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEachIndexed { index, (label, icon) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, tint = if (index == 0) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, color = if (index == 0) colors.primary else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EditRowFrame(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().auroraPanel(MaterialTheme.shapes.medium)
                .then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick) else Modifier)
                .heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun RowLabel(icon: ImageVector, label: String, note: String?, dimmed: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Icon(icon, null, tint = if (dimmed) colors.onSurfaceVariant else colors.primary, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(16.dp))
    Column(modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun TabEditRow(
    item: NavMenuItem,
    first: Boolean,
    last: Boolean,
    removable: Boolean,
    simpleMode: Boolean,
    onShift: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EditRowFrame(modifier) {
        val note = when {
            item.id == NavMenu.PINNED_TAB -> appString(R.string.nav_tab_locked)
            item.advancedAudio && simpleMode -> appString(R.string.nav_simple_mode_hidden)
            else -> null
        }
        RowLabel(item.icon, item.shortLabel, note, dimmed = false, modifier = Modifier.weight(1f))
        IconButton(onClick = { onShift(-1) }, enabled = !first) { Icon(Icons.Filled.KeyboardArrowUp, appString(R.string.nav_move_up)) }
        IconButton(onClick = { onShift(1) }, enabled = !last) { Icon(Icons.Filled.KeyboardArrowDown, appString(R.string.nav_move_down)) }
        if (item.id == NavMenu.PINNED_TAB) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, appString(R.string.nav_tab_locked), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        } else IconButton(onClick = onRemove, enabled = removable) {
            Icon(Icons.Filled.RemoveCircleOutline, appString(R.string.nav_tabbar_remove))
        }
    }
}

@Composable
private fun AddTabRow(layout: NavLayout, onAdd: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val options = NavMenu.items.filter { item -> layout.bar.none { it.id == item.id } }
    val enabled = layout.canAddTab && options.isNotEmpty()
    Box {
        EditRowFrame(onClick = if (enabled) ({ menu = true }) else null) {
            RowLabel(Icons.Filled.Add, appString(R.string.nav_tabbar_add), if (layout.canAddTab) null else appString(R.string.nav_tabbar_full),
                dimmed = !enabled, modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    leadingIcon = { Icon(item.icon, null) },
                    onClick = { menu = false; onAdd(item.id) },
                )
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
    EditRowFrame(modifier) {
        val note = when {
            item.locked -> appString(R.string.nav_locked)
            item.advancedAudio && simpleMode -> appString(R.string.nav_simple_mode_hidden)
            else -> null
        }
        RowLabel(item.icon, item.label, note, dimmed = placement == NavPlacement.HIDDEN, modifier = Modifier.weight(1f))
        if (placement == NavPlacement.HIDDEN) {
            TextButton(onClick = { onMove(NavPlacement.MAIN) }) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(appString(R.string.nav_section_main))
            }
            TextButton(onClick = { onMove(NavPlacement.MORE) }) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(appString(R.string.nav_section_more))
            }
        } else {
            IconButton(onClick = { onShift(-1) }, enabled = !first) {
                Icon(Icons.Filled.KeyboardArrowUp, appString(R.string.nav_move_up))
            }
            IconButton(onClick = { onShift(1) }, enabled = !last) {
                Icon(Icons.Filled.KeyboardArrowDown, appString(R.string.nav_move_down))
            }
            if (item.locked) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, appString(R.string.nav_locked), tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            } else Box {
                IconButton(onClick = { menu = true }) {
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
