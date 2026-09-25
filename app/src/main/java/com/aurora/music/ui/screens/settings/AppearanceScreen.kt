package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.aurora.music.data.AppTypeface
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
import com.aurora.music.ui.theme.auroraFontFamily
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
        SettingsTopBar(appString(R.string.text_appearance_41def7), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            item { SettingsSectionTitle(appString(R.string.text_theme_a797e3)) }
            item { ThemeStylePicker(prefs) { style -> scope.launch { store.setThemeStyle(style) } } }
            item {
                SegmentedRow(appString(R.string.text_mode_a7b93d), listOf(appString(R.string.text_system_bc0792), appString(R.string.text_light_a36ef8), appString(R.string.text_dark_ae1ef0), "AMOLED"), prefs.themeMode) { i ->
                    scope.launch { store.setThemeMode(i) }
                }
            }
            item {
                Text(
                    when (prefs.themeMode) {
                        ThemeMode.AMOLED -> appString(R.string.text_true_black_surfaces_saves_power_on_oled_screens_2481a8)
                        ThemeMode.SYSTEM -> appString(R.string.text_follows_your_device_s_light_dark_setting_38c955)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            if (prefs.themeStyle == ThemeStyle.AURORA) {
            item { SettingsSectionTitle(appString(R.string.text_accent_233064)) }
            item {
                SegmentedRow(appString(R.string.text_source_6da13a), listOf(appString(R.string.text_presets_e709e7), appString(R.string.text_custom_081ae3), "Material You"), prefs.accentMode) { i ->
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
                        if (materialYouSupported) appString(R.string.text_using_your_wallpaper_colors_material_you_33e00d)
                        else appString(R.string.text_material_you_needs_android_12_falling_back_to_the_preset_accent_8c9a77),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            }

            item { SettingsSectionTitle(appString(R.string.text_display_574ff9)) }
            item {
                TypefacePicker(prefs) { typeface -> scope.launch { store.setTypeface(typeface) } }
            }
            item {
                SettingsSliderRow(appString(R.string.text_font_size_83ca9e), "${(prefs.fontScale * 100).roundToInt()}%", prefs.fontScale, 0.85f..1.3f) { v ->
                    scope.launch { store.setFontScale(v) }
                }
            }
            if (prefs.themeStyle == ThemeStyle.AURORA) item {
                SegmentedRow(appString(R.string.text_corners_f1fb13), listOf(appString(R.string.text_sharp_cf3e9a), appString(R.string.text_default_808d7d), appString(R.string.text_rounded_c5aa34), appString(R.string.text_pill_38ea5b)), prefs.cornerStyle) { i ->
                    scope.launch { store.setCornerStyle(i) }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_player_e53407)) }
            item {
                SegmentedRow(appString(R.string.text_seek_bar_0030d0), listOf(appString(R.string.text_waveform_200f14), appString(R.string.text_bar_e496fd)), prefs.playerSeekStyle) { i ->
                    scope.launch { store.setPlayerSeekStyle(i) }
                }
            }
            if (prefs.playerSeekStyle == SeekStyle.WAVEFORM) {
                item {
                    SettingsSliderRow(appString(R.string.text_waveform_bars_c215c9), "${prefs.playerWaveBars}", prefs.playerWaveBars.toFloat(), 24f..96f) { v ->
                        scope.launch { store.setPlayerWaveBars(v.roundToInt()) }
                    }
                }
            }
            item {
                SettingsSliderRow(appString(R.string.text_artwork_size_6dd0fc), "${(prefs.playerArtSize * 100).roundToInt()}%", prefs.playerArtSize, 0.6f..1f) { v ->
                    scope.launch { store.setPlayerArtSize(v) }
                }
            }
            if (prefs.themeStyle == ThemeStyle.AURORA) item {
                SettingsSliderRow(appString(R.string.text_gradient_intensity_84a1f5), "${(prefs.playerGradient * 100).roundToInt()}%", prefs.playerGradient, 0f..1.5f) { v ->
                    scope.launch { store.setPlayerGradient(v) }
                }
            }
            item {
                SettingsSwitchRow(title = appString(R.string.text_bottom_utilities_8b97ef), subtitle = appString(R.string.text_speed_lyrics_queue_row_814225), checked = prefs.playerShowUtilities) { v ->
                    scope.launch { store.setPlayerShowUtilities(v) }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_miniplayer_4adff2)) }
            item {
                SegmentedRow(appString(R.string.text_style_99a0ef), listOf(appString(R.string.text_standard_2dfa66), appString(R.string.text_compact_1df39a), appString(R.string.text_prominent_4b3579)), prefs.miniStyle) { i ->
                    scope.launch { store.setMiniStyle(i) }
                }
            }
            item {
                SegmentedRow(appString(R.string.text_progress_1b9027), listOf(appString(R.string.text_line_ea9676), appString(R.string.text_bar_e496fd), appString(R.string.text_none_6eef66)), prefs.miniProgress) { i ->
                    scope.launch { store.setMiniProgress(i) }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_library_b8100f)) }
            item {
                SegmentedRow(appString(R.string.text_grid_columns_b0c94c), listOf("2", "3", "4"), (prefs.libraryColumns - 2).coerceIn(0, 2)) { i ->
                    scope.launch { store.setLibraryColumns(i + 2) }
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_home_sections_1f24fb)) }
            val homeSections = listOf(
                HomeSection.HERO to appString(R.string.text_new_release_hero_c354b3),
                HomeSection.RECENT to appString(R.string.text_jump_back_in_1d9181),
                HomeSection.PLAYLISTS to appString(R.string.text_your_playlists_df03eb),
                HomeSection.FAVOURITE to appString(R.string.text_from_your_favourites_d39722),
                HomeSection.MOST to appString(R.string.text_most_played_14202e),
                HomeSection.ARTISTS to appString(R.string.text_artists_1528d8),
                HomeSection.NEW to appString(R.string.text_new_releases_3cdd02),
                HomeSection.RECOMMENDED to appString(R.string.text_recommended_albums_36271e),
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
private fun TypefacePicker(prefs: UiPrefs, onSelect: (Int) -> Unit) {
    Text(
        appString(R.string.typeface_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
    val options = listOf(
        AppTypeface.THEME_DEFAULT to appString(R.string.typeface_theme_default),
        AppTypeface.DM_SANS to "DM Sans",
        AppTypeface.PLUS_JAKARTA_SANS to "Plus Jakarta Sans",
        AppTypeface.MANROPE to "Manrope",
    )
    SettingsGroup {
        Column(Modifier.selectableGroup()) {
            options.forEach { (typeface, label) ->
                val selected = prefs.typeface == typeface
                val family = auroraFontFamily(typeface, prefs.themeStyle)
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(typeface) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontFamily = family, fontWeight = FontWeight.Bold)
                        Text(
                            appString(R.string.typeface_preview),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = family,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(selected = selected, onClick = null)
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
            Text(appString(R.string.text_this_style_includes_its_own_colors_and_corners_your_aurora_custom_9b4b82), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemePreview(identity: ThemeIdentity, prefs: UiPrefs, dark: Boolean) {
    CompositionLocalProvider(LocalUiPrefs provides prefs.copy(themeStyle = identity.id)) {
        MaterialTheme(
            colorScheme = styleColorScheme(identity.id, dark),
            typography = auroraTypography(0.85f, identity.id, prefs.typeface),
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
                            Icon(Icons.Filled.Check, appString(R.string.text_selected_9a976f), tint = on, modifier = Modifier.size(22.dp))
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
            Text(appString(R.string.text_live_preview_d44c24), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSliderRow(appString(R.string.text_hue_7e58a6), "${hue.roundToInt()}°", hue, 0f..360f) { hue = it; push() }
        SettingsSliderRow(appString(R.string.text_saturation_20a32b), "${(sat * 100).roundToInt()}%", sat, 0f..1f) { sat = it; push() }
        SettingsSliderRow(appString(R.string.text_brightness_e1a2b6), "${(bri * 100).roundToInt()}%", bri, 0f..1f) { bri = it; push() }
    }
}

private fun Color.luminanceApprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
