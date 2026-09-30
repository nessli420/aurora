package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DEFAULT_SQUIG_BASE
import com.aurora.music.data.DEFAULT_SQUIG_TARGET
import com.aurora.music.data.DspMode
import com.aurora.music.data.EqProfile
import com.aurora.music.data.ParamBand
import com.aurora.music.data.PlaybackPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.SQUIG_INSTANCES
import com.aurora.music.data.SQUIG_TARGETS
import com.aurora.music.data.SettingsStore
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import com.aurora.music.ui.components.LottieLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenLoudness: () -> Unit,
    onOpenProcessingPresets: () -> Unit, onOpenProcessingRack: () -> Unit, onOpenImpulses: () -> Unit) {
    val store = LocalDesktopContainer.current.settingsStore
    val prefs by store.audioPrefs.collectAsStateWithLifecycle(initialValue = AudioPrefs())
    val playbackPrefs by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val rack by store.processingRack.collectAsStateWithLifecycle(initialValue = ProcessingRack())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun toast(message: String) { scope.launch { snackbar.showSnackbar(message) } }

    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val activeEq by store.activeEqProfile.collectAsStateWithLifecycle(initialValue = "")
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(appString(R.string.text_equalizer_effects_e6ad57), onBack)
            SettingsList(contentPadding, state = listState) {
                item { SettingsSectionTitle(appString(R.string.text_tone_engine_79c7e5)) }
                item {
                    if (rack.enabled) SettingsGroup {
                        SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.processingRack,
                            appString(R.string.text_active_4a0251, (rack.name)), onClick = onOpenProcessingRack)
                        Text(appString(R.string.text_edit_the_active_rack_to_change_your_sound_792bcb),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        TextButton(onClick = { scope.launch {
                            store.setProcessingRack(rack.copy(enabled = false)).onFailure {
                                toast(appString(R.string.text_could_not_switch_processing_mode_a22224))
                            }
                        } },
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) { Text(appString(R.string.text_use_standard_settings_4e6ed9)) }
                    } else ToneEngineCard(prefs.dspMode) { i -> scope.launch { store.setDspMode(i) } }
                }
                item { Spacer(Modifier.height(10.dp)) }
                item {
                    SettingsGroup {
                        if (!rack.enabled) {
                            SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.processingRack,
                                appString(R.string.text_arrange_effects_with_64_bands_per_equalizer_071bbb), onClick = onOpenProcessingRack)
                            SettingsRowDivider()
                        }
                        SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.processingPresets,
                            appString(R.string.text_your_complete_processing_settings_ready_to_recall_a6fd9f), onClick = onOpenProcessingPresets)
                    }
                }

                if (!rack.enabled) {
                    item { SettingsSectionTitle(appString(R.string.text_correction_7f2640)) }
                    collapsible("autoeq", appString(R.string.text_device_presets_ca72d3), Icons.Filled.Headset, activeEq.ifBlank { appString(R.string.text_live_squig_link_308ef5) }, expanded) {
                        AutoEqPanel(prefs, store, scope, ::toast)
                    }
                    collapsible("conv", appString(R.string.text_convolution_ir_d15d63), Icons.Filled.GraphicEq, if (prefs.dspConvEnabled && prefs.dspConvIrName.isNotBlank()) prefs.dspConvIrName else appString(R.string.text_off_e3de5a), expanded) {
                        ConvolutionPanel(prefs, store, scope, onOpenImpulses)
                    }

                    if (prefs.dspMode == DspMode.CUSTOM) {
                        item { SettingsSectionTitle(appString(R.string.text_custom_dsp_df083c)) }
                        customDspSection(prefs, store, scope, expanded)
                    }

                    item { SettingsSectionTitle(appString(R.string.text_channels_18e03e)) }
                    item {
                        SettingsGroup {
                            SettingsSwitchRow(Icons.Filled.Headset, appString(R.string.text_mono_audio_b977b4), appString(R.string.text_combine_left_and_right_channels_where_the_active_path_supports_pr_3682b6), playbackPrefs.monoAudio) { value ->
                                scope.launch { store.setMono(value) }
                            }
                        }
                    }
                }
                item { SettingsSectionTitle(appString(R.string.text_related_settings_661f04)) }
                item {
                    SettingsGroup {
                        val mode = listOf(appString(R.string.text_off_e3de5a), appString(R.string.text_track_b1c5a7), appString(R.string.text_album_dfb4c9))[prefs.replayGain.coerceIn(0, 2)]
                        SettingsDestinationRow(Icons.AutoMirrored.Filled.VolumeUp, SettingsDestinations.loudness, "ReplayGain · $mode", onClick = onOpenLoudness)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }
}

