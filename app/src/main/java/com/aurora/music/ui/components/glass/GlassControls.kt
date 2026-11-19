package com.aurora.music.ui.components.glass

// tab bar, button and toggle adapted from kyant0/AndroidLiquidGlass catalog components (apache-2.0)

import com.aurora.music.ui.components.longPressFirst
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.luminance
import com.aurora.music.ui.theme.GlassColors
import com.aurora.music.ui.theme.LocalGlassAmbient
import com.aurora.music.ui.theme.glassIsDark
import com.aurora.music.ui.theme.liquidGlass
import com.aurora.music.ui.theme.liveGlassSupported
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

data class GlassTab(val label: String, val icon: ImageVector)

private val LocalTabScale = staticCompositionLocalOf { { 1f } }

// drag the selection bubble across the bar; it magnifies and tints the tab beneath it
@Composable
fun GlassTabBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    onLongPress: ((Int) -> Unit)? = null,
) {
    val dark = glassIsDark()
    val live = backdrop != null && liveGlassSupported
    val source = if (live) backdrop!! else emptyBackdrop()
    val accent = MaterialTheme.colorScheme.primary
    val container = GlassColors.surface(dark, live)
    val count = tabs.size.coerceAtLeast(1)
    val current by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState(onSelect)
    val tabsBackdrop = rememberLayerBackdrop()

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val hold by rememberUpdatedState(onLongPress)
    BoxWithConstraints(modifier.longPressFirst(count, onLongPress?.let { { at, size ->
        val x = if (rtl) size.width - at.x else at.x
        hold?.invoke((x / size.width * count).toInt().coerceIn(0, count - 1))
    } }), contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 8.dp.toPx()) / count }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
        val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val scope = rememberCoroutineScope()
        val drag = remember(scope, count) {
            DampedDragAnimation(
                scope = scope,
                initialValue = selectedIndex.coerceIn(0, count - 1).toFloat(),
                valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = .001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    animateToValue(target.toFloat())
                    scope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, .5f)) }
                    if (target != current) select(target)
                },
                onDrag = { _, amount ->
                    updateValue((targetValue + amount.x / tabWidth * if (ltr) 1f else -1f).fastCoerceIn(0f, (count - 1).toFloat()))
                    scope.launch { offsetAnimation.snapTo(offsetAnimation.value + amount.x) }
                },
            )
        }
        LaunchedEffect(drag) {
            snapshotFlow { current }.drop(1).collect { index ->
                if (index in 0 until count && index.toFloat() != drag.targetValue) drag.animateToValue(index.toFloat())
            }
        }
        val shown by animateFloatAsState(if (selectedIndex in 0 until count) 1f else 0f, label = "glassTabIndicator")
        val highlight = remember(scope) {
            InteractiveHighlight(scope) { size, _ ->
                Offset(if (ltr) (drag.value + .5f) * tabWidth + panelOffset else size.width - (drag.value + .5f) * tabWidth + panelOffset, size.height / 2f)
            }
        }
        val content: @Composable RowScope.(tinted: Boolean) -> Unit = { tinted ->
            tabs.forEachIndexed { i, tab ->
                GlassTabItem(tab, selected = i == selectedIndex, tinted = tinted) {
                    if (i != current) {
                        drag.animateToValue(i.toFloat())
                        select(i)
                    }
                }
            }
        }

        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .liquidGlass(source.takeIf { live }, Capsule(), layerBlock = {
                    val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, drag.pressProgress)
                    scaleX = scale
                    scaleY = scale
                })
                .then(highlight.modifier)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(false) }

        CompositionLocalProvider(LocalTabScale provides { lerp(1f, 1.2f, drag.pressProgress) }) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = source,
                        shape = { Capsule() },
                        effects = {
                            val progress = drag.pressProgress
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                        },
                        highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                        onDrawSurface = { drawRect(container) },
                    )
                    .then(highlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
                verticalAlignment = Alignment.CenterVertically,
            ) { content(true) }
        }

        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX = if (ltr) drag.value * tabWidth + panelOffset else size.width - (drag.value + 1f) * tabWidth + panelOffset
                    alpha = shown
                }
                .then(if (selectedIndex in 0 until count) highlight.gestureModifier.then(drag.modifier) else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(source, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = drag.pressProgress
                        lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                    shadow = { Shadow(alpha = drag.pressProgress) },
                    innerShadow = { InnerShadow(radius = 8.dp * drag.pressProgress, alpha = drag.pressProgress) },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val velocity = drag.velocity / 10f
                        scaleX /= 1f - (velocity * .75f).fastCoerceIn(-.2f, .2f)
                        scaleY *= 1f - (velocity * .25f).fastCoerceIn(-.2f, .2f)
                    },
                    onDrawSurface = {
                        val progress = drag.pressProgress
                        drawRect(GlassColors.selection(dark), alpha = 1f - progress)
                        drawRect(Color.Black.copy(alpha = .03f * progress))
                    },
                )
                .height(56.dp)
                .fillMaxWidth(1f / count),
        )
    }
}

