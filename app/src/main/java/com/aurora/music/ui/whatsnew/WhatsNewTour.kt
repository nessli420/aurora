package com.aurora.music.ui.whatsnew

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.FilterAlt
import com.aurora.music.data.TrackSort
import com.aurora.music.ui.components.label
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.core.graphics.drawable.toBitmap
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.WhatsNew
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.label
import com.aurora.music.ui.components.glass.GlassToggle
import com.aurora.music.ui.screens.settings.SettingsDestinations
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraBackdrop
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.isGlassTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The update tour: the Aurora mark draws itself and docks beside the heading, the splash with the version
 * rises in, then one page per new feature and a wrap-up with smaller fixes. Everything is drawn from the
 * current theme's colours, type, corners, panels and backdrop. [releases] run oldest first.
 */
@Composable
fun WhatsNewTour(
    releases: List<WhatsNewRelease>,
    page: Int,
    onPageChange: (Int) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onTry: (String) -> Unit,
    onClose: () -> Unit,
) {
    val features = remember(releases) { releases.flatMap { it.features } }
    val fixes = remember(releases) { releases.flatMap { it.fixes } }
    val version = WhatsNew.shortVersion(releases.last().version)
    val count = features.size + 2
    val pager = rememberPagerState(initialPage = page.coerceIn(0, count - 1)) { count }
    // the logo intro plays once, when the tour opens on its splash
    var introDone by rememberSaveable { mutableStateOf(page != 0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(onPageChange) }
    fun go(to: Int) { scope.launch { pager.animateScrollToPage(to) } }
    BackHandler { if (pager.currentPage > 0) go(pager.currentPage - 1) else onClose() }

    // owns every touch so nothing reaches the app underneath
    Box(Modifier.fillMaxSize().auroraBackdrop()
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = introDone) { index ->
            when (index) {
                0 -> SplashPage(version, features, enabled, onEnabledChange, intro = !introDone, onIntroDone = { introDone = true },
                    onStart = { go(1) }, onSkip = onClose)
                count - 1 -> DonePage(version, fixes, enabled, onEnabledChange, onClose)
                else -> {
                    val feature = features[index - 1]
                    FeaturePage(feature, index - 1, features.size, onSkip = onClose,
                        onTry = feature.route?.let { route -> { onTry(route) } }, onNext = { go(index + 1) })
                }
            }
        }
    }
}

/** Shown while "Try it" has the real screen open. Sits at the top: the bottom belongs to the mini player and nav bar. */
@Composable
fun WhatsNewReturnPill(onResume: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraLarge
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp),
        contentAlignment = Alignment.TopCenter) {
        Row(Modifier.shadow(12.dp, shape).auroraPanel(shape, emphasized = true).padding(start = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onResume) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp), tint = colors.primary)
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.whats_new_resume), color = colors.primary, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, appString(R.string.whats_new_close), tint = colors.onSurface) }
        }
    }
}

// --- the Aurora mark, drawn stroke by stroke so it can write itself in ---

private val logoLetter: Path = PathParser().parsePathString("M9,53 L28.4,11.3 Q32,3.8 35.6,11.3 L55,53").toPath()
private val logoWave: Path = PathParser().parsePathString("M16,39.5 C27,30 37,49 48,39.5").toPath()