@Composable
private fun CollapsibleSection(
    title: String, icon: ImageVector, summary: String?, open: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(bottom = 10.dp), content = content)
        }
    }
}

private fun LazyListScope.collapsible(
    key: String, title: String, icon: ImageVector, summary: String?,
    expanded: SnapshotStateMap<String, Boolean>, defaultOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) = item(key = key) {
    val open = expanded[key] ?: defaultOpen
    CollapsibleSection(title, icon, summary, open, { expanded[key] = !open }, content)
}

@Composable
private fun ToneEngineCard(mode: Int, onSelect: (Int) -> Unit) {
    val engines = listOf(DspMode.CUSTOM to appString(R.string.text_custom_dsp_df083c), DspMode.OFF to appString(R.string.text_off_e3de5a))
    val current = engines.indexOfFirst { it.first == (if (mode == DspMode.CUSTOM) DspMode.CUSTOM else DspMode.OFF) }
    SettingsGroup {
        SettingsDropdownRow(
            appString(R.string.text_engine_c1f65d), engines[current].second, engines.map { it.second }, current,
            icon = Icons.Filled.GraphicEq,
            subtitle = if (mode == DspMode.CUSTOM) appString(R.string.text_aurora_software_dsp_works_on_any_device_overrides_bit_perfect_out_c3a42d)
                else appString(R.string.text_all_tone_shaping_bypassed_a7e684),
            menuLabel = appString(R.string.text_choose_engine_dee9e6),
        ) { index -> onSelect(engines[index].first) }
    }
}

@Composable
private fun HeadroomRow(peak: Float, preamp: Float, onAuto: () -> Unit) {
    val over = peak + preamp                 // positive means the curve can clip
    val clip = over > 0.1f
    val color = if (clip) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(appString(R.string.text_headroom_39deaf), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                when {
                    peak <= 0.1f -> appString(R.string.text_eq_curve_stays_below_0_db_no_preamp_needed_701882)
                    clip -> appString(R.string.text_eq_peak_1f_db_clipping_by_1f_db_7e0752).format(peak, over)
                    else -> appString(R.string.text_eq_peak_1f_db_1f_db_headroom_1db199).format(peak, -over)
                },
                style = MaterialTheme.typography.bodySmall, color = color,
            )
        }
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                .pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onAuto).padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text(appString(R.string.text_auto_c614ba), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary) }
    }
}

@Composable
private fun ConvolutionPanel(prefs: AudioPrefs, store: SettingsStore, scope: CoroutineScope, onOpenImpulses: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(appString(R.string.text_enable_convolution_558c8c), style = MaterialTheme.typography.bodyLarge)
                Text(
                    prefs.dspConvIrName.ifBlank { appString(R.string.text_no_impulse_response_selected_a643d4) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = prefs.dspConvEnabled, onCheckedChange = { v -> scope.launch { store.setDspConvEnabled(v) } })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onOpenImpulses).padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(appString(R.string.text_impulse_library_ff955d), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            if (prefs.dspConvIrName.isNotBlank()) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { scope.launch { store.setDspConvIr("", ""); store.setDspConvEnabled(false) } }.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(appString(R.string.text_remove_e96390), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) }
            }
        }
        DbSliderRow(appString(R.string.text_make_up_gain_5dc858), prefs.dspConvMakeupDb, -12f..12f) { v -> scope.launch { store.setDspConvMakeup(v) } }
    }
}

