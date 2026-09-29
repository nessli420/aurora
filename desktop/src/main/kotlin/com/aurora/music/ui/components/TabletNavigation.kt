package com.aurora.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.navigation.NavLayout
import com.aurora.music.navigation.NavMenuItem
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.layout.TabletMetrics

@Composable
fun TabletNavigationRail(currentRoute: String?, items: List<NavMenuItem>, onOpen: (NavMenuItem) -> Unit, onMenu: () -> Unit, onSettings: () -> Unit) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f),
    )
    NavigationRail(
        modifier = Modifier.width(TabletMetrics.RailWidth).fillMaxHeight(),
        containerColor = Color.Transparent,
        header = {
            IconButton(onClick = onMenu, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Icon(Icons.Filled.Menu, appString(R.string.open_navigation))
            }
        },
    ) {
        Spacer(Modifier.height(20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            items.forEach { item ->
                NavigationRailItem(
                    selected = currentRoute == item.route,
                    colors = colors,
                    onClick = { onOpen(item) },
                    icon = { Icon(item.icon, null) },
                    label = { Text(railLabel(item), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.padding(vertical = 4.dp).pointerHoverIcon(PointerIcon.Hand),
                )
            }
        }
        NavigationRailItem(
            selected = currentRoute == Routes.SETTINGS,
            colors = colors,
            onClick = onSettings,
            icon = { Icon(Icons.Filled.Settings, null) },
            label = { Text(appString(R.string.text_settings_c7f73b)) },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
        Spacer(Modifier.height(16.dp))
    }
}

private fun railLabel(item: NavMenuItem): String =
    if (item.route == Routes.LIBRARY) appString(R.string.text_library_b8100f) else item.label

@Composable
fun TabletSidebar(
    currentRoute: String?,
    username: String,
    server: String,
    avatarUrl: String,
    layout: NavLayout,
    onOpen: (NavMenuItem) -> Unit,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.width(TabletMetrics.SidebarWidth).fillMaxHeight()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
    ) {
        ProfileHeader(username, server, avatarUrl, onProfile)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            layout.main.forEach { item ->
                SidebarItem(item.label, item.icon, currentRoute == item.route, prominent = true) { onOpen(item) }
            }
            if (layout.more.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FoldableMore(layout.more, currentRoute, onOpen)
            }
        }
        SidebarItem(appString(R.string.text_settings_c7f73b), Icons.Filled.Settings, currentRoute == Routes.SETTINGS, prominent = false, onClick = onSettings)
    }
}

@Composable
private fun ProfileHeader(username: String, server: String, avatarUrl: String, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = appString(R.string.text_profile_ff4fc0), onClick = onProfile)
            .heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl.isNotBlank()) Artwork(avatarUrl, MaterialTheme.colorScheme.primary, Modifier.size(44.dp), corner = 22.dp)
            else Text(username.take(2).uppercase().ifBlank { appString(R.string.text_me_b4d362) },
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(username.ifBlank { appString(R.string.text_listener_37ea46) }, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val host = server.removePrefix("http://").removePrefix("https://")
            if (host.isNotBlank()) Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FoldableMore(items: List<NavMenuItem>, currentRoute: String?, onOpen: (NavMenuItem) -> Unit, startPadding: Dp = 16.dp) {
    var open by rememberSaveable { mutableStateOf(items.any { it.route == currentRoute }) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "moreChevron")
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { open = !open }
            .heightIn(min = 44.dp).padding(start = startPadding, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(appString(R.string.text_more_4bab2d), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).rotate(rotation))
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            items.forEach { item -> SidebarItem(item.label, item.icon, currentRoute == item.route, prominent = false, compact = true) { onOpen(item) } }
        }
    }
}

@Composable
private fun SidebarItem(label: String, icon: ImageVector, selected: Boolean, prominent: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent, label = "sidebarItem")
    val content = if (selected) colors.primary else if (prominent) colors.onSurface else colors.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(CircleShape).background(container)
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = when { prominent -> 52.dp; compact -> 40.dp; else -> 48.dp }).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(if (prominent) 24.dp else 20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = if (prominent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || prominent) FontWeight.Bold else FontWeight.Medium, color = content,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
