package com.aurora.music.ui.components

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SidebarContent(
    username: String,
    server: String,
    avatarUrl: String = "",
    onProfile: () -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    onDuplicates: () -> Unit,
    onRadio: () -> Unit = {},
    onPodcasts: () -> Unit = {},
    onLogout: () -> Unit,
) {
    val initials = username.take(2).uppercase().ifBlank { appString(R.string.text_me_b4d362) }
    val host = server.removePrefix("http://").removePrefix("https://").ifBlank { appString(R.string.text_view_profile_b98795) }
    Column(
        Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        // Profile header
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onProfile() }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl.isNotBlank()) {
                    Artwork(avatarUrl, MaterialTheme.colorScheme.primary, Modifier.matchParentSize(), corner = 28.dp)
                } else {
                    Text(initials, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(username.ifBlank { appString(R.string.text_listener_37ea46) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
        Spacer(Modifier.height(8.dp))

        DrawerItem(Icons.Filled.Person, appString(R.string.text_profile_ff4fc0)) { onProfile() }
        DrawerItem(Icons.AutoMirrored.Filled.QueueMusic, appString(R.string.text_your_library_fd740f)) { onLibrary() }
        DrawerItem(Icons.Filled.Radio, appString(R.string.text_radio_b11bf1)) { onRadio() }
        DrawerItem(Icons.Filled.Podcasts, appString(R.string.text_podcasts_fd52b4)) { onPodcasts() }
        DrawerItem(Icons.Filled.History, appString(R.string.text_listening_history_bd9991)) { onHistory() }
        DrawerItem(Icons.Filled.Workspaces, appString(R.string.text_listening_stats_a760b2)) { onStats() }
        DrawerItem(Icons.Filled.ContentCopy, appString(R.string.text_find_duplicates_586f31)) { onDuplicates() }
        DrawerItem(Icons.Filled.Settings, appString(R.string.text_settings_c7f73b)) { onSettings() }

        Spacer(Modifier.weight(1f))

        DrawerItem(Icons.AutoMirrored.Filled.Logout, appString(R.string.text_log_out_6e78c9), tint = MaterialTheme.colorScheme.error) { onLogout() }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = tint)
    }
}
