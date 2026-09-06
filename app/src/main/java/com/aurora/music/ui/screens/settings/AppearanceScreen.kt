package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AccentMode
import com.aurora.music.data.CornerStyle
import com.aurora.music.data.HomeSection
import com.aurora.music.data.MiniProgress
import com.aurora.music.data.MiniStyle
import com.aurora.music.data.SeekStyle
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.theme.AccentPresets
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.ThemeIdentities
import com.aurora.music.ui.theme.ThemeIdentity
import com.aurora.music.ui.theme.auroraBackdrop
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.auroraShapes
import com.aurora.music.ui.theme.auroraTypography
import com.aurora.music.ui.theme.styleColorScheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AppearanceScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val prefs by store.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val scope = rememberCoroutineScope()
    val materialYouSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Appearance", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            item { SettingsSectionTitle("Theme") }
            item { ThemeStylePicker(prefs) { style -> scope.launch { store.setThemeStyle(style) } } }
            item {
                SegmentedRow("Mode", listOf("System", "Light", "Dark", "AMOLED"), prefs.themeMode) { i ->
                    scope.launch { store.setThemeMode(i) }
                }
            }
            item {
                Text(
                    when (prefs.themeMode) {
                        ThemeMode.AMOLED -> "True-black surfaces — saves power on OLED screens."
                        ThemeMode.SYSTEM -> "Follows your device's light/dark setting."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            if (prefs.themeStyle == ThemeStyle.AURORA) {
            item { SettingsSectionTitle("Accent") }
            item {
                SegmentedRow("Source", listOf("Presets", "Custom", "Material You"), prefs.accentMode) { i ->
                    scope.launch { store.setAccentMode(i) }
                }
            }

            when (prefs.accentMode) {
                AccentMode.PRESET -> item {
                    AccentPresetGrid(selected = prefs.accentPreset) { i -> scope.launch { store.setAccentPreset(i) } }
                }
                AccentMode.CUSTOM -> item {
                    CustomColorPicker(initialArgb = prefs.accentColor.toInt()) { argb ->
                        scope.launch { store.setAccentColor(argb.toLong() and 0xFFFFFFFFL) }
                    }
                }
                else -> item {
                    Text(
                        if (materialYouSupported) "Using your wallpaper colors (Material You)."
                        else "Material You needs Android 12+. Falling back to the preset accent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            }

            item { SettingsSectionTitle("Display") }
            item {
                SettingsSliderRow("Font size", "${(prefs.fontScale * 100).roundToInt()}%", prefs.fontScale, 0.85f..1.3f) { v ->
                    scope.launch { store.setFontScale(v) }
                }
            }
            if (prefs.themeStyle == ThemeStyle.AURORA) item {
                SegmentedRow("Corners", listOf("Sharp", "Default", "Rounded", "Pill"), prefs.cornerStyle) { i ->
                    scope.launch { store.setCornerStyle(i) }
                }
            }

            item { SettingsSectionTitle("Player") }
            item {
                SegmentedRow("Seek bar", listOf("Waveform", "Bar"), prefs.playerSeekStyle) { i ->
                    scope.launch { store.setPlayerSeekStyle(i) }
                }
            }
            if (prefs.playerSeekStyle == SeekStyle.WAVEFORM) {
                item {
                    SettingsSliderRow("Waveform bars", "${prefs.playerWaveBars}", prefs.playerWaveBars.toFloat(), 24f..96f) { v ->
                        scope.launch { store.setPlayerWaveBars(v.roundToInt()) }
                    }
                }
            }
            item {
                SettingsSliderRow("Artwork size", "${(prefs.playerArtSize * 100).roundToInt()}%", prefs.playerArtSize, 0.6f..1f) { v ->
                    scope.launch { store.setPlayerArtSize(v) }
                }
            }
            if (prefs.themeStyle == ThemeStyle.AURORA) item {
                SettingsSliderRow("Gradient intensity", "${(prefs.playerGradient * 100).roundToInt()}%", prefs.playerGradient, 0f..1.5f) { v ->
                    scope.launch { store.setPlayerGradient(v) }
                }
            }
            item {
                SettingsSwitchRow(title = "Bottom utilities", subtitle = "Speed · Lyrics · Queue row", checked = prefs.playerShowUtilities) { v ->
                    scope.launch { store.setPlayerShowUtilities(v) }
                }
            }

            item { SettingsSectionTitle("Miniplayer") }
            item {
                SegmentedRow("Style", listOf("Standard", "Compact", "Prominent"), prefs.miniStyle) { i ->
                    scope.launch { store.setMiniStyle(i) }
                }
            }
            item {
                SegmentedRow("Progress", listOf("Line", "Bar", "None"), prefs.miniProgress) { i ->
                    scope.launch { store.setMiniProgress(i) }
                }
            }

            item { SettingsSectionTitle("Library") }
            item {
                SegmentedRow("Grid columns", listOf("2", "3", "4"), (prefs.libraryColumns - 2).coerceIn(0, 2)) { i ->
                    scope.launch { store.setLibraryColumns(i + 2) }
                }
            }

            item { SettingsSectionTitle("Home sections") }
            val homeSections = listOf(
                HomeSection.HERO to "New release hero",
                HomeSection.RECENT to "Jump back in",
                HomeSection.PLAYLISTS to "Your playlists",
                HomeSection.FAVOURITE to "From your favourites",
                HomeSection.MOST to "Most played",
                HomeSection.ARTISTS to "Artists",
                HomeSection.NEW to "New releases",
            )
            items(homeSections.size) { idx ->
                val (id, label) = homeSections[idx]
                SettingsSwitchRow(title = label, checked = id !in prefs.hiddenHomeSections) { v ->
                    scope.launch { store.setHomeSectionHidden(id, !v) }
                }
            }
        }
    }
}

@Composable
private fun ThemeStylePicker(prefs: UiPrefs, onSelect: (Int) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemeIdentities.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { identity ->
                    val selected = identity.id == prefs.themeStyle
                    Column(
                        Modifier.weight(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(identity.id) })
                            .padding(4.dp),
                    ) {
                        ThemePreview(identity, prefs, dark)
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(identity.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        val current = ThemeIdentities.firstOrNull { it.id == prefs.themeStyle } ?: ThemeIdentities.first()
        Text(current.description, style = MaterialTheme.typography.titleSmall)
        Text(current.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (prefs.themeStyle != ThemeStyle.AURORA) {
            Text("This style includes its own colors and corners. Your Aurora customizations are saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemePreview(identity: ThemeIdentity, prefs: UiPrefs, dark: Boolean) {
    CompositionLocalProvider(LocalUiPrefs provides prefs.copy(themeStyle = identity.id)) {
        MaterialTheme(
            colorScheme = styleColorScheme(identity.id, dark),
            typography = auroraTypography(0.85f, identity.id),
            shapes = auroraShapes(CornerStyle.DEFAULT, identity.id),
        ) {
            Column(Modifier.fillMaxWidth().height(122.dp).clip(MaterialTheme.shapes.small).auroraBackdrop().padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("AURORA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(MaterialTheme.shapes.extraSmall).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(22.dp).border(4.dp, MaterialTheme.colorScheme.background.copy(alpha = 0.55f), CircleShape))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth(0.95f).height(4.dp).background(MaterialTheme.colorScheme.onBackground))
                        Box(Modifier.fillMaxWidth(0.65f).height(3.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(26.dp).auroraPanel(MaterialTheme.shapes.small, emphasized = true).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.25f, 0.55f, 0.85f, 0.5f, 0.7f, 0.35f, 0.6f, 0.4f).forEach { level ->
                        Box(Modifier.weight(1f).height((level * 18).dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentPresetGrid(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentPresets.chunked(5).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { colIdx, preset ->
                    val index = rowIdx * 5 + colIdx
                    val isSel = index == selected
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp))
                            .background(preset.seed)
                            .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, RoundedCornerShape(16.dp)) else Modifier)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) {
                            val on = if (preset.seed.luminanceApprox() > 0.5f) Color.Black else Color.White
                            Icon(Icons.Filled.Check, "Selected", tint = on, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                // Pad short final row so swatches keep their width.
                repeat(5 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CustomColorPicker(initialArgb: Int, onChange: (Int) -> Unit) {
    val initialHsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var bri by remember { mutableFloatStateOf(initialHsv[2]) }

    fun push() = onChange(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri)))
    val preview = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri)))

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(preview).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
            Text("  Live preview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSliderRow("Hue", "${hue.roundToInt()}°", hue, 0f..360f) { hue = it; push() }
        SettingsSliderRow("Saturation", "${(sat * 100).roundToInt()}%", sat, 0f..1f) { sat = it; push() }
        SettingsSliderRow("Brightness", "${(bri * 100).roundToInt()}%", bri, 0f..1f) { bri = it; push() }
    }
}

private fun Color.luminanceApprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
