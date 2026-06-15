package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AppContainer
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DEFAULT_SQUIG_BASE
import com.aurora.music.data.DEFAULT_SQUIG_TARGET
import com.aurora.music.data.DspMode
import com.aurora.music.data.SQUIG_INSTANCES
import com.aurora.music.data.SQUIG_TARGETS
import com.aurora.music.data.EqBinding
import com.aurora.music.data.EqProfile
import com.aurora.music.data.ParamBand
import com.aurora.music.data.SettingsStore
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val fx = container.audioEffects
    val prefs by store.audioPrefs.collectAsStateWithLifecycle(initialValue = AudioPrefs())
    val scope = rememberCoroutineScope()

    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val activeEq by store.activeEqProfile.collectAsStateWithLifecycle(initialValue = "")
    val rgLabels = listOf("Off", "Track", "Album")

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Equalizer & effects", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            item { SettingsSectionTitle("Tone engine") }
            item { ToneEngineCard(prefs.dspMode) { i -> scope.launch { store.setDspMode(i) } } }

            item { SettingsSectionTitle("Correction") }
            collapsible("autoeq", "Headphone correction", Icons.Filled.Headset, activeEq.ifBlank { "AutoEQ — off" }, expanded) {
                AutoEqPanel(container, prefs, store, scope)
            }
            collapsible("conv", "Convolution (IR)", Icons.Filled.GraphicEq, if (prefs.dspConvEnabled && prefs.dspConvIrName.isNotBlank()) prefs.dspConvIrName else "Off", expanded) {
                ConvolutionPanel(prefs, store, scope)
            }

            when (prefs.dspMode) {
                DspMode.SYSTEM -> { item { SettingsSectionTitle("System equalizer") }; systemEqSection(prefs, fx, store, scope, expanded) }
                DspMode.CUSTOM -> { item { SettingsSectionTitle("Custom DSP") }; customDspSection(prefs, store, scope, expanded) }
                else -> item {
                    Text(
                        "Tone shaping is bypassed. Choose System or Custom above to enable the EQ.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            item { SettingsSectionTitle("Output") }
            collapsible("rg", "Volume leveling", Icons.Filled.VolumeUp, "ReplayGain — ${rgLabels[prefs.replayGain.coerceIn(0, 2)]}", expanded) {
                SegmentedRow("Mode", rgLabels, prefs.replayGain) { i -> scope.launch { store.setReplayGain(i) } }
            }
        }
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
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
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
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)).padding(12.dp),
    ) {
        EngineDropdown(mode, onSelect)
        Text(
            when (mode) {
                DspMode.SYSTEM -> "Android system effects — device-dependent."
                DspMode.CUSTOM -> "Aurora software DSP — works on any device. Overrides bit-perfect output. Restart playback after switching engines."
                else -> "All tone shaping bypassed."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun HeadroomRow(peak: Float, preamp: Float, onAuto: () -> Unit) {
    val over = peak + preamp                 // positive means the curve can clip
    val clip = over > 0.1f
    val color = if (clip) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Headroom", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                when {
                    peak <= 0.1f -> "EQ curve stays below 0 dB — no preamp needed"
                    clip -> "EQ peak +%.1f dB · clipping by %.1f dB".format(peak, over)
                    else -> "EQ peak +%.1f dB · %.1f dB headroom".format(peak, -over)
                },
                style = MaterialTheme.typography.bodySmall, color = color,
            )
        }
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onAuto).padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Auto", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary) }
    }
}

