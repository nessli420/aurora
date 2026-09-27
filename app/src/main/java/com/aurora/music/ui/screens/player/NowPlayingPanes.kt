package com.aurora.music.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel

enum class PlayerPane(val icon: ImageVector, private val labelRes: Int) {
    LYRICS(Icons.Filled.Lyrics, R.string.text_lyrics_8670cb),
    QUEUE(Icons.AutoMirrored.Filled.QueueMusic, R.string.text_queue_d325fc);

    val label: String get() = appString(labelRes)
}

@Composable
private fun TabGroup(modifier: Modifier, content: @Composable () -> Unit) {
    val scale = LocalUiPrefs.current.tabletPanelScale
    Row(
        modifier
            .semantics { contentDescription = appString(R.string.tablet_player_panes) }
            .selectableGroup()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(3.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun PaneSwitcher(selected: PlayerPane?, onSelect: (PlayerPane) -> Unit, modifier: Modifier = Modifier, panes: List<PlayerPane> = PlayerPane.entries) {
    TabGroup(modifier) {
        panes.forEach { pane -> PaneTab(pane.label, pane.icon, pane == selected) { onSelect(pane) } }
    }
}

@Composable
fun PlayerTabs(selected: PlayerPane?, onSelect: (PlayerPane?) -> Unit, modifier: Modifier = Modifier) {
    TabGroup(modifier) {
        PaneTab(appString(R.string.tablet_now_playing), null, selected == null) { onSelect(null) }
        PlayerPane.entries.forEach { pane -> PaneTab(pane.label, pane.icon, pane == selected) { onSelect(pane) } }
    }
}

@Composable
internal fun PaneTab(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    val scale = LocalUiPrefs.current.tabletPanelScale
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(180), label = "paneTab",
    )
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(CircleShape).background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 40.dp * scale).padding(horizontal = 12.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(16.dp * scale.coerceAtLeast(0.9f)))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = if (scale < 0.9f) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NowPlayingSidePanel(
    pane: PlayerPane,
    onSelect: (PlayerPane) -> Unit,
    onClose: () -> Unit,
    content: @Composable (PlayerPane, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable (PlayerPane) -> Unit = {},
) {
    val spacing = LocalUiPrefs.current.tabletPanelSpacing.dp
    Column(modifier.auroraPanel(MaterialTheme.shapes.extraLarge).padding(spacing.coerceAtLeast(4.dp))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PaneSwitcher(pane, onSelect)
            Spacer(Modifier.weight(1f))
            actions(pane)
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, appString(R.string.tablet_close_panel), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(spacing.coerceAtLeast(4.dp)))
        AnimatedContent(
            targetState = pane,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "sidePanelPane",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { target ->
            Box(Modifier.fillMaxSize()) { content(target, Modifier.fillMaxSize()) }
        }
    }
}