/** The logo in the theme's accent, [progress] 0..1 tracing the A and then the wave; [halo] glows behind it. */
@Composable
internal fun AuroraMark(modifier: Modifier, progress: () -> Float = { 1f }, halo: () -> Float = { 0f }) {
    val colors = MaterialTheme.colorScheme
    val retro = LocalUiPrefs.current.themeStyle == ThemeStyle.RETRO
    val brush = remember(colors.primary, colors.tertiary, retro) {
        if (retro) Brush.linearGradient(listOf(colors.primary, colors.primary))
        else Brush.linearGradient(listOf(colors.primary, lerp(colors.primary, colors.tertiary, 0.7f)), Offset(8f, 8f), Offset(56f, 56f))
    }
    val measure = remember { PathMeasure() }
    val letter = remember { Path() }
    val wave = remember { Path() }
    Canvas(modifier) {
        val glow = halo()
        if (glow > 0f) drawCircle(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.42f * glow), Color.Transparent),
            center, size.minDimension * 1.1f), size.minDimension * 1.1f)
        val p = progress()
        val scale = size.minDimension / 64f
        withTransform({
            translate((size.width - 64f * scale) / 2f, (size.height - 64f * scale) / 2f)
            scale(scale, scale, Offset.Zero)
        }) {
            val stroke = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun trace(source: Path, into: Path, t: Float) {
                if (t <= 0f) return
                measure.setPath(source, false)
                into.reset()
                measure.getSegment(0f, measure.length * t, into, true)
                drawPath(into, brush, style = stroke)
            }
            trace(logoLetter, letter, (p / 0.68f).coerceIn(0f, 1f))
            trace(logoWave, wave, ((p - 0.48f) / 0.52f).coerceIn(0f, 1f))
        }
    }
}

// --- theme pieces ---

/** Fades and lifts a piece in as [progress] passes its [step] of [steps]. */
private fun Modifier.reveal(progress: Animatable<Float, AnimationVector1D>, step: Int, steps: Int): Modifier = graphicsLayer {
    val span = 0.38f
    val start = if (steps <= 1) 0f else step * (1f - span) / (steps - 1)
    val t = FastOutSlowInEasing.transform(((progress.value - start) / span).coerceIn(0f, 1f))
    alpha = t
    translationY = (1f - t) * 28.dp.toPx()
}

@Composable
private fun buttonShape(): Shape = when (LocalUiPrefs.current.themeStyle) {
    ThemeStyle.RETRO, ThemeStyle.AERO -> MaterialTheme.shapes.medium
    else -> MaterialTheme.shapes.extraLarge
}

/** The primary action in the theme's own style: glossy on Aero, framed on Retro, a solid accent elsewhere. */
@Composable
private fun TourButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val style = LocalUiPrefs.current.themeStyle
    val shape = buttonShape()
    val surface = when {
        !primary -> Modifier.auroraPanel(shape)
        style == ThemeStyle.AERO -> Modifier.clip(shape).background(Brush.verticalGradient(
            0f to lerp(colors.primary, Color.White, 0.42f), 0.49f to lerp(colors.primary, Color.White, 0.14f),
            0.5f to colors.primary, 1f to lerp(colors.primary, Color.Black, 0.12f),
        )).border(1.dp, lerp(colors.primary, Color.Black, 0.3f), shape)
        style == ThemeStyle.RETRO -> Modifier.clip(shape).background(colors.primary)
            .border(1.5.dp, colors.onBackground.copy(alpha = 0.45f), shape)
        else -> Modifier.clip(shape).background(colors.primary)
    }
    Box(modifier.height(54.dp).then(surface).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(if (style == ThemeStyle.RETRO) text.uppercase(Locale.getDefault()) else text,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = if (primary) colors.onPrimary else colors.onSurface, maxLines = 1)
    }
}

@Composable
private fun ThemedSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    if (isGlassTheme()) GlassToggle(checked, onChange)
    else if (LocalUiPrefs.current.themeStyle == ThemeStyle.PAPER) com.aurora.music.ui.theme.PaperSwitch(checked, null)
    else Switch(checked = checked, onCheckedChange = null)
}

@Composable
private fun TourColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp, vertical = 16.dp), content = content)
    }
}

