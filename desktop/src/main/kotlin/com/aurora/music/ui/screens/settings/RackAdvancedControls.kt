package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import com.aurora.music.data.*
import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.playback.engine.RackNodeMeter
import com.aurora.music.playback.engine.EqResponseCalculator

@Composable
internal fun RackAdvancedControls(node: ProcessingRackNode, onEdit: ((ProcessingRackNode) -> ProcessingRackNode) -> Unit,
    meter: RackNodeMeter? = null, sampleRate: Int? = null) {
    when (node.kind) {
        RackNodeKind.UTILITY -> {
            val u = node.utility ?: RackUtility()
            fun edit(next: RackUtility) = onEdit { it.copy(utility = next) }
            SettingsGroup {
                SettingsSwitchRow(title = appString(R.string.text_dc_blocker_d73679), checked = u.dcBlock, onCheckedChange = { edit(u.copy(dcBlock = it)) })
                SettingsSwitchRow(title = appString(R.string.text_mono_bass_b1ddea), checked = u.monoBassHz > 0, onCheckedChange = { edit(u.copy(monoBassHz = if (it) 120.0 else 0.0)) })
                if (u.monoBassHz > 0) AdvancedSlider(appString(R.string.text_mono_bass_cutoff_605472), u.monoBassHz, 20.0..500.0, appString(R.string.text_hz_97aaf5)) { edit(u.copy(monoBassHz = it)) }
                AdvancedSlider(appString(R.string.text_left_left_6c2130), u.ll, -2.0..2.0, "×") { edit(u.copy(ll = it)) }
                AdvancedSlider(appString(R.string.text_right_left_0aa851), u.lr, -2.0..2.0, "×") { edit(u.copy(lr = it)) }
                AdvancedSlider(appString(R.string.text_left_right_03b2d1), u.rl, -2.0..2.0, "×") { edit(u.copy(rl = it)) }
                AdvancedSlider(appString(R.string.text_right_right_ebb708), u.rr, -2.0..2.0, "×") { edit(u.copy(rr = it)) }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = { edit(u.copy(ll = -u.ll, lr = -u.lr)) }) { Text(appString(R.string.text_invert_left_2baeba)) }
                    TextButton(onClick = { edit(u.copy(rl = -u.rl, rr = -u.rr)) }) { Text(appString(R.string.text_invert_right_b4bd7d)) }
                }
            }
        }
        RackNodeKind.ALIGNMENT_DELAY -> {
            val u = node.utility ?: RackUtility()
            SettingsGroup {
                AdvancedSlider(appString(R.string.text_alignment_delay_56ff3c), u.delayMs, 0.0..100.0, "ms") { value -> onEdit { it.copy(utility = u.copy(delayMs = value)) } }
                Text(appString(R.string.text_other_branches_align_automatically_at_each_mix_e6de43), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        RackNodeKind.DYNAMIC_EQ -> {
            val d = node.dynamic ?: RackDynamicEq()
            fun edit(next: RackDynamicEq) = onEdit { it.copy(dynamic = next) }
            SettingsGroup {
                DynamicGainMeter(meter?.changeDb, appString(R.string.text_filter_change_93d9b2))
                if (meter != null) DynamicResponse(node, meter.changeDb, sampleRate ?: 48000)
                AdvancedSlider(appString(R.string.text_filter_frequency_9f283c), d.frequencyHz, 20.0..20000.0, appString(R.string.text_hz_97aaf5)) { edit(d.copy(frequencyHz = it)) }
                AdvancedSlider(appString(R.string.text_filter_q_9280eb), d.q, .1..12.0, "") { edit(d.copy(q = it)) }
                AdvancedSlider(appString(R.string.text_detector_frequency_7df85c), d.detectorHz, 20.0..20000.0, appString(R.string.text_hz_97aaf5)) { edit(d.copy(detectorHz = it)) }
                AdvancedSlider(appString(R.string.text_detector_q_480b14), d.detectorQ, .1..12.0, "") { edit(d.copy(detectorQ = it)) }
                SettingsSwitchRow(title = appString(R.string.text_boost_below_threshold_b552a1), checked = d.upward, onCheckedChange = { edit(d.copy(upward = it)) })
                DynamicsControls(d.dynamics, true) { edit(d.copy(dynamics = it)) }
            }
        }
        RackNodeKind.MULTIBAND -> {
            val m = node.multiband ?: RackMultiband()
            var selected by remember(node.id) { mutableIntStateOf(0) }
            fun edit(next: RackMultiband) = onEdit { it.copy(multiband = next) }
            SettingsGroup {
                AdvancedSlider(appString(R.string.text_low_crossover_cbf2b7), m.lowHz, 40.0..minOf(2000.0, m.highHz / 2), appString(R.string.text_hz_97aaf5)) { edit(m.copy(lowHz = it)) }
                AdvancedSlider(appString(R.string.text_high_crossover_ade7d1), m.highHz, maxOf(400.0, m.lowHz * 2)..16000.0, appString(R.string.text_hz_97aaf5)) { edit(m.copy(highHz = it)) }
                SegmentedRow(appString(R.string.text_band_895f49), listOf(appString(R.string.text_low_a12494), appString(R.string.text_mid_9a6af7), appString(R.string.text_high_b1a595)), selected) { selected = it }
                DynamicGainMeter(meter?.bandChangesDb?.getOrNull(selected), appString(R.string.text_band_reduction_f99df5))
                DynamicsControls(m.bands[selected], false) { value -> edit(m.copy(bands = m.bands.mapIndexed { i, band -> if (i == selected) value else band })) }
            }
        }
        RackNodeKind.LOUDNESS -> {
            val l = node.loudness ?: RackLoudness()
            fun edit(next: RackLoudness) = onEdit { it.copy(loudness = next) }
            SettingsGroup {
                DynamicGainMeter(meter?.changeDb, appString(R.string.text_current_bass_compensation_15bc46))
                AdvancedSlider(appString(R.string.text_reference_volume_38aeb3), l.referenceVolume * 100, 5.0..100.0, "%") { edit(l.copy(referenceVolume = it / 100)) }
                AdvancedSlider(appString(R.string.text_bass_limit_956ae9), l.bassCapDb, 0.0..12.0, appString(R.string.text_db_e44622)) { edit(l.copy(bassCapDb = it)) }
                AdvancedSlider(appString(R.string.text_treble_limit_abf8ab), l.trebleCapDb, 0.0..6.0, appString(R.string.text_db_e44622)) { edit(l.copy(trebleCapDb = it)) }
                AdvancedSlider(appString(R.string.text_strength_24d3e4), l.strength * 100, 0.0..100.0, "%") { edit(l.copy(strength = it / 100)) }
                Text(appString(R.string.text_relative_to_media_volume_this_is_not_an_spl_estimate_8959bd), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        RackNodeKind.DYNAMICS -> {
            val d = node.dynamics ?: RackDynamicsEffect()
            fun edit(value: RackDynamicsEffect) = onEdit { it.copy(dynamics = value) }
            SettingsGroup {
                RackModePicker(appString(R.string.text_effect_720b5d), listOf(appString(R.string.text_expander_c3d792), appString(R.string.text_gate_5701b5), appString(R.string.text_de_esser_1fe14b)), d.mode.ordinal) { edit(d.copy(mode = RackDynamicsMode.entries[it])) }
                DynamicGainMeter(meter?.changeDb, appString(R.string.text_gain_reduction_692931))
                if (d.mode == RackDynamicsMode.DEESSER) AdvancedSlider(appString(R.string.text_sibilance_cutoff_6da1b4), d.frequencyHz, 1000.0..16000.0, appString(R.string.text_hz_97aaf5)) { edit(d.copy(frequencyHz = it)) }
                if (d.mode == RackDynamicsMode.GATE) AdvancedSlider(appString(R.string.text_hold_3bd328), d.holdMs, 0.0..500.0, "ms") { edit(d.copy(holdMs = it)) }
                DynamicsControls(d.dynamics, true, gate = d.mode == RackDynamicsMode.GATE) { edit(d.copy(dynamics = it)) }
            }
        }
        RackNodeKind.TONE -> {
            val t = node.tone ?: RackTone()
            fun edit(value: RackTone) = onEdit { it.copy(tone = value) }
            SettingsGroup {
                RackModePicker(appString(R.string.text_color_1d0c83), listOf(appString(R.string.text_exciter_298dd8), appString(R.string.text_tape_c51c55), appString(R.string.text_tube_8315d0), appString(R.string.text_bass_99cb7d)), t.mode.ordinal) { index ->
                    val mode = RackToneMode.entries[index]
                    edit(t.copy(mode = mode, frequencyHz = if (mode == RackToneMode.BASS) 100.0 else 3000.0))
                }
                if (t.mode == RackToneMode.BASS) AdvancedSlider(appString(R.string.text_bass_boost_c946c9), t.driveDb * .5, 0.0..12.0, appString(R.string.text_db_e44622)) { edit(t.copy(driveDb = it * 2)) }
                else AdvancedSlider(appString(R.string.text_drive_a02bb4), t.driveDb, 0.0..24.0, appString(R.string.text_db_e44622)) { edit(t.copy(driveDb = it)) }
                AdvancedSlider(appString(R.string.text_amount_43dc85), t.amount * 100, 0.0..100.0, "%") { edit(t.copy(amount = it / 100)) }
                if (t.mode in listOf(RackToneMode.BASS, RackToneMode.EXCITER)) AdvancedSlider(appString(R.string.text_cutoff_118de3), t.frequencyHz,
                    if (t.mode == RackToneMode.BASS) 30.0..300.0 else 1000.0..16000.0, appString(R.string.text_hz_97aaf5)) { edit(t.copy(frequencyHz = it)) }
                if (t.mode != RackToneMode.BASS) Text(appString(R.string.text_2u00d7_oversampling_tape_and_tube_are_tone_effects_7297b7), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        RackNodeKind.SPACE -> {
            val s = node.space ?: RackSpace()
            fun edit(value: RackSpace) = onEdit { it.copy(space = value) }
            SettingsGroup {
                RackModePicker(appString(R.string.text_effect_720b5d), listOf(appString(R.string.text_stereo_delay_d3073c), appString(R.string.text_ping_pong_delay_3982a4), appString(R.string.text_reverb_052ba8)), s.mode.ordinal) { edit(s.copy(mode = RackSpaceMode.entries[it])) }
                AdvancedSlider(if (s.mode == RackSpaceMode.REVERB) appString(R.string.text_pre_delay_966730) else appString(R.string.text_delay_b4c200), s.timeMs, 1.0..750.0, "ms") { edit(s.copy(timeMs = it)) }
                if (s.mode == RackSpaceMode.REVERB) AdvancedSlider(appString(R.string.text_decay_423840), s.decaySeconds, .1..4.0, "s") { edit(s.copy(decaySeconds = it)) }
                else AdvancedSlider(appString(R.string.text_feedback_c8d767), s.feedback * 100, 0.0..65.0, "%") { edit(s.copy(feedback = it / 100)) }
                AdvancedSlider(appString(R.string.text_damping_cutoff_5e7aff), s.dampingHz, 200.0..16000.0, appString(R.string.text_hz_97aaf5)) { edit(s.copy(dampingHz = it)) }
                Text(appString(R.string.text_use_the_stage_mix_to_blend_with_the_original_audio_ff5a7b), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        RackNodeKind.MODULATION -> {
            val m = node.modulation ?: RackModulation()
            fun edit(value: RackModulation) = onEdit { it.copy(modulation = value) }
            SettingsGroup {
                RackModePicker(appString(R.string.text_effect_720b5d), listOf(appString(R.string.text_chorus_6973e1), appString(R.string.text_flanger_dc4066), appString(R.string.text_phaser_1a55bd), appString(R.string.text_tremolo_cd439d), appString(R.string.text_vibrato_e4de40)), m.mode.ordinal) { edit(m.copy(mode = RackModulationMode.entries[it])) }
                AdvancedSlider(appString(R.string.text_rate_3a9c73), m.rateHz, .05..10.0, appString(R.string.text_hz_97aaf5)) { edit(m.copy(rateHz = it)) }
                AdvancedSlider(appString(R.string.text_depth_df0e29), m.depth * 100, 0.0..100.0, "%") { edit(m.copy(depth = it / 100)) }
                AdvancedSlider(appString(R.string.text_stereo_phase_05d1da), m.stereoPhase, 0.0..180.0, "\u00b0") { edit(m.copy(stereoPhase = it)) }
                if (m.mode in listOf(RackModulationMode.CHORUS, RackModulationMode.FLANGER, RackModulationMode.PHASER))
                    AdvancedSlider(appString(R.string.text_feedback_c8d767), m.feedback * 100, -65.0..65.0, "%") { edit(m.copy(feedback = it / 100)) }
            }
        }
        RackNodeKind.SATURATION -> SettingsGroup {
            SegmentedRow(appString(R.string.text_oversampling_75358a), listOf(appString(R.string.text_off_e3de5a), "2×", "4×", "8×"), listOf(1, 2, 4, 8).indexOf(node.oversampling ?: 1)) { value ->
                onEdit { it.copy(oversampling = listOf(1, 2, 4, 8)[value]) }
            }
        }
        else -> Unit
    }
}

@Composable
private fun DynamicGainMeter(change: Double?, title: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(if (change == null) appString(R.string.text_no_active_audio_3c1032, (title)) else appString(R.string.text_1f_db_22efd4, (title)).format(change), style = MaterialTheme.typography.labelMedium)
        if (change != null) LinearProgressIndicator(progress = { (kotlin.math.abs(change) / 24).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun DynamicResponse(node: ProcessingRackNode, gain: Double, rate: Int) {
    val settings = node.dynamic ?: RackDynamicEq()
    val values = remember(settings.frequencyHz, settings.q, gain, rate, node.wet, node.bypass) {
        EqResponseCalculator.calculateNode(node.copy(kind = RackNodeKind.EQ,
            audio = AudioPrefs(dspParametric = listOf(ParamBand(settings.frequencyHz.toFloat(), gain.toFloat(), settings.q.toFloat())))), rate, 128).magnitudeDb
    }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val center = size.height * .5f
            drawLine(grid, Offset(0f, center), Offset(size.width, center))
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = index.toFloat() / values.lastIndex * size.width
                val y = center - value.coerceIn(-24.0, 24.0).toFloat() / 24 * center
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
        Text(appString(R.string.text_20_hz_hz_24_db_filter_only_e3a70a, (minOf(20000, rate / 2))), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DynamicsControls(d: RackDynamics, detectorSolo: Boolean, gate: Boolean = false, onChange: (RackDynamics) -> Unit) {
    SegmentedRow(appString(R.string.text_detector_8e9b36), listOf(appString(R.string.text_peak_c83dbb), "RMS"), d.detector.ordinal) { onChange(d.copy(detector = RackDetector.entries[it])) }
    AdvancedSlider(appString(R.string.text_threshold_c51f7b), d.thresholdDb, -80.0..0.0, "dBFS") { onChange(d.copy(thresholdDb = it)) }
    if (!gate) AdvancedSlider(appString(R.string.text_ratio_794f65), d.ratio, 1.0..20.0, ":1") { onChange(d.copy(ratio = it)) }
    AdvancedSlider(appString(R.string.text_attack_1b81f2), d.attackMs, .1..200.0, "ms") { onChange(d.copy(attackMs = it)) }
    AdvancedSlider(appString(R.string.text_release_d41f56), d.releaseMs, 5.0..3000.0, "ms") { onChange(d.copy(releaseMs = it)) }
    if (!gate) AdvancedSlider(appString(R.string.text_knee_116ee8), d.kneeDb, 0.0..24.0, appString(R.string.text_db_e44622)) { onChange(d.copy(kneeDb = it)) }
    if (!gate) AdvancedSlider(appString(R.string.text_maximum_change_cd4bd5), d.rangeDb, 0.0..24.0, appString(R.string.text_db_e44622)) { onChange(d.copy(rangeDb = it)) }
    AdvancedSlider(appString(R.string.text_makeup_21c25b), d.makeupDb, -24.0..12.0, appString(R.string.text_db_e44622)) { onChange(d.copy(makeupDb = it)) }
    SettingsSwitchRow(title = if (detectorSolo) appString(R.string.text_listen_to_detector_ea443f) else appString(R.string.text_solo_band_3fb8b7), checked = d.solo, onCheckedChange = { onChange(d.copy(solo = it)) })
    SettingsSwitchRow(title = appString(R.string.text_mute_0f0973), checked = d.mute, onCheckedChange = { onChange(d.copy(mute = it)) })
    Text(appString(R.string.text_stereo_linked_6e3759), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun RackModePicker(title: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { expanded = true }) { Text("$title: ${labels[selected]}") }
        DropdownMenu(expanded, { expanded = false }) {
            labels.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(index) }) }
        }
    }
}

@Composable
private fun AdvancedSlider(title: String, value: Double, range: ClosedFloatingPointRange<Double>, suffix: String, onChange: (Double) -> Unit) =
    SettingsSliderRow(title, "%.1f %s".format(value, suffix), value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
        range.start.toFloat()..range.endInclusive.toFloat()) { onChange(it.toDouble()) }

@Composable
internal fun RackRoutingDialog(title: String, available: List<ProcessingRackNode>, initial: List<RackInput>?, onDismiss: () -> Unit,
    onSave: (List<RackInput>?) -> Unit) {
    var inputs by remember { mutableStateOf(initial ?: listOf(RackInput(available.lastOrNull()?.id ?: RackInput.INPUT))) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            inputs.forEachIndexed { index, edge ->
                var sourceMenu by remember(index) { mutableStateOf(false) }
                var channelMenu by remember(index) { mutableStateOf(false) }
                fun edit(value: RackInput) { inputs = inputs.mapIndexed { i, old -> if (i == index) value else old } }
                Row {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { sourceMenu = true }) { Text(available.firstOrNull { it.id == edge.source }?.name ?: appString(R.string.text_input_b568d4)) }
                        DropdownMenu(sourceMenu, { sourceMenu = false }) {
                            DropdownMenuItem({ Text(appString(R.string.text_input_b568d4)) }, onClick = { edit(edge.copy(source = RackInput.INPUT)); sourceMenu = false })
                            available.forEach { source -> DropdownMenuItem({ Text(source.name) }, onClick = { edit(edge.copy(source = source.id)); sourceMenu = false }) }
                        }
                    }
                    Box {
                        TextButton(onClick = { channelMenu = true }) { Text(edge.channel.label()) }
                        DropdownMenu(channelMenu, { channelMenu = false }) {
                            RackChannel.entries.forEach { channel -> DropdownMenuItem({ Text(channel.label()) }, onClick = { edit(edge.copy(channel = channel)); channelMenu = false }) }
                        }
                    }
                }
                AdvancedSlider(appString(R.string.text_input_gain_39981c, (index + 1)), edge.gainDb, -60.0..12.0, appString(R.string.text_db_e44622)) { edit(edge.copy(gainDb = it)) }
                if (inputs.size > 1) TextButton(onClick = { inputs = inputs.filterIndexed { i, _ -> i != index } }) { Text(appString(R.string.text_remove_input_d3f475)) }
            }
            if (inputs.size < 4) TextButton(onClick = { inputs = inputs + RackInput() }) { Text(appString(R.string.text_add_input_2a21ea)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        if (inputs.map { it.source to it.channel }.distinct().size != inputs.size) error = appString(R.string.text_choose_distinct_inputs_00550b) else onSave(inputs)
    }) { Text(appString(R.string.text_save_efc007)) } }, dismissButton = {
        Row { TextButton(onClick = { onSave(null) }) { Text(appString(R.string.text_serial_83f8c3)) }; TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } }
    })
}

private fun RackChannel.label() = when (this) {
    RackChannel.STEREO -> appString(R.string.text_stereo_f4f390); RackChannel.LEFT -> appString(R.string.text_left_8ae1c3); RackChannel.RIGHT -> appString(R.string.text_right_954daa)
    RackChannel.MID -> appString(R.string.text_mid_9a6af7); RackChannel.SIDE -> appString(R.string.text_side_265283); RackChannel.ENCODE_MS -> appString(R.string.text_encode_m_s_3c88d0); RackChannel.DECODE_MS -> appString(R.string.text_decode_m_s_0898c4)
}

@Composable
internal fun RackImpulsePicker(node: ProcessingRackNode, library: List<ImpulseLibraryEntry>, onEdit: ((ProcessingRackNode) -> ProcessingRackNode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { menu = true }) { Text(library.firstOrNull { it.id == node.impulseId }?.name ?: if (node.impulseId == null) appString(R.string.text_use_standard_impulse_289fd1) else appString(R.string.text_missing_impulse_7f47dc)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(appString(R.string.text_use_standard_impulse_289fd1)) }, onClick = { onEdit { it.copy(impulseId = null) }; menu = false })
            library.forEach { entry -> DropdownMenuItem({ Text(entry.name + if (entry.prepared != null) appString(R.string.text_prepared_19d34e) else appString(R.string.text_source_ddb023)) }, onClick = { onEdit { it.copy(impulseId = entry.id) }; menu = false }) }
        }
    }
}
