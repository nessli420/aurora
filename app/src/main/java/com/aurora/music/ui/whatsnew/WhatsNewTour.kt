package com.aurora.music.ui.whatsnew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.WhatsNew
import com.aurora.music.localization.appString
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.label
import com.aurora.music.ui.screens.settings.SettingsDestinations
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The update tour: a splash with the version, one page per new feature, then a wrap-up with smaller fixes.
 * [releases] run oldest first; the splash shows the newest version.
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
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(onPageChange) }
    fun go(to: Int) { scope.launch { pager.animateScrollToPage(to) } }
    BackHandler { if (pager.currentPage > 0) go(pager.currentPage - 1) else onClose() }

    // owns every touch so nothing reaches the app underneath
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { index ->
            when (index) {
                0 -> SplashPage(version, features, enabled, onEnabledChange, onStart = { go(1) }, onSkip = onClose)
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

/** Shown while "Try it" has the real screen open. */
@Composable
fun WhatsNewReturnPill(onResume: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // sits at the top: the bottom belongs to the mini player and nav bar
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp),
        contentAlignment = Alignment.TopCenter) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHighest, contentColor = colors.onSurface,
            shadowElevation = 12.dp, border = androidx.compose.foundation.BorderStroke(1.dp, colors.primary.copy(alpha = 0.5f))) {
            Row(Modifier.padding(start = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onResume) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp), tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(appString(R.string.whats_new_resume), color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, appString(R.string.whats_new_close)) }
            }
        }
    }
}

@Composable
private fun TourColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp, vertical = 16.dp), content = content)
    }
}

@Composable
private fun Kicker(text: String) {
    Text(text.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(54.dp), shape = RoundedCornerShape(27.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SplashPage(
    version: String,
    features: List<WhatsNewFeature>,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-240).dp).size(640.dp)
            .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.34f), colors.tertiary.copy(alpha = 0.10f), Color.Transparent))))
        TourColumn {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(44.dp))
                Kicker(appString(R.string.whats_new_kicker))
                Text(version, Modifier.padding(top = 6.dp), fontSize = if (version.length <= 4) 128.sp else 96.sp,
                    lineHeight = 124.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-6).sp, maxLines = 1, softWrap = false)
                Text(appString(R.string.whats_new_title), Modifier.padding(top = 18.dp), style = MaterialTheme.typography.headlineMedium,
                    fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Text(appString(R.string.whats_new_summary), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                features.take(MAX_LISTED).forEach { feature ->
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(feature.icon, null, Modifier.size(22.dp), tint = colors.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(appString(feature.title), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (features.size > MAX_LISTED) Text(appString(R.string.whats_new_more, features.size - MAX_LISTED),
                    Modifier.padding(start = 36.dp, top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
            BigButton(appString(R.string.whats_new_start), Modifier.fillMaxWidth(), onStart)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .toggleable(value = !enabled, role = Role.Checkbox) { onEnabledChange(!it) }
                    .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = !enabled, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(appString(R.string.whats_new_dont_show), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = onSkip) { Text(appString(R.string.whats_new_skip), color = colors.onSurfaceVariant) }
            }
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
            val shape = RoundedCornerShape(28.dp)
            Box(Modifier.fillMaxWidth().height(300.dp).clip(shape).background(colors.surfaceContainer)
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), shape)) {
                val preview = feature.preview
                if (preview != null) preview() else IconArt(feature.icon)
            }
            Spacer(Modifier.height(28.dp))
            Kicker(appString(R.string.whats_new_badge))
            Text(appString(feature.title), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineMedium,
                fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            Text(appString(feature.body), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onTry != null) OutlinedButton(onClick = onTry, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(27.dp)) {
                Text(appString(R.string.whats_new_try), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            BigButton(appString(R.string.onboarding_next), Modifier.weight(1f), onNext)
        }
    }
}

@Composable
private fun DonePage(version: String, fixes: List<Int>, enabled: Boolean, onEnabledChange: (Boolean) -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TourColumn {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(48.dp))
            Box(Modifier.size(72.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.14f))
                .border(1.5.dp, colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(32.dp), tint = colors.primary)
            }
            Text(appString(R.string.whats_new_done_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineMedium,
                fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            Text(appString(R.string.text_version_d2f210, version), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant)
            if (fixes.isNotEmpty()) {
                Column(Modifier.padding(top = 28.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainer).padding(20.dp)) {
                    Text(appString(R.string.whats_new_fixes).uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant)
                    fixes.forEach { fix ->
                        Row(Modifier.padding(top = 12.dp)) {
                            Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(colors.primary))
                            Spacer(Modifier.width(12.dp))
                            Text(appString(fix), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainer)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.whats_new_show_setting), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(appString(R.string.whats_new_show_setting_detail), Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = enabled, onCheckedChange = null)
            }
            Spacer(Modifier.height(16.dp))
        }
        BigButton(appString(R.string.whats_new_finish), Modifier.fillMaxWidth().padding(top = 12.dp), onClose)
    }
}

@Composable
private fun ProgressBars(index: Int, total: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = appString(R.string.whats_new_progress, index + 1, total)
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (i <= index) colors.primary else colors.surfaceContainerHighest))
        }
    }
}

@Composable
private fun IconArt(icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.20f), Color.Transparent))),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(136.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.08f))
            .border(1.dp, colors.primary.copy(alpha = 0.35f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
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
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainerHigh)
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
        val field = RoundedCornerShape(26.dp)
        Row(Modifier.fillMaxWidth().height(52.dp).clip(field).background(colors.surfaceContainerHighest)
            .border(1.5.dp, colors.primary, field).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(word.lowercase(Locale.getDefault()), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.padding(start = 2.dp).width(2.dp).height(20.dp).background(colors.primary))
        }
        val playback = SettingsDestinations.playback
        PreviewRow(highlight(word, word, colors.primary), AnnotatedString(playback.label)) {
            Switch(checked = true, onCheckedChange = null)
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
    val shape = RoundedCornerShape(16.dp)
    val lift = if (lifted) Modifier.graphicsLayer { translationX = 10.dp.toPx(); rotationZ = -1.5f }.shadow(10.dp, shape)
        .border(1.5.dp, colors.primary, shape) else Modifier
    Row(Modifier.fillMaxWidth().then(lift).height(50.dp).clip(shape)
        .background(if (lifted) colors.surfaceContainerHighest else colors.surfaceContainerHigh)
        .alpha(if (hidden) 0.5f else 1f).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.DragIndicator, null, Modifier.size(20.dp), tint = if (lifted) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (lifted) FontWeight.SemiBold else null)
        Icon(if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

private const val MAX_LISTED = 5