@Composable
private fun Kicker(text: String, modifier: Modifier = Modifier) {
    val retro = LocalUiPrefs.current.themeStyle == ThemeStyle.RETRO
    Text(text.uppercase(Locale.getDefault()), modifier, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        letterSpacing = if (retro) 0.sp else 1.6.sp, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PageTitle(text: String, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography.headlineLarge
    Text(text, modifier, style = type.copy(fontSize = type.fontSize * 1.15f, lineHeight = type.lineHeight * 1.12f), fontWeight = FontWeight.Bold)
}

// --- pages ---

@Composable
private fun SplashPage(
    version: String,
    features: List<WhatsNewFeature>,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    intro: Boolean,
    onIntroDone: () -> Unit,
    onStart: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val style = LocalUiPrefs.current.themeStyle
    val draw = remember { Animatable(if (intro) 0f else 1f) }
    val dock = remember { Animatable(if (intro) 0f else 1f) }
    val reveal = remember { Animatable(if (intro) 0f else 1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var target by remember { mutableStateOf<Rect?>(null) }
    val docking by remember { derivedStateOf { dock.value < 1f } }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!intro) return@LaunchedEffect
        draw.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        delay(160)
        coroutineScope {
            launch { dock.animateTo(1f, tween(720, easing = FastOutSlowInEasing)) }
            delay(260)
            reveal.animateTo(1f, tween(1000, easing = LinearEasing))
        }
        onIntroDone()
    }
    fun finishIntro() {
        scope.launch { draw.snapTo(1f); dock.snapTo(1f); reveal.snapTo(1f); onIntroDone() }
    }

    val listed = features.take(MAX_LISTED)
    val steps = 6 + listed.size
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot(); box = it.size }) {
        if (style != ThemeStyle.RETRO) Box(Modifier.align(Alignment.TopCenter).offset(y = (-240).dp).size(640.dp)
            .graphicsLayer { alpha = reveal.value }
            .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.30f), colors.tertiary.copy(alpha = 0.08f), Color.Transparent))))
        TourColumn {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(40.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the docked logo; the intro's big mark flies into this spot
                    AuroraMark(Modifier.size(30.dp).onGloballyPositioned { target = it.boundsInRoot() }
                        .graphicsLayer { alpha = if (dock.value >= 1f) 1f else 0f })
                    Spacer(Modifier.width(12.dp))
                    Kicker(appString(R.string.whats_new_kicker), Modifier.reveal(reveal, 0, steps))
                }
                val display = MaterialTheme.typography.displayLarge
                Text(version, Modifier.padding(top = 10.dp).reveal(reveal, 1, steps),
                    style = display.copy(fontSize = display.fontSize * if (version.length <= 4) 3.2f else 2.4f,
                        lineHeight = display.fontSize * 3.1f),
                    fontWeight = FontWeight.ExtraBold, letterSpacing = if (style == ThemeStyle.RETRO) 0.sp else (-6).sp,
                    maxLines = 1, softWrap = false)
                PageTitle(appString(R.string.whats_new_title), Modifier.padding(top = 18.dp).reveal(reveal, 2, steps))
                Text(appString(R.string.whats_new_summary), Modifier.padding(top = 8.dp).reveal(reveal, 3, steps),
                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                listed.forEachIndexed { i, feature ->
                    Row(Modifier.padding(vertical = 8.dp).reveal(reveal, 4 + i, steps), verticalAlignment = Alignment.CenterVertically) {
                        Icon(feature.icon, null, Modifier.size(22.dp), tint = colors.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(appString(feature.title), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (features.size > MAX_LISTED) Text(appString(R.string.whats_new_more, features.size - MAX_LISTED),
                    Modifier.padding(start = 36.dp, top = 6.dp).reveal(reveal, steps - 2, steps),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.reveal(reveal, steps - 1, steps)) {
                TourButton(appString(R.string.whats_new_start), Modifier.fillMaxWidth(), onClick = onStart)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                        .toggleable(value = !enabled, role = Role.Checkbox) { onEnabledChange(!it) }
                        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = !enabled, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(appString(R.string.whats_new_dont_show), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    TextButton(onClick = onSkip) { Text(appString(R.string.whats_new_skip), color = colors.onSurfaceVariant) }
                }
            }
        }

        if (docking) {
            // the big mark: writes itself in at the centre, then flies into the docked spot beside the heading
            val big = 132.dp
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = appString(R.string.whats_new_skip)) { finishIntro() })
            AuroraMark(Modifier.size(big).graphicsLayer {
                val d = FastOutSlowInEasing.transform(dock.value)
                val end = target?.translate(-origin)
                val startX = box.width / 2f
                val startY = box.height * 0.44f
                val cx = lerpFloat(startX, end?.center?.x ?: startX, d)
                val cy = lerpFloat(startY, end?.center?.y ?: startY, d)
                translationX = cx - size.width / 2f
                translationY = cy - size.height / 2f
                val s = lerpFloat(1f, (end?.width ?: size.width) / size.width, d)
                scaleX = s
                scaleY = s
            }, progress = { draw.value }, halo = { draw.value * (1f - dock.value) })
        }
    }
}

@Composable
private fun FeaturePage(feature: WhatsNewFeature, index: Int, total: Int, onSkip: () -> Unit, onTry: (() -> Unit)?, onNext: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TourColumn {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressBars(index, total, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onSkip) { Text(appString(R.string.whats_new_skip), color = colors.onSurfaceVariant) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(300.dp).auroraPanel(MaterialTheme.shapes.extraLarge)) {
                val preview = feature.preview
                if (preview != null) preview() else IconArt(feature.icon)
            }
            Spacer(Modifier.height(28.dp))
            Kicker(appString(R.string.whats_new_badge))
            PageTitle(appString(feature.title), Modifier.padding(top = 8.dp))
            Text(appString(feature.body), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onTry != null) TourButton(appString(R.string.whats_new_try), Modifier.weight(1f), primary = false, onClick = onTry)
            TourButton(appString(R.string.onboarding_next), Modifier.weight(1f), onClick = onNext)
        }
    }
}