@Composable
private fun PillSelector(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val active = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .pointerHoverIcon(PointerIcon.Hand).clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    opt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AutoEqPanel(prefs: AudioPrefs, store: SettingsStore, scope: CoroutineScope, toast: (String) -> Unit) {
    val active by store.activeEqProfile.collectAsStateWithLifecycle(initialValue = "")
    val autoSwitch by store.autoEqAutoSwitch.collectAsStateWithLifecycle(initialValue = false)
    val bindings by store.eqBindings.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<EqProfile>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableStateOf(20) }
    val squigBase by store.squigBaseUrl.collectAsStateWithLifecycle(initialValue = DEFAULT_SQUIG_BASE)
    val squigTargetName by store.squigTarget.collectAsStateWithLifecycle(initialValue = DEFAULT_SQUIG_TARGET)
    val observedOutput by store.processingRoutes.observations.collectAsStateWithLifecycle()
    val outLabel = observedOutput.route.label
    val squigEq = LocalDesktopContainer.current.squigEq

    LaunchedEffect(query, squigBase, squigTargetName) {
        results = emptyList()
        visibleCount = 20
        searchFailed = false
        searching = true
        try {
            if (query.isNotBlank()) delay(220)
            results = if (query.trim().length >= 2) squigEq.search(query) else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            searchFailed = true
        } finally {
            searching = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val instIdx = SQUIG_INSTANCES.indexOfFirst { it.second == squigBase }.coerceAtLeast(0)
        PillSelector(SQUIG_INSTANCES.map { it.first }, instIdx) { i -> scope.launch { store.setSquigBaseUrl(SQUIG_INSTANCES[i].second) } }
        val tgtIdx = SQUIG_TARGETS.indexOfFirst { it.second == squigTargetName }.coerceAtLeast(0)
        PillSelector(SQUIG_TARGETS.map { it.first }, tgtIdx) { i -> scope.launch { store.setSquigTarget(SQUIG_TARGETS[i].second) } }
        Text(
            appString(R.string.text_corrections_are_generated_on_device_from_live_squig_link_measurem_eb5940, (SQUIG_TARGETS.getOrNull(tgtIdx)?.first ?: "Harman")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        if (active.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Icon(Icons.Filled.Headset, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.text_applied_60aafc, (active)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(appString(R.string.text_clear_719ea3), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).pointerHoverIcon(PointerIcon.Hand).clickable {
                        scope.launch { store.setDspParametric(emptyList()); store.setDspPreamp(0f); store.setActiveEqProfile("") }
                    }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(appString(R.string.text_search_live_iem_measurements_24a92f)) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = { if (query.isNotEmpty()) Icon(Icons.Filled.Close, appString(R.string.text_clear_719ea3), modifier = Modifier.clip(RoundedCornerShape(50)).pointerHoverIcon(PointerIcon.Hand).clickable { query = "" }.padding(4.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        if (working || searching) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                LottieLoader(modifier = Modifier.width(36.dp).height(36.dp))
            }
        }
        if (!searching) {
            Text(
                when {
                    searchFailed -> appString(R.string.text_could_not_load_presets_try_another_search_8452dd)
                    query.trim().length < 2 -> appString(R.string.text_enter_at_least_two_characters_to_search_squig_link_ca1a71)
                    results.isEmpty() -> appString(R.string.text_no_measured_presets_found_try_another_model_name_or_device_catego_06b64c)
                    else -> appString(R.string.text_presets_showing_72ab09, (results.size), (minOf(visibleCount, results.size)))
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        results.take(visibleCount).forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).pointerHoverIcon(PointerIcon.Hand).clickable(enabled = !working) {
                    scope.launch {
                        working = true
                        val eq = squigEq.generate(p)
                        if (eq != null && eq.bands.isNotEmpty()) {
                            store.applyLegacyEqProfile(p.name, eq).onSuccess {
                                query = ""
                                toast(appString(R.string.text_applied_bands_db_preamp_cf3937, (p.name), (eq.bands.size), ("%.1f".format(eq.preampDb))))
                            }.onFailure { toast(it.message ?: appString(R.string.text_couldn_t_load_a_supported_correction_check_your_connection_or_try_ee72f6)) }
                        } else {
                            toast(appString(R.string.text_couldn_t_load_a_supported_correction_check_your_connection_or_try_ee72f6))
                        }
                        working = false
                    }
                }.padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(p.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Icon(Icons.Filled.Add, appString(R.string.text_apply_cfea41), tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (results.size > visibleCount) {
            TextLink(appString(R.string.text_show_20_more_3b05ec)) { visibleCount += 20 }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(appString(R.string.text_auto_switch_per_output_66f8bf), style = MaterialTheme.typography.bodyLarge)
                Text(appString(R.string.text_apply_profiles_to_confirmed_playback_outputs_038123), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = autoSwitch, onCheckedChange = { v -> scope.launch { store.setAutoEqAutoSwitch(v) } })
        }
        if (active.isNotBlank() && observedOutput.route.key != null) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable {
                        scope.launch {
                            store.bindEqToCurrentOutput(active, prefs.dspPreampDb, prefs.dspParametric)
                                .onFailure { toast(it.message ?: appString(R.string.text_could_not_bind_this_output_15b372)) }
                        }
                    }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(appString(R.string.text_bind_to_75e438, (active), (outLabel)), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        }
        bindings.forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.deviceLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(b.profileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(Icons.Filled.Close, appString(R.string.text_unbind_e40303), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(50)).pointerHoverIcon(PointerIcon.Hand).clickable { scope.launch { store.removeEqBinding(b.deviceKey) } }.padding(4.dp))
            }
        }
    }
}

private fun LazyListScope.customDspSection(
    prefs: AudioPrefs,
    store: SettingsStore,
    scope: CoroutineScope,
    expanded: SnapshotStateMap<String, Boolean>,
) {
    val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(prefs.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
    val nBands = layout.freqs.size
    val graphic = (0 until nBands).map { prefs.dspGraphicBands.getOrElse(it) { 0f } }
    val anyGraphic = graphic.any { it != 0f }

    collapsible("c_graphic", appString(R.string.text_graphic_eq_3df203), Icons.Filled.Tune, "${layout.name}${if (anyGraphic) appString(R.string.text_active_945637) else ""}", expanded, defaultOpen = true) {
        SegmentedRow(appString(R.string.text_bands_2bc0aa), DspCoeffBuilder.GRAPHIC_LAYOUTS.map { it.name }, prefs.dspGraphicLayout) { i ->
            scope.launch { store.setDspGraphicLayout(i); store.setDspGraphicBands(List(DspCoeffBuilder.GRAPHIC_LAYOUTS[i].freqs.size) { 0f }) }
        }
        graphic.forEachIndexed { i, g ->
            DbSliderRow(freqLabel(layout.freqs[i].toInt()), g, -12f..12f) { v ->
                val updated = graphic.toMutableList().also { it[i] = v }
                scope.launch { store.setDspGraphicBands(updated) }
            }
        }
        TextLink(appString(R.string.text_reset_graphic_eq_ace461)) { scope.launch { store.setDspGraphicBands(List(nBands) { 0f }) } }
    }

    collapsible("c_param", appString(R.string.text_parametric_eq_6e40f9), Icons.Filled.GraphicEq, appPlural(R.plurals.band_count, (prefs.dspParametric.size)), expanded) {
        prefs.dspParametric.forEachIndexed { i, band ->
            ParametricBandCard(
                band = band,
                onChange = { nb -> scope.launch { store.setDspParametric(prefs.dspParametric.toMutableList().also { it[i] = nb }) } },
                onRemove = { scope.launch { store.setDspParametric(prefs.dspParametric.toMutableList().also { it.removeAt(i) }) } },
            )
        }
        if (prefs.dspParametric.size < DspCoeffBuilder.MAX_PARAMETRIC) {
            TextLink(appString(R.string.text_add_band_563c3b)) { scope.launch { store.setDspParametric(prefs.dspParametric + ParamBand(1000f, 0f, 1f)) } }
        }
    }

    collapsible("c_gain", appString(R.string.text_gain_headroom_5903cd), Icons.AutoMirrored.Filled.VolumeUp, appString(R.string.text_pre_amp_db_84974b, ("%+.0f".format(prefs.dspPreampDb))), expanded) {
        DbSliderRow(appString(R.string.text_pre_amp_7b79c8), prefs.dspPreampDb, -12f..12f) { v -> scope.launch { store.setDspPreamp(v) } }
        val peak = remember(prefs.dspGraphicBands, prefs.dspParametric, prefs.dspGraphicLayout) {
            DspCoeffBuilder.eqPeakDb(DspParams(graphic = graphic.toFloatArray(), graphicFreqs = layout.freqs, graphicQ = layout.q, parametric = prefs.dspParametric.map { DspBand.from(it) }))
        }
        HeadroomRow(peak = peak, preamp = prefs.dspPreampDb) { scope.launch { store.setDspPreamp((-peak).coerceIn(-12f, 0f)) } }
        FloatSliderRow(appString(R.string.text_balance_90eef6), prefs.dspBalance, -1f..1f, valueText = balanceLabel(prefs.dspBalance)) { v -> scope.launch { store.setDspBalance(v) } }
    }

    val spatial = buildList { if (prefs.dspWidth != 1f) add(appString(R.string.text_width_2f_276839).format(prefs.dspWidth)); if (prefs.dspCrossfeed > 0f) add(appString(R.string.text_crossfeed_8f1996, ((prefs.dspCrossfeed * 100).roundToInt()))) }.joinToString(" · ").ifBlank { appString(R.string.text_off_e3de5a) }
    collapsible("c_spatial", appString(R.string.text_spatial_729ac1), Icons.Filled.SurroundSound, spatial, expanded) {
        FloatSliderRow(appString(R.string.text_stereo_width_336051), prefs.dspWidth, 0f..2f, valueText = "%.2f×".format(prefs.dspWidth)) { v -> scope.launch { store.setDspWidth(v) } }
        FloatSliderRow(appString(R.string.text_crossfeed_e6b7b4), prefs.dspCrossfeed, 0f..1f, valueText = if (prefs.dspCrossfeed <= 0f) appString(R.string.text_off_e3de5a) else "${(prefs.dspCrossfeed * 100).roundToInt()}%") { v -> scope.launch { store.setDspCrossfeed(v) } }
    }

    collapsible("c_harm", appString(R.string.text_harmonics_17ba48), Icons.Filled.Whatshot, if (prefs.dspSaturation > 0f) appString(R.string.text_tube_419868, ((prefs.dspSaturation * 100).roundToInt())) else appString(R.string.text_off_e3de5a), expanded) {
        FloatSliderRow(appString(R.string.text_tube_saturation_8c63f8), prefs.dspSaturation, 0f..1f, valueText = if (prefs.dspSaturation <= 0f) appString(R.string.text_off_e3de5a) else "${(prefs.dspSaturation * 100).roundToInt()}%") { v -> scope.launch { store.setDspSaturation(v) } }
    }

    val aligned = prefs.dspDelayLeftMs > 0f || prefs.dspDelayRightMs > 0f || prefs.dspTrimLeftDb != 0f || prefs.dspTrimRightDb != 0f
    collapsible("c_align", appString(R.string.text_channel_alignment_7c33be), Icons.Filled.SwapHoriz, if (aligned) appString(R.string.text_adjusted_7bba98) else appString(R.string.text_off_e3de5a), expanded) {
        FloatSliderRow(appString(R.string.text_left_delay_c54844), prefs.dspDelayLeftMs, 0f..20f, valueText = appString(R.string.text_1f_ms_57bb04).format(prefs.dspDelayLeftMs)) { v -> scope.launch { store.setDspDelayLeft(v) } }
        FloatSliderRow(appString(R.string.text_right_delay_ca7838), prefs.dspDelayRightMs, 0f..20f, valueText = appString(R.string.text_1f_ms_57bb04).format(prefs.dspDelayRightMs)) { v -> scope.launch { store.setDspDelayRight(v) } }
        DbSliderRow(appString(R.string.text_left_trim_9ad513), prefs.dspTrimLeftDb, -12f..0f) { v -> scope.launch { store.setDspTrimLeft(v) } }
        DbSliderRow(appString(R.string.text_right_trim_cadffb), prefs.dspTrimRightDb, -12f..0f) { v -> scope.launch { store.setDspTrimRight(v) } }
    }

    val dyn = buildList { if (prefs.dspLimiterEnabled) add(appString(R.string.text_limiter_20fee6)); if (prefs.dspCompEnabled) add(appString(R.string.text_compressor_b23f61)) }.joinToString(" · ").ifBlank { appString(R.string.text_off_e3de5a) }
    collapsible("c_dyn", appString(R.string.text_dynamics_7d5536), Icons.Filled.Compress, dyn, expanded) {
        SettingsSwitchRow(Icons.Filled.GraphicEq, appString(R.string.text_limiter_20fee6), appString(R.string.text_sample_peak_control_with_attack_and_release_936da5), prefs.dspLimiterEnabled) { v -> scope.launch { store.setDspLimiterEnabled(v) } }
        if (prefs.dspLimiterEnabled) {
            FloatSliderRow(appString(R.string.text_ceiling_e29db9), prefs.dspLimiterCeilingDb, -6f..0f, valueText = appString(R.string.text_1f_db_02557a).format(prefs.dspLimiterCeilingDb)) { v -> scope.launch { store.setDspCeiling(v) } }
        }
        SettingsSwitchRow(Icons.Filled.GraphicEq, appString(R.string.text_compressor_b23f61), appString(R.string.text_even_out_loud_quiet_passages_5f8fe4), prefs.dspCompEnabled) { v -> scope.launch { store.setDspCompEnabled(v) } }
        if (prefs.dspCompEnabled) {
            FloatSliderRow(appString(R.string.text_threshold_c51f7b), prefs.dspCompThreshDb, -40f..0f, valueText = appString(R.string.text_0f_db_fd1f4d).format(prefs.dspCompThreshDb)) { v -> scope.launch { store.setDspCompThresh(v) } }
            FloatSliderRow(appString(R.string.text_ratio_794f65), prefs.dspCompRatio, 1f..10f, valueText = "%.1f:1".format(prefs.dspCompRatio)) { v -> scope.launch { store.setDspCompRatio(v) } }
        }
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(50)).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun ParametricBandCard(band: ParamBand, onChange: (ParamBand) -> Unit, onRemove: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    if (edit) RackBandDialog(band, appString(R.string.text_edit_filter_32d3bd), { edit = false }) { onChange(it); edit = false }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(freqLabel(band.freqHz.toInt()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(appString(R.string.text_1f_db_q_1f_4fdf16).format(band.gainDb, band.q), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                Icons.Filled.Close, appString(R.string.text_remove_band_fb769d),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp).clip(RoundedCornerShape(50)).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onRemove).padding(start = 8.dp),
            )
        }
        TextButton(onClick = { edit = true }) { Text(band.filterType.label + if (band.isEnabled) "" else appString(R.string.text_bypassed_c5c251)) }
        FloatSliderRow(appString(R.string.text_freq_f5f7de), band.freqHz, 20f..20000f, valueText = freqLabel(band.freqHz.toInt())) { v -> onChange(band.copy(freqHz = v)) }
        if (band.filterType.hasGain) DbSliderRow(appString(R.string.text_gain_96dd91), band.gainDb, -15f..15f) { v -> onChange(band.copy(gainDb = v)) }
        if (band.filterType.hasQ) FloatSliderRow("Q", band.q, 0.3f..8f, valueText = "%.2f".format(band.q)) { v -> onChange(band.copy(q = v)) }
    }
}

@Composable
private fun DbSliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            )
            Text("%+.1f".format(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        }
    }
}

@Composable
private fun FloatSliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

private fun balanceLabel(b: Float): String = when {
    b < -0.01f -> appString(R.string.text_l_08eea2, ((-b * 100).roundToInt()))
    b > 0.01f -> appString(R.string.text_r_6b99ea, ((b * 100).roundToInt()))
    else -> appString(R.string.text_center_a23911)
}

private fun freqLabel(hz: Int): String = when {
    hz <= 0 -> "—"
    hz >= 1000 -> if (hz % 1000 == 0) appString(R.string.text_khz_dd177d, (hz / 1000)) else appString(R.string.text_1f_khz_92ed69).format(hz / 1000f)
    else -> appString(R.string.text_hz_648ee5, (hz))
}