@Composable
private fun ConvolutionPanel(prefs: AudioPrefs, store: SettingsStore, scope: CoroutineScope) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching {
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "impulse.wav"
                runCatching {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) name = c.getString(i)
                    }
                }
                val dest = java.io.File(ctx.filesDir, "ir_active.wav")
                ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                store.setDspConvIr(dest.absolutePath, name)
                store.setDspConvEnabled(true)
                scope.launch(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "Loaded IR: $name", android.widget.Toast.LENGTH_SHORT).show() }
            }.onFailure {
                scope.launch(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "Couldn't load that file", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enable convolution", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (prefs.dspConvIrName.isNotBlank()) "IR: ${prefs.dspConvIrName}" else "Linear-phase EQ / room / headphone correction from a WAV",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = prefs.dspConvEnabled, onCheckedChange = { v -> scope.launch { store.setDspConvEnabled(v) } })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { runCatching { picker.launch(arrayOf("*/*")) } }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (prefs.dspConvIrName.isBlank()) "Load IR (.wav)" else "Replace IR", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            if (prefs.dspConvIrName.isNotBlank()) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { scope.launch { store.setDspConvIr("", ""); store.setDspConvEnabled(false) } }.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Remove", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) }
            }
        }
        DbSliderRow("Make-up gain", prefs.dspConvMakeupDb, -12f..12f) { v -> scope.launch { store.setDspConvMakeup(v) } }
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
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
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
private fun AutoEqPanel(container: AppContainer, prefs: AudioPrefs, store: SettingsStore, scope: CoroutineScope) {
    val active by store.activeEqProfile.collectAsStateWithLifecycle(initialValue = "")
    val autoSwitch by store.autoEqAutoSwitch.collectAsStateWithLifecycle(initialValue = false)
    val bindings by store.eqBindings.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<EqProfile>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(0) }   // 0 bundled autoeq db 1 live squig.link
    val squigBase by store.squigBaseUrl.collectAsStateWithLifecycle(initialValue = DEFAULT_SQUIG_BASE)
    val squigTargetName by store.squigTarget.collectAsStateWithLifecycle(initialValue = DEFAULT_SQUIG_TARGET)
    val outLabel = container.autoEqController.currentOutputLabel()
    val ctx = LocalContext.current
    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    LaunchedEffect(query, source, squigBase, squigTargetName) {
        if (query.trim().length < 2) results = emptyList()
        else { delay(220); results = if (source == 0) container.autoEq.search(query) else container.squigEq.search(query) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        PillSelector(listOf("AutoEq DB", "squig.link"), source) { source = it }
        if (source == 1) {
            val instIdx = SQUIG_INSTANCES.indexOfFirst { it.second == squigBase }.coerceAtLeast(0)
            PillSelector(SQUIG_INSTANCES.map { it.first }, instIdx) { i -> scope.launch { store.setSquigBaseUrl(SQUIG_INSTANCES[i].second) } }
            val tgtIdx = SQUIG_TARGETS.indexOfFirst { it.second == squigTargetName }.coerceAtLeast(0)
            PillSelector(SQUIG_TARGETS.map { it.first }, tgtIdx) { i -> scope.launch { store.setSquigTarget(SQUIG_TARGETS[i].second) } }
            Text(
                "Corrections are generated on-device from live squig.link measurements toward the ${SQUIG_TARGETS.getOrNull(tgtIdx)?.first ?: "Harman"} target.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        if (active.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Icon(Icons.Filled.Headset, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Applied: $active", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("Clear", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        scope.launch { store.setDspParametric(emptyList()); store.setDspPreamp(0f); store.setActiveEqProfile("") }
                    }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (source == 0) "Find your headphones / IEM" else "Search squig.link for an IEM") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = { if (query.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", modifier = Modifier.clip(RoundedCornerShape(50)).clickable { query = "" }.padding(4.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        if (working) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.width(36.dp).height(36.dp))
            }
        }
        results.take(20).forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                    scope.launch {
                        working = true
                        val eq = if (source == 0) container.autoEq.fetch(p) else container.squigEq.generate(p)
                        android.util.Log.d("AutoEQ", "apply ${p.name}: ${if (eq == null) "FETCH FAILED" else "preamp=${eq.preampDb} bands=${eq.bands.size}"}")
                        if (eq != null && eq.bands.isNotEmpty()) {
                            store.setDspParametric(eq.bands)
                            store.setDspPreamp(eq.preampDb)
                            store.setDspMode(DspMode.CUSTOM)
                            store.setActiveEqProfile(p.name)
                            query = ""
                            toast("Applied ${p.name} · ${eq.bands.size} bands, ${"%.1f".format(eq.preampDb)} dB preamp")
                        } else {
                            toast(if (eq == null) "Couldn't fetch correction — check connection" else "No filters found for ${p.name}")
                        }
                        working = false
                    }
                }.padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(p.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(Icons.Filled.Add, "Apply", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-switch per output", style = MaterialTheme.typography.bodyLarge)
                Text("Apply each device's bound profile when it connects", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = autoSwitch, onCheckedChange = { v -> scope.launch { store.setAutoEqAutoSwitch(v) } })
        }
        if (active.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        scope.launch {
                            store.upsertEqBinding(EqBinding(container.autoEqController.currentOutputKey(), outLabel, active, prefs.dspPreampDb, prefs.dspParametric))
                            store.setAutoEqAutoSwitch(true)
                        }
                    }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Bind \"$active\" to $outLabel", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        }
        bindings.forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.deviceLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(b.profileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(Icons.Filled.Close, "Unbind", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { scope.launch { store.removeEqBinding(b.deviceKey) } }.padding(4.dp))
            }
        }
    }
}

private fun LazyListScope.systemEqSection(
    prefs: AudioPrefs,
    fx: com.aurora.music.data.AudioEffectsController,
    store: SettingsStore,
    scope: CoroutineScope,
    expanded: SnapshotStateMap<String, Boolean>,
) {
    val bandCount = fx.bandCount
    item {
        SettingsGroup {
            SettingsSwitchRow(Icons.Filled.GraphicEq, "Equalizer", if (fx.available) "${bandCount}-band graphic EQ" else "Not supported on this device", prefs.eqEnabled) { v ->
                scope.launch { store.setEqEnabled(v) }
            }
        }
    }

    if (fx.presetNames.isNotEmpty()) {
        val presetSummary = if (prefs.eqPreset >= 0) fx.presetNames.getOrElse(prefs.eqPreset) { "Custom" } else "Custom"
        collapsible("s_presets", "Presets", Icons.Filled.AutoFixHigh, presetSummary, expanded) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { PresetChip("Custom", selected = prefs.eqPreset < 0) { scope.launch { store.setEqPreset(-1) } } }
                items(fx.presetNames.size) { i ->
                    PresetChip(fx.presetNames[i], selected = prefs.eqPreset == i) {
                        scope.launch {
                            store.setEqPreset(i)
                            store.setEqBands(fx.presetBandLevels(i))
                            if (!prefs.eqEnabled) store.setEqEnabled(true)
                        }
                    }
                }
            }
        }
    }

    val bands = (0 until bandCount).map { prefs.eqBands.getOrElse(it) { 0 } }
    collapsible("s_bands", "Bands", Icons.Filled.Tune, "$bandCount-band", expanded, defaultOpen = true) {
        bands.forEachIndexed { i, mb ->
            BandSlider(
                freqHz = fx.bandFreqsHz.getOrElse(i) { 0 }, valueMb = mb, minMb = fx.minBandMb, maxMb = fx.maxBandMb,
                onChange = { v ->
                    val updated = bands.toMutableList().also { it[i] = v }
                    scope.launch { store.setEqBands(updated); store.setEqPreset(-1); if (!prefs.eqEnabled) store.setEqEnabled(true) }
                },
            )
        }
    }

    val enh = if (prefs.bassBoost > 0 || prefs.virtualizer > 0 || prefs.loudnessGain > 0) "On" else "Off"
    collapsible("s_enh", "Enhancers", Icons.Filled.Whatshot, enh, expanded) {
        EnhancerSlider("Bass boost", prefs.bassBoost, 0..1000) { v -> scope.launch { store.setBassBoost(v) } }
        EnhancerSlider("Virtualizer (headphone widening)", prefs.virtualizer, 0..1000) { v -> scope.launch { store.setVirtualizer(v) } }
        EnhancerSlider("Loudness", prefs.loudnessGain, 0..2000, unit = " mB") { v -> scope.launch { store.setLoudness(v) } }
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

    collapsible("c_graphic", "Graphic EQ", Icons.Filled.Tune, "${layout.name}${if (anyGraphic) " · active" else ""}", expanded, defaultOpen = true) {
        SegmentedRow("Bands", DspCoeffBuilder.GRAPHIC_LAYOUTS.map { it.name }, prefs.dspGraphicLayout) { i ->
            scope.launch { store.setDspGraphicLayout(i); store.setDspGraphicBands(List(DspCoeffBuilder.GRAPHIC_LAYOUTS[i].freqs.size) { 0f }) }
        }
        graphic.forEachIndexed { i, g ->
            DbSliderRow(freqLabel(layout.freqs[i].toInt()), g, -12f..12f) { v ->
                val updated = graphic.toMutableList().also { it[i] = v }
                scope.launch { store.setDspGraphicBands(updated) }
            }
        }
        TextLink("Reset graphic EQ") { scope.launch { store.setDspGraphicBands(List(nBands) { 0f }) } }
    }

    collapsible("c_param", "Parametric EQ", Icons.Filled.GraphicEq, "${prefs.dspParametric.size} band${if (prefs.dspParametric.size == 1) "" else "s"}", expanded) {
        prefs.dspParametric.forEachIndexed { i, band ->
            ParametricBandCard(
                band = band,
                onChange = { nb -> scope.launch { store.setDspParametric(prefs.dspParametric.toMutableList().also { it[i] = nb }) } },
                onRemove = { scope.launch { store.setDspParametric(prefs.dspParametric.toMutableList().also { it.removeAt(i) }) } },
            )
        }
        if (prefs.dspParametric.size < DspCoeffBuilder.MAX_PARAMETRIC) {
            TextLink("+ Add band") { scope.launch { store.setDspParametric(prefs.dspParametric + ParamBand(1000f, 0f, 1f)) } }
        }
    }

    collapsible("c_gain", "Gain & headroom", Icons.Filled.VolumeUp, "Pre-amp ${"%+.0f".format(prefs.dspPreampDb)} dB", expanded) {
        DbSliderRow("Pre-amp", prefs.dspPreampDb, -12f..12f) { v -> scope.launch { store.setDspPreamp(v) } }
        val peak = androidx.compose.runtime.remember(prefs.dspGraphicBands, prefs.dspParametric, prefs.dspGraphicLayout) {
            DspCoeffBuilder.eqPeakDb(DspParams(graphic = graphic.toFloatArray(), graphicFreqs = layout.freqs, graphicQ = layout.q, parametric = prefs.dspParametric.map { DspBand(it.freqHz, it.gainDb, it.q, it.type) }))
        }
        HeadroomRow(peak = peak, preamp = prefs.dspPreampDb) { scope.launch { store.setDspPreamp((-peak).coerceIn(-12f, 0f)) } }
        FloatSliderRow("Balance", prefs.dspBalance, -1f..1f, valueText = balanceLabel(prefs.dspBalance)) { v -> scope.launch { store.setDspBalance(v) } }
    }

    val spatial = buildList { if (prefs.dspWidth != 1f) add("Width %.2f×".format(prefs.dspWidth)); if (prefs.dspCrossfeed > 0f) add("Crossfeed ${(prefs.dspCrossfeed * 100).roundToInt()}%") }.joinToString(" · ").ifBlank { "Off" }
    collapsible("c_spatial", "Spatial", Icons.Filled.SurroundSound, spatial, expanded) {
        FloatSliderRow("Stereo width", prefs.dspWidth, 0f..2f, valueText = "%.2f×".format(prefs.dspWidth)) { v -> scope.launch { store.setDspWidth(v) } }
        FloatSliderRow("Crossfeed", prefs.dspCrossfeed, 0f..1f, valueText = if (prefs.dspCrossfeed <= 0f) "Off" else "${(prefs.dspCrossfeed * 100).roundToInt()}%") { v -> scope.launch { store.setDspCrossfeed(v) } }
    }

    collapsible("c_harm", "Harmonics", Icons.Filled.Whatshot, if (prefs.dspSaturation > 0f) "Tube ${(prefs.dspSaturation * 100).roundToInt()}%" else "Off", expanded) {
        FloatSliderRow("Tube saturation", prefs.dspSaturation, 0f..1f, valueText = if (prefs.dspSaturation <= 0f) "Off" else "${(prefs.dspSaturation * 100).roundToInt()}%") { v -> scope.launch { store.setDspSaturation(v) } }
    }

    val aligned = prefs.dspDelayLeftMs > 0f || prefs.dspDelayRightMs > 0f || prefs.dspTrimLeftDb != 0f || prefs.dspTrimRightDb != 0f
    collapsible("c_align", "Channel alignment", Icons.Filled.SwapHoriz, if (aligned) "Adjusted" else "Off", expanded) {
        FloatSliderRow("Left delay", prefs.dspDelayLeftMs, 0f..20f, valueText = "%.1f ms".format(prefs.dspDelayLeftMs)) { v -> scope.launch { store.setDspDelayLeft(v) } }
        FloatSliderRow("Right delay", prefs.dspDelayRightMs, 0f..20f, valueText = "%.1f ms".format(prefs.dspDelayRightMs)) { v -> scope.launch { store.setDspDelayRight(v) } }
        DbSliderRow("Left trim", prefs.dspTrimLeftDb, -12f..0f) { v -> scope.launch { store.setDspTrimLeft(v) } }
        DbSliderRow("Right trim", prefs.dspTrimRightDb, -12f..0f) { v -> scope.launch { store.setDspTrimRight(v) } }
    }

    val dyn = buildList { if (prefs.dspLimiterEnabled) add("Limiter"); if (prefs.dspCompEnabled) add("Compressor") }.joinToString(" · ").ifBlank { "Off" }
    collapsible("c_dyn", "Dynamics", Icons.Filled.Compress, dyn, expanded) {
        SettingsSwitchRow(Icons.Filled.GraphicEq, "Limiter", "Brick-wall clip protection (recommended)", prefs.dspLimiterEnabled) { v -> scope.launch { store.setDspLimiterEnabled(v) } }
        if (prefs.dspLimiterEnabled) {
            FloatSliderRow("Ceiling", prefs.dspLimiterCeilingDb, -6f..0f, valueText = "%.1f dB".format(prefs.dspLimiterCeilingDb)) { v -> scope.launch { store.setDspCeiling(v) } }
        }
        SettingsSwitchRow(Icons.Filled.GraphicEq, "Compressor", "Even out loud/quiet passages", prefs.dspCompEnabled) { v -> scope.launch { store.setDspCompEnabled(v) } }
        if (prefs.dspCompEnabled) {
            FloatSliderRow("Threshold", prefs.dspCompThreshDb, -40f..0f, valueText = "%.0f dB".format(prefs.dspCompThreshDb)) { v -> scope.launch { store.setDspCompThresh(v) } }
            FloatSliderRow("Ratio", prefs.dspCompRatio, 1f..10f, valueText = "%.1f:1".format(prefs.dspCompRatio)) { v -> scope.launch { store.setDspCompRatio(v) } }
        }
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun ParametricBandCard(band: ParamBand, onChange: (ParamBand) -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(freqLabel(band.freqHz.toInt()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("%+.1f dB · Q%.1f".format(band.gainDb, band.q), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                Icons.Filled.Close, "Remove band",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRemove).padding(start = 8.dp),
            )
        }
        FloatSliderRow("Freq", band.freqHz, 20f..20000f, valueText = freqLabel(band.freqHz.toInt())) { v -> onChange(band.copy(freqHz = v)) }
        DbSliderRow("Gain", band.gainDb, -15f..15f) { v -> onChange(band.copy(gainDb = v)) }
        FloatSliderRow("Q", band.q, 0.3f..8f, valueText = "%.2f".format(band.q)) { v -> onChange(band.copy(q = v)) }
    }
}

@Composable
private fun EngineDropdown(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("System effects", "Custom DSP", "Off")
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text("Engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { expanded = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(labels.getOrElse(selected) { labels[0] }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, "Choose engine", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                labels.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { Text(label, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { onSelect(i); expanded = false },
                        trailingIcon = if (i == selected) {
                            { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun BandSlider(freqHz: Int, valueMb: Int, minMb: Int, maxMb: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(freqLabel(freqHz), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
            Slider(
                value = valueMb.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = minMb.toFloat()..maxMb.toFloat(),
                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            )
            Text("%+.1f".format(valueMb / 100f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
        }
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

@Composable
private fun EnhancerSlider(title: String, value: Int, range: IntRange, unit: String = "", onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            val pct = if (unit.isBlank()) "${(value * 100 / (range.last.coerceAtLeast(1)))}%" else "$value$unit"
            Text(pct, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

private fun balanceLabel(b: Float): String = when {
    b < -0.01f -> "L ${(-b * 100).roundToInt()}%"
    b > 0.01f -> "R ${(b * 100).roundToInt()}%"
    else -> "Center"
}

private fun freqLabel(hz: Int): String = when {
    hz <= 0 -> "—"
    hz >= 1000 -> if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)
    else -> "$hz Hz"
}