@Composable
private fun DonePage(version: String, fixes: List<Int>, enabled: Boolean, onEnabledChange: (Boolean) -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val badge = if (LocalUiPrefs.current.themeStyle == ThemeStyle.RETRO) MaterialTheme.shapes.medium else CircleShape
    TourColumn {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(48.dp))
            Box(Modifier.size(72.dp).clip(badge).background(colors.primary.copy(alpha = 0.14f))
                .border(1.5.dp, colors.primary, badge), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(32.dp), tint = colors.primary)
            }
            PageTitle(appString(R.string.whats_new_done_title), Modifier.padding(top = 24.dp))
            Text(appString(R.string.text_version_d2f210, version), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant)
            if (fixes.isNotEmpty()) {
                Column(Modifier.padding(top = 28.dp).fillMaxWidth().auroraPanel(MaterialTheme.shapes.large).padding(20.dp)) {
                    Text(appString(R.string.whats_new_fixes).uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
                    fixes.forEach { fix ->
                        Row(Modifier.padding(top = 12.dp)) {
                            Box(Modifier.padding(top = 8.dp).size(6.dp).clip(badge).background(colors.primary))
                            Spacer(Modifier.width(12.dp))
                            Text(appString(fix), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().auroraPanel(MaterialTheme.shapes.large)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.whats_new_show_setting), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(appString(R.string.whats_new_show_setting_detail), Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                ThemedSwitch(enabled, onEnabledChange)
            }
            Spacer(Modifier.height(16.dp))
        }
        TourButton(appString(R.string.whats_new_finish), Modifier.fillMaxWidth().padding(top = 12.dp), onClick = onClose)
    }
}

@Composable
private fun ProgressBars(index: Int, total: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = appString(R.string.whats_new_progress, index + 1, total)
    val shape = MaterialTheme.shapes.extraSmall
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(shape)
                .background(if (i <= index) colors.primary else colors.surfaceContainerHighest))
        }
    }
}

@Composable
private fun IconArt(icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    val retro = LocalUiPrefs.current.themeStyle == ThemeStyle.RETRO
    val ring = if (retro) MaterialTheme.shapes.large else CircleShape
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(colors.primary.copy(alpha = if (retro) 0.10f else 0.20f), Color.Transparent))),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(136.dp).clip(ring).background(colors.primary.copy(alpha = 0.08f))
            .border(1.dp, colors.primary.copy(alpha = 0.35f), ring), contentAlignment = Alignment.Center) {
            Box(Modifier.size(88.dp).clip(ring).background(colors.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(40.dp), tint = colors.primary)
            }
        }
    }
}