@Composable
private fun RowScope.GlassTabItem(tab: GlassTab, selected: Boolean, tinted: Boolean, onClick: () -> Unit) {
    val scale = LocalTabScale.current
    val color = if (tinted) Color.White else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .clip(Capsule())
            .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, null, tint = color, modifier = Modifier.size(24.dp))
        Text(tab.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// a round or capsule glass button that squishes toward the finger
@Composable
fun GlassButton(
    onClick: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    surface: Color = Color.Unspecified,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val hold by rememberUpdatedState(onLongClick)
    val dark = glassIsDark()
    Row(
        modifier
            .liquidGlass(backdrop, Capsule(), refraction = 16.dp, depth = 24.dp, surface = surface, layerBlock = {
                val progress = highlight.pressProgress
                val scale = lerp(1f, 1f + 4.dp.toPx() / size.height, progress)
                val maxOffset = size.minDimension
                val offset = highlight.offset
                translationX = maxOffset * tanh(.05f * offset.x / maxOffset)
                translationY = maxOffset * tanh(.05f * offset.y / maxOffset)
                val stretch = 4.dp.toPx() / size.height
                val angle = atan2(offset.y, offset.x)
                scaleX = scale + stretch * abs(cos(angle) * offset.x / size.maxDimension) * (size.width / size.height).fastCoerceAtMost(1f)
                scaleY = scale + stretch * abs(sin(angle) * offset.y / size.maxDimension) * (size.height / size.width).fastCoerceAtMost(1f)
            }, onDrawSurface = {
                if (tint != Color.Unspecified) drawRect(tint.copy(alpha = .85f))
                else if (selected) drawRect(GlassColors.selection(dark))
            })
            .longPressFirst(Unit, onLongClick?.let { { _, _ -> hold?.invoke() } })
            .clickable(interactionSource = null, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription; this.selected = selected }
            .then(highlight.modifier)
            .then(highlight.gestureModifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalGlassAmbient.current,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) = GlassButton(onClick, backdrop, modifier.size(40.dp), contentDescription = label, enabled = enabled) {
    Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp).alpha(if (enabled) 1f else .4f))
}

@Composable
fun GlassAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalGlassAmbient.current,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    val ink = if (accent.luminance() > .6f) Color.Black else Color.White
    GlassButton(onClick, backdrop, modifier.alpha(if (enabled) 1f else .5f), surface = GlassColors.accent(accent, glassIsDark()), enabled = enabled) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
        }
    }
}

