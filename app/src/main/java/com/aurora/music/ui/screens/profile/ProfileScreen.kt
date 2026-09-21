package com.aurora.music.ui.screens.profile

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader

@Composable
fun ProfileScreen(
    contentPadding: PaddingValues,
    username: String,
    server: String,
    serverLabel: String,
    avatarUrl: String,
    bannerUrl: String = "",
    onEditProfile: (() -> Unit)? = null,
    playlists: List<Playlist>,
    artists: List<Artist>,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val initials = username.take(2).uppercase().ifBlank { appString(R.string.text_me_b4d362) }
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(220.dp)
                        .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.background))),
                )
                if (bannerUrl.isNotBlank()) {
                    Artwork(bannerUrl, MaterialTheme.colorScheme.secondary, Modifier.fillMaxWidth().height(220.dp), corner = 0.dp)
                    Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(
                        androidx.compose.ui.graphics.Color.Black.copy(alpha = .3f),
                        MaterialTheme.colorScheme.background.copy(alpha = .15f), MaterialTheme.colorScheme.background))))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = topInset + 6.dp, start = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp))
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.MoreVert, appString(R.string.text_settings_c7f73b), modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onOpenSettings).padding(8.dp))
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(110.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarUrl.isNotBlank()) {
                            Artwork(avatarUrl, MaterialTheme.colorScheme.primary, Modifier.matchParentSize(), corner = 55.dp)
                        } else {
                            Text(initials, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(username.ifBlank { appString(R.string.text_listener_37ea46) }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(serverLabel.ifBlank { server.removePrefix("http://").removePrefix("https://") }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat("${playlists.size}", appString(R.string.text_playlists_77b69f))
                Stat("${artists.size}", appString(R.string.text_artists_1528d8))
                Stat(serverLabel.ifBlank { appString(R.string.text_server_cb0cb1) }, appString(R.string.text_server_cb0cb1))
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).clickable(onClick = onEditProfile ?: onOpenSettings).padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (onEditProfile != null) appString(R.string.text_edit_profile_15141e) else appString(R.string.text_settings_c7f73b), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }

        if (artists.isNotEmpty()) {
            item {
                SectionHeader(appString(R.string.text_top_artists_4920f8), Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(artists.size) { i -> ArtistCircle(artists[i], onClick = { onOpenDetail("artist", artists[i].id) }) }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item {
                SectionHeader(appString(R.string.text_your_playlists_df03eb), Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(playlists.size) { i -> PlaylistCard(playlists[i], onClick = { onOpenDetail("playlist", playlists[i].id) }) }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