private fun highlight(text: String, word: String, color: Color): AnnotatedString = buildAnnotatedString {
    val at = text.indexOf(word, ignoreCase = true)
    if (at < 0) { append(text); return@buildAnnotatedString }
    append(text.substring(0, at))
    withStyle(SpanStyle(color = color)) { append(text.substring(at, at + word.length)) }
    append(text.substring(at + word.length))
}

@Composable
private fun PreviewRow(title: AnnotatedString, subtitle: AnnotatedString, trailing: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHighest.copy(alpha = 0.7f))
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
        trailing()
    }
}

@Composable
internal fun SettingsSearchPreview() {
    val colors = MaterialTheme.colorScheme
    val word = appString(R.string.text_crossfade_00acfc)
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(appString(R.string.text_settings_c7f73b), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val field = MaterialTheme.shapes.extraLarge
        Row(Modifier.fillMaxWidth().height(52.dp).clip(field).background(colors.surfaceContainerHighest)
            .border(1.5.dp, colors.primary, field).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(word.lowercase(Locale.getDefault()), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.padding(start = 2.dp).width(2.dp).height(20.dp).background(colors.primary))
        }
        val playback = SettingsDestinations.playback
        PreviewRow(highlight(word, word, colors.primary), AnnotatedString(playback.label)) {
            ThemedSwitch(true) {}
        }
        PreviewRow(AnnotatedString(playback.label), highlight(playback.description, word, colors.primary)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LibraryTabsPreview() {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(appString(R.string.library_tabs_customize), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        PreviewTab(LibraryFilter.SONGS.label, lifted = true)
        PreviewTab(LibraryFilter.ALBUMS.label)
        PreviewTab(LibraryFilter.ARTISTS.label)
        PreviewTab(LibraryFilter.ALL.label, hidden = true)
    }
}

@Composable
private fun PreviewTab(label: String, lifted: Boolean = false, hidden: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val lift = if (lifted) Modifier.graphicsLayer { translationX = 10.dp.toPx(); rotationZ = -1.5f }.shadow(10.dp, shape)
        .border(1.5.dp, colors.primary, shape) else Modifier
    Row(Modifier.fillMaxWidth().then(lift).height(50.dp).clip(shape)
        .background(if (lifted) colors.surfaceContainerHighest else colors.surfaceContainerHighest.copy(alpha = 0.6f))
        .alpha(if (hidden) 0.5f else 1f).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.DragIndicator, null, Modifier.size(20.dp), tint = if (lifted) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (lifted) FontWeight.SemiBold else null)
        Icon(if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun TrackSortPreview() {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(18.dp)) {
        Text(appString(R.string.text_liked_songs_58c3a9), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        Icon(Icons.Filled.FilterAlt, null, Modifier.align(Alignment.TopEnd).size(26.dp), tint = colors.primary)
        // the open sort menu, the active choice in the accent with its direction arrow
        Column(Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.78f).shadow(12.dp, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHighest).padding(vertical = 8.dp)) {
            Text(appString(R.string.track_sort_heading), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            listOf(TrackSort.CUSTOM, TrackSort.TITLE, TrackSort.ARTIST, TrackSort.RECENTLY_ADDED, TrackSort.DURATION).forEach { sort ->
                val active = sort == TrackSort.RECENTLY_ADDED
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sort.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (active) FontWeight.SemiBold else null, color = if (active) colors.primary else colors.onSurface)
                    if (active) Icon(Icons.Filled.ArrowDownward, null, Modifier.size(18.dp), tint = colors.primary)
                }
            }
        }
    }
}

// the queue as autoplay leaves it: your own song, then its picks under their own heading
@Composable
internal fun AutoplayPreview() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(appString(R.string.queue_track_count, appPlural(R.plurals.track_count, 1)), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        PreviewRow(AnnotatedString("Golden Hour"), AnnotatedString("Mira Vale")) {}
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AllInclusive, null, Modifier.size(18.dp), tint = colors.primary)
            Spacer(Modifier.width(6.dp))
            Text(appString(R.string.autoplay_heading), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Text(appString(R.string.autoplay_heading_detail), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp))
        listOf("Night Drive" to "Aurelia", "Glass Hours" to "The Lanterns", "Low Tide" to "Sol Marin").forEach { (title, artist) ->
            PreviewRow(AnnotatedString(title), AnnotatedString(artist)) {}
        }
    }
}

/** A small data-sheet page drawn in the paper theme itself, whatever theme the tour is showing in. */
@Composable
internal fun PaperThemePreview() {
    val prefs = LocalUiPrefs.current
    androidx.compose.runtime.CompositionLocalProvider(LocalUiPrefs provides prefs.copy(themeStyle = ThemeStyle.PAPER)) {
        MaterialTheme(
            colorScheme = com.aurora.music.ui.theme.styleColorScheme(ThemeStyle.PAPER, false),
            typography = com.aurora.music.ui.theme.auroraTypography(0.92f, ThemeStyle.PAPER),
            shapes = com.aurora.music.ui.theme.auroraShapes(com.aurora.music.data.CornerStyle.DEFAULT, ThemeStyle.PAPER),
        ) {
            val colors = MaterialTheme.colorScheme
            Column(Modifier.fillMaxSize().background(colors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Aurora, music player", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onBackground)
                    Text(appString(R.string.theme_paper_name), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.onBackground))
                Text(appString(R.string.text_library_b8100f), style = MaterialTheme.typography.headlineLarge, color = colors.onBackground)
                // the site's spec table: mono field names, values in archivo, split on hairlines inside an ink frame
                Column(Modifier.fillMaxWidth().border(1.dp, colors.onBackground)) {
                    listOf(appString(R.string.track_sort_title) to "Erica Western Teleport", appString(R.string.track_sort_artist) to "Emperor X",
                        appString(R.string.track_sort_duration) to "3:33").forEachIndexed { i, (field, value) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(field, Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.background(colors.primary).padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Text(appString(R.string.text_play_5d12bd), style = MaterialTheme.typography.labelLarge, color = colors.onPrimary)
                    }
                    Box(Modifier.border(1.dp, colors.onBackground).padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Text(appString(R.string.text_shuffle_5b772b), style = MaterialTheme.typography.labelLarge, color = colors.onBackground)
                    }
                    Spacer(Modifier.weight(1f))
                    com.aurora.music.ui.theme.PaperSwitch(true, null)
                }
            }
        }
    }
}

/** The Paper launcher icon, square as Paper draws it, over the app icon picker with Paper chosen. */
@Composable
internal fun PaperIconPreview() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val smallPx = with(density) { 36.dp.roundToPx() }
    val icons = remember(smallPx) {
        com.aurora.music.ui.appicon.AppIcon.entries.map { icon ->
            icon to androidx.core.content.ContextCompat.getDrawable(context, icon.icon)
                ?.toBitmap(smallPx, smallPx)?.asImageBitmap()
        }
    }
    val paper = com.aurora.music.ui.theme.styleColorScheme(ThemeStyle.PAPER, false)
    Column(
        Modifier.fillMaxSize().background(paper.background).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        // an adaptive icon shows the middle 72 of its 108 canvas, so the layers are drawn 1.5x and cropped square
        Box(Modifier.size(104.dp).clip(androidx.compose.ui.graphics.RectangleShape), contentAlignment = Alignment.Center) {
            listOf(R.drawable.ic_launcher_paper_background, R.drawable.ic_launcher_paper_foreground).forEach { layer ->
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(layer), contentDescription = null,
                    modifier = Modifier.requiredSize(156.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(appString(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = paper.onBackground)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(paper.outlineVariant))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icons.forEach { (icon, bitmap) ->
                val chosen = icon == com.aurora.music.ui.appicon.AppIcon.PAPER
                Box(
                    Modifier.border(if (chosen) 2.dp else 1.dp, if (chosen) paper.primary else paper.outlineVariant).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) androidx.compose.foundation.Image(bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

private const val MAX_LISTED = 5
