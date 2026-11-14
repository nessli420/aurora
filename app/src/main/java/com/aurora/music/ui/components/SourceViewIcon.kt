package com.aurora.music.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.ui.graphics.vector.ImageVector

// one icon per home feed or library view so the switch shows which one is open
fun sourceViewIcon(id: String): ImageVector = when (id) {
    "discovery", "youtube" -> Icons.Outlined.PlayCircle
    "spotify" -> Icons.Outlined.GraphicEq
    "soundcloud" -> Icons.Outlined.Cloud
    "apple" -> Icons.Outlined.Album
    else -> Icons.Outlined.LibraryMusic
}
