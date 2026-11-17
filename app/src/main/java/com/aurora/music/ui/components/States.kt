package com.aurora.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aurora.music.ui.theme.FilledTonalButton
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.Space
import com.aurora.music.ui.theme.paperFrame

// one look for every empty, missing or failed list: icon, line, optional hint and action
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.section, vertical = Space.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).paperFrame(CircleShape, { background(colors.primary.copy(alpha = 0.12f)) }, PaperTokens.plate),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = colors.primary, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(Space.l))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(Space.xs))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp))
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(Space.l))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

// the page-level loader, so every screen waits the same way
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = Space.section), contentAlignment = Alignment.Center) {
        LottieLoader(Modifier.size(64.dp))
    }
}
