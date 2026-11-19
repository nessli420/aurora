package com.aurora.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import com.aurora.music.ui.theme.GlassColors
import com.aurora.music.ui.theme.glassIsDark
import com.aurora.music.ui.theme.isGlassTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// a song's menu: the theme's large corners, the rest scrolled behind faded edges
@Composable
fun SongMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val glass = isGlassTheme()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.large,
        // a menu is its own window, so under glass it is a frosted sheet with the rim rather than live glass
        containerColor = if (glass) GlassColors.surface(glassIsDark(), live = false) else MenuDefaults.containerColor,
        border = if (glass) BorderStroke(1.dp, GlassColors.rim(glassIsDark()))
            else BorderStroke(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        val scroll = rememberScrollState()
        // tall enough to cut a row in half, so the rest reads as scrollable
        Column(Modifier.heightIn(max = 380.dp).fadedEdges(scroll).verticalScroll(scroll), content = content)
    }
}

// a group of rows that rises in a beat after the one above it, ruled off from it once it has rows of its own
@Composable
fun SongMenuSection(index: Int, content: @Composable ColumnScope.() -> Unit) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 45L)
        shown.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
    }
    Layout(
        content = {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Column(content = content)
        },
        modifier = Modifier.graphicsLayer {
            alpha = shown.value
            translationY = (1f - shown.value) * 10.dp.toPx()
        },
    ) { (line, rows), constraints ->
        val body = rows.measure(constraints)
        val rule = if (index == 0 || body.height == 0) null else line.measure(constraints.copy(minHeight = 0))
        val top = rule?.height ?: 0
        layout(body.width, top + body.height) {
            rule?.place(0, 0)
            body.place(0, top)
        }
    }
}

private fun Modifier.fadedEdges(scroll: ScrollState) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = 28.dp.toPx()
        val above = (scroll.value / edge).coerceIn(0f, 1f)
        val below = ((scroll.maxValue - scroll.value) / edge).coerceIn(0f, 1f)
        if (above > 0f) drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 1f - above), Color.Black), endY = edge),
            size = Size(size.width, edge), blendMode = BlendMode.DstIn)
        if (below > 0f) drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1f - below)), startY = size.height - edge, endY = size.height),
            topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge), blendMode = BlendMode.DstIn)
    }
