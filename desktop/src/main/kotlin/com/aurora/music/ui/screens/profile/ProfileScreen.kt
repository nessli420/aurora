package com.aurora.music.ui.screens.profile

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.PageSection
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.TabletMetrics
import com.aurora.music.ui.layout.pagePadding

private val WideProfile = 900.dp

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
    val gutter = LocalPageGutter.current
    val listState = rememberLazyListState()
    val identity = ProfileIdentity(
        name = username.ifBlank { appString(R.string.text_listener_37ea46) },
        initials = username.take(2).uppercase().ifBlank { appString(R.string.text_me_b4d362) },
        source = serverLabel.ifBlank { server.removePrefix("http://").removePrefix("https://") },
        avatarUrl = avatarUrl,
        stats = listOf("${playlists.size}" to appString(R.string.text_playlists_77b69f), "${artists.size}" to appString(R.string.text_artists_1528d8)),
        actionLabel = if (onEditProfile != null) appString(R.string.text_edit_profile_15141e) else appString(R.string.text_settings_c7f73b),
        onAction = onEditProfile ?: onOpenSettings,
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth - gutter * 2 >= WideProfile
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = pagePadding(contentPadding, top = TabletMetrics.WindowInset),
        verticalArrangement = Arrangement.spacedBy(PageMetrics.SectionGap),
    ) {
        item(key = "identity") {
            Box(Modifier.fillMaxWidth()) {
                Banner(bannerUrl, if (wide) 240.dp else 200.dp, gutter, onBack, onOpenSettings)
                if (wide) WideIdentity(identity, 240.dp) else NarrowIdentity(identity, 200.dp)
            }
        }

        if (artists.isNotEmpty()) {
            item(key = "artists") {
                PageSection(appString(R.string.text_top_artists_4920f8)) {
                    AdaptiveShelf(artists, bleed = gutter) { artist, width ->
                        ArtistCircle(artist, onClick = { onOpenDetail("artist", artist.id) }, width = width)
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item(key = "playlists") {
                PageSection(appString(R.string.text_your_playlists_df03eb)) {
                    AdaptiveShelf(playlists, bleed = gutter) { playlist, width ->
                        PlaylistCard(playlist, onClick = { onOpenDetail("playlist", playlist.id) }, width = width)
                    }
                }
            }
        }
    }
    }
}

private class ProfileIdentity(
    val name: String,
    val initials: String,
    val source: String,
    val avatarUrl: String,
    val stats: List<Pair<String, String>>,
    val actionLabel: String,
    val onAction: () -> Unit,
)

@Composable
private fun Banner(bannerUrl: String, height: Dp, gutter: Dp, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val inset = gutter / 2
    Box(
        Modifier
            .layout { measurable, constraints ->
                val extra = inset.roundToPx()
                val width = constraints.maxWidth + extra * 2
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
            }
            .height(height)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)))),
    ) {
        if (bannerUrl.isNotBlank()) {
            Artwork(bannerUrl, MaterialTheme.colorScheme.secondary, Modifier.matchParentSize(), corner = 0.dp)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.25f)))))
        }
        Row(Modifier.fillMaxWidth().padding(inset), verticalAlignment = Alignment.CenterVertically) {
            BannerButton(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), onBack)
            Spacer(Modifier.weight(1f))
            BannerButton(Icons.Outlined.Settings, appString(R.string.text_settings_c7f73b), onOpenSettings)
        }
    }
}

@Composable
private fun BannerButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClickLabel = label, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Avatar(identity: ProfileIdentity, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
            .border(4.dp, MaterialTheme.colorScheme.background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (identity.avatarUrl.isNotBlank()) {
            Artwork(identity.avatarUrl, MaterialTheme.colorScheme.primary, Modifier.matchParentSize().padding(4.dp), corner = size / 2)
        } else {
            Text(identity.initials, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun WideIdentity(identity: ProfileIdentity, bannerHeight: Dp) {
    val avatar = 132.dp
    Row(
        Modifier.fillMaxWidth().padding(top = bannerHeight - avatar / 2),
        verticalAlignment = Alignment.Bottom,
    ) {
        Avatar(identity, avatar)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
            Text(identity.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(identity.source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            identity.stats.forEach { (value, label) -> Stat(value, label) }
            ProfileAction(identity)
        }
    }
}

@Composable
private fun NarrowIdentity(identity: ProfileIdentity, bannerHeight: Dp) {
    val avatar = 112.dp
    Column(
        Modifier.fillMaxWidth().padding(top = bannerHeight - avatar / 2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(identity, avatar)
        Spacer(Modifier.height(10.dp))
        Text(identity.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(identity.source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            identity.stats.forEach { (value, label) -> Stat(value, label) }
        }
        Spacer(Modifier.height(16.dp))
        ProfileAction(identity)
    }
}

@Composable
private fun ProfileAction(identity: ProfileIdentity) {
    Button(
        onClick = identity.onAction,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(identity.actionLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
