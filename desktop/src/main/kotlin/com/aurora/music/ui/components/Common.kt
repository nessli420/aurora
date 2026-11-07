package com.aurora.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.localization.appString
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.cornerDp
import com.aurora.music.ui.theme.auroraBackdrop

val LocalCoverHover = staticCompositionLocalOf { mutableStateOf<String?>(null) }

// the hovered cover lends its colour to the app accent (see AuroraRoot)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.coverHover(url: String): Modifier {
    if (url.isBlank()) return this
    val hover = LocalCoverHover.current
    DisposableEffect(hover, url) { onDispose { if (hover.value == url) hover.value = null } }
    return onPointerEvent(PointerEventType.Enter) { hover.value = url }
        .onPointerEvent(PointerEventType.Exit) { if (hover.value == url) hover.value = null }
}

@Composable
fun Artwork(
    url: String,
    accent: Color,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Crop,
    animated: CoverKey? = null,
) {
    val ui = LocalUiPrefs.current
    val artCorner = when {
        corner == 0.dp -> corner
        ui.themeStyle == ThemeStyle.AURORA -> cornerDp(ui.cornerStyle, corner)
        corner >= 22.dp -> corner
        ui.themeStyle == ThemeStyle.RETRO -> 2.dp
        ui.themeStyle == ThemeStyle.AERO -> 5.dp
        else -> 18.dp
    }
    val placeholder = Brush.linearGradient(
        listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))
    )
    Box(
        modifier = modifier.clip(RoundedCornerShape(artCorner)).background(placeholder),
        contentAlignment = Alignment.Center,
    ) {
        // the box already paints the placeholder, and a subcomposition per cover is what makes grids scroll heavily
        val context = LocalPlatformContext.current
        AsyncImage(
            model = remember(url, context) { ImageRequest.Builder(context).data(url).crossfade(true).build() },
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
        if (animated != null) AnimatedCoverLayer(animated, Modifier.fillMaxSize())
    }
}

@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(top = PageMetrics.HeaderTop).heightIn(min = PageMetrics.HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            val hang = 12.dp
            IconButton(
                onClick = onBack,
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width - hang.roundToPx(), placeable.height) { placeable.place(-hang.roundToPx(), 0) }
                }.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    count: Int? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                    )
                ),
        )
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (count != null) {
                Spacer(Modifier.width(10.dp))
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null && onAction != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
fun PageSection(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    count: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(title, action = action, onAction = onAction, count = count)
        Spacer(Modifier.height(PageMetrics.HeaderToContent))
        content()
    }
}

@Composable
fun Eyebrow(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = color,
        letterSpacing = 1.5.sp,
        modifier = modifier,
    )
}

@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    if (LocalUiPrefs.current.themeStyle != ThemeStyle.AURORA) {
        Box(modifier.fillMaxSize().auroraBackdrop())
        return
    }
    val base = MaterialTheme.colorScheme.background
    val top = MaterialTheme.colorScheme.primary
    val bottom = MaterialTheme.colorScheme.tertiary
    Box(modifier.fillMaxSize().background(base)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(top.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(120f, -80f),
                    radius = 1100f,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.55f to Color.Transparent,
                        1f to bottom.copy(alpha = 0.12f),
                    )
                )
            )
        )
    }
}

fun formatTime(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    val h = safe / 3600
    val m = safe % 3600 / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTime(totalSec: Float): String = formatTime(totalSec.toInt())
