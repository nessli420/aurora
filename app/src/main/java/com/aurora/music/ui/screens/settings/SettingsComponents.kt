package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.LocalGlassAmbient
import com.aurora.music.ui.components.glass.GlassButton
import com.aurora.music.ui.components.glass.GlassSegmented
import com.aurora.music.ui.components.glass.GlassToggle
import com.aurora.music.ui.components.glass.glassTileColor
import com.kyant.shapes.RoundedRectangle
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

val LocalSettingsPaneRoots = compositionLocalOf<Set<String>?> { null }

@Composable
private fun isSettingsPaneRoot(): Boolean {
    val roots = LocalSettingsPaneRoots.current ?: return false
    val entry = LocalViewModelStoreOwner.current as? NavBackStackEntry ?: return true
    return entry.id in roots
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit) {
    SettingsTopBar(title, onBack, showBack = !isSettingsPaneRoot())
}

@Composable
fun SettingsTopBar(title: String, onBack: () -> Unit, showBack: Boolean) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (isGlassTheme()) {
        Row(
            Modifier.fillMaxWidth().padding(top = topInset + 6.dp, start = 16.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                GlassButton(onClick = onBack, backdrop = LocalGlassAmbient.current, modifier = Modifier.size(44.dp),
                    contentDescription = appString(R.string.text_back_b52b36)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, Modifier.size(20.dp).padding(start = 5.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(12.dp))
            } else Spacer(Modifier.width(4.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        return
    }
    Row(
        Modifier.fillMaxWidth()
            .then(if (LocalUiPrefs.current.themeStyle != ThemeStyle.AURORA) Modifier.auroraPanel(RectangleShape) else Modifier)
            .padding(top = topInset + 6.dp, start = 8.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.width(8.dp))
        } else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        title.uppercase(java.util.Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (isGlassTheme()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = if (isGlassTheme()) 28.dp else 20.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
fun SettingsGroup(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .then(
                if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA)
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                else Modifier.auroraPanel(MaterialTheme.shapes.medium)
            ),
        content = content,
    )
}

@Composable
fun SettingsRowDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 72.dp).height(0.7.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

@Composable
internal fun RowScaffold(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    selected: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null && isGlassTheme()) {
            Box(
                Modifier.size(32.dp).clip(RoundedRectangle(8.dp)).background(glassTileColor(icon)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Box(
                Modifier.size(38.dp).then(
                    if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA)
                        Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    else Modifier.auroraPanel(MaterialTheme.shapes.extraSmall)
                ),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

@Composable
fun SettingsNavRow(icon: ImageVector, title: String, subtitle: String? = null, value: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    RowScaffold(icon, title, subtitle, onClick, selected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsSwitchRow(icon: ImageVector? = null, title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    RowScaffold(icon, title, subtitle, { onCheckedChange(!checked) }) {
        if (isGlassTheme()) GlassToggle(checked, onCheckedChange, Modifier.padding(start = 12.dp))
        else Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

internal const val LIVE_WRITE_MS = 120L

// drags render locally and reach datastore at most every LIVE_WRITE_MS since the engine only hears stored values
internal class LiveValue<T : Any> {
    var local by mutableStateOf<T?>(null)
    val pending = MutableStateFlow<T?>(null)
    var write: suspend (T) -> Unit = {}

    fun set(v: T) { local = v; pending.value = v }

    suspend fun run() {
        try {
            pending.collect { v -> if (v != null) { write(v); pending.compareAndSet(v, null); delay(LIVE_WRITE_MS) } }
        } finally {
            pending.value?.let { v -> withContext(NonCancellable) { write(v) } }
        }
    }
}

@Composable
internal fun <T : Any> rememberLiveValue(stored: T, write: suspend (T) -> Unit): LiveValue<T> {
    val live = remember { LiveValue<T>() }
    live.write = write
    LaunchedEffect(live) { live.run() }
    val local = live.local
    LaunchedEffect(stored, local) {
        if (local == null) return@LaunchedEffect
        if (local != stored) delay(1_000)
        live.local = null
    }
    return live
}

@Composable
fun SettingsSliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onValueChange: (Float) -> Unit) {
    val live = rememberLiveValue(value) { onValueChange(it) }
    // the release writes at once, since leaving the screen cancels the callers' scope before a deferred write runs
    DirectSliderRow(title, valueLabel, live.local ?: value, range, steps,
        onValueChangeFinished = { live.pending.value?.let { live.pending.value = null; onValueChange(it) } }, onValueChange = live::set)
}

// unthrottled, for callers that preview every move themselves
@Composable
internal fun DirectSliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0,
    onValueChangeFinished: () -> Unit = {}, onValueChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value, onValueChange = onValueChange, valueRange = range, steps = steps, onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
fun SegmentedRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.size(10.dp))
        SegmentedChoice(options, selected, onSelect = onSelect)
    }
}

@Composable
fun SegmentedChoice(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    if (isGlassTheme()) {
        Box(modifier) { GlassSegmented(options, selected, onSelect) }
        return
    }
    val shape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(50) else MaterialTheme.shapes.small
    Row(
        modifier.fillMaxWidth().then(
            if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA)
                Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            else Modifier.auroraPanel(shape)
        ).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val active = i == selected
            Box(
                Modifier.weight(1f).clip(shape)
                    .background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(opt, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