// ios-style switch whose thumb turns into a lens while held
@Composable
fun GlassToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val dark = glassIsDark()
    val accent = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    val track = if (dark) Color(0xFF787880).copy(alpha = .36f) else Color(0xFF787878).copy(alpha = .2f)
    val density = LocalDensity.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20.dp.toPx() }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(checked)
    val change by rememberUpdatedState(onCheckedChange)
    var dragged by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val drag = remember(scope) {
        DampedDragAnimation(
            scope = scope, initialValue = fraction, valueRange = 0f..1f, visibilityThreshold = .001f,
            initialScale = 1f, pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                fraction = if (dragged) (if (targetValue >= .5f) 1f else 0f) else (if (latest) 0f else 1f)
                dragged = false
                if ((fraction == 1f) != latest) change(fraction == 1f)
            },
            onDrag = { _, amount ->
                if (!dragged) dragged = amount.x != 0f
                val delta = amount.x / dragWidth
                fraction = (if (ltr) fraction + delta else fraction - delta).fastCoerceIn(0f, 1f)
            },
        )
    }
    LaunchedEffect(drag) { snapshotFlow { fraction }.collect { drag.updateValue(it) } }
    LaunchedEffect(drag) {
        snapshotFlow { latest }.collect { on ->
            val target = if (on) 1f else 0f
            if (target != fraction) {
                fraction = target
                drag.animateToValue(target)
            }
        }
    }
    val trackBackdrop = rememberLayerBackdrop()
    Box(
        modifier
            .alpha(if (enabled) 1f else .45f)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind { drawRect(lerp(track, accent, drag.value)) }
                .size(64.dp, 28.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    val pad = 2.dp.toPx()
                    translationX = if (ltr) lerp(pad, pad + dragWidth, drag.value) else lerp(-pad, -(pad + dragWidth), drag.value)
                }
                .then(if (enabled) drag.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        emptyBackdrop(),
                        rememberBackdrop(trackBackdrop) { draw ->
                            val progress = drag.pressProgress
                            scale(lerp(2f / 3f, .75f, progress), lerp(0f, .75f, progress)) { draw() }
                        },
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = drag.pressProgress
                        blur(8.dp.toPx() * (1f - progress))
                        lens(5.dp.toPx() * progress, 10.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = {
                        Highlight.Ambient.copy(width = Highlight.Ambient.width / 1.5f, blurRadius = Highlight.Ambient.blurRadius / 1.5f, alpha = drag.pressProgress)
                    },
                    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = .08f)) },
                    innerShadow = { InnerShadow(radius = 4.dp * drag.pressProgress, alpha = drag.pressProgress) },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val velocity = drag.velocity / 50f
                        scaleX /= 1f - (velocity * .75f).fastCoerceIn(-.2f, .2f)
                        scaleY *= 1f - (velocity * .25f).fastCoerceIn(-.2f, .2f)
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - drag.pressProgress)) },
                )
                .size(40.dp, 24.dp),
        )
    }
}

// ios segmented control: a raised thumb springs between the options
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dark = glassIsDark()
    val track = Color(0xFF767680).copy(alpha = if (dark) .24f else .12f)
    val thumb = if (dark) Color(0xFF636366).copy(alpha = .9f) else Color.White
    val position by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), spring(.78f, 420f), label = "glassSegment")
    val scope = rememberCoroutineScope()
    val press = remember(scope) { InteractiveHighlight(scope) }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(36.dp).clip(Capsule()).background(track).padding(2.dp),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        if (selected >= 0) Box(
            Modifier
                .width(segment)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = position * segment.toPx()
                    val s = lerp(1f, 1.06f, press.pressProgress)
                    scaleX = s
                    scaleY = s
                }
                .shadow(if (dark) 0.dp else 3.dp, Capsule(), clip = false, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .12f))
                .clip(Capsule())
                .background(thumb),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight().then(press.gestureModifier)) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(Capsule())
                        .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(i) }
                        .semantics { this.selected = i == selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

private val TilePalette = listOf(
    Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFFF375F),
    Color(0xFFBF5AF2), Color(0xFF5E5CE6), Color(0xFF32ADE6), Color(0xFFFF453A), Color(0xFF8E8E93),
)

// a stable ios-settings colour per icon
fun glassTileColor(icon: ImageVector): Color = TilePalette[Math.floorMod(icon.name.hashCode(), TilePalette.size)]
