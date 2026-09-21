package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aurora.music.data.tuning.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TuningFitSettingsDialog(project: TuningProject, onDismiss: () -> Unit, onSave: (TuningFitConfig) -> Unit) {
    val initial = project.config
    var values by remember { mutableStateOf(mapOf(
        "rate" to initial.sampleRate.toString(), "bands" to initial.bandBudget.toString(),
        "low" to initial.minFrequencyHz.toString(), "high" to initial.maxFrequencyHz.toString(),
        "boost" to initial.maxBoostDb.toString(), "cut" to initial.maxCutDb.toString(),
        "minQ" to initial.minQ.toString(), "maxQ" to initial.maxQ.toString(), "smooth" to initial.smoothingOctaves.toString(),
        "bass" to initial.bassDb.toString(), "bassHz" to initial.bassFrequencyHz.toString(),
        "tilt" to initial.tiltDbPerOctave.toString(), "ear" to initial.earGainDb.toString(), "earHz" to initial.earGainFrequencyHz.toString(),
        "trebleHz" to initial.trebleStartHz.toString(), "trebleBoost" to (initial.trebleMaxBoostDb ?: 3.0).toString(),
        "trebleCut" to (initial.trebleMaxCutDb ?: 6.0).toString(), "trebleQ" to (initial.trebleMaxQ ?: 2.0).toString(),
        "leftTrim" to initial.leftTrimDb.toString(), "rightTrim" to initial.rightTrimDb.toString(),
        "leftDelay" to initial.leftDelayMs.toString(), "rightDelay" to initial.rightDelayMs.toString(),
    ) + listOf("l" to initial.leftLimits, "r" to initial.rightLimits).flatMap { (prefix, limits) -> listOf(
        "${prefix}Boost" to (limits?.maxBoostDb ?: initial.maxBoostDb).toString(), "${prefix}Cut" to (limits?.maxCutDb ?: initial.maxCutDb).toString(),
        "${prefix}MinQ" to (limits?.minQ ?: initial.minQ).toString(), "${prefix}MaxQ" to (limits?.maxQ ?: initial.maxQ).toString()) }.toMap()) }
    var channelMode by remember { mutableStateOf(initial.channelMode) }
    var normalization by remember { mutableStateOf(initial.normalization) }
    var treble by remember { mutableStateOf(initial.trebleMaxBoostDb != null || initial.trebleMaxCutDb != null || initial.trebleMaxQ != null) }
    var linkedLimits by remember { mutableStateOf(initial.leftLimits == null && initial.rightLimits == null) }
    var section by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(appString(R.string.text_fitting_settings_d58cce)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningChoiceRow(appString(R.string.text_channel_intent_e006ec), TuningChannelMode.entries.map { it.label() }, channelMode.ordinal) {
                if (!busy) { channelMode = TuningChannelMode.entries[it]; error = null }
            }
            Text(when (channelMode) {
                TuningChannelMode.LEFT -> appString(R.string.text_left_channel_correction_210202)
                TuningChannelMode.RIGHT -> appString(R.string.text_right_channel_correction_e4ef15)
                TuningChannelMode.INDEPENDENT -> appString(R.string.text_separate_correction_per_channel_shares_the_band_budget_acace8)
                TuningChannelMode.LINKED_AVERAGE -> appString(R.string.text_averages_both_measurements_for_a_shared_correction_886d6b)
            }, style = MaterialTheme.typography.bodySmall)
            TuningChoiceRow(appString(R.string.text_level_normalization_31c168), listOf(appString(R.string.text_none_6eef66), appString(R.string.text_match_mean_200_2000_hz_b8838a)), normalization.ordinal) {
                if (!busy) { normalization = TuningNormalization.entries[it]; error = null }
            }
            TuningChoiceRow(appString(R.string.text_controls_bee75c), listOf(appString(R.string.text_fit_limits_ec1134), appString(R.string.text_target_shape_57174c), appString(R.string.text_treble_f66bec), appString(R.string.text_channel_limits_e045d3), appString(R.string.text_trim_and_delay_d38388)), section) { section = it }
            val fields = when (section) {
                1 -> listOf(Triple("bass", appString(R.string.text_bass_db_765749), ""), Triple("bassHz", appString(R.string.text_bass_corner_hz_9bf7fa), ""),
                    Triple("tilt", appString(R.string.text_tilt_db_octave_9349c9), ""), Triple("ear", appString(R.string.text_ear_gain_db_91d15e), ""), Triple("earHz", appString(R.string.text_ear_gain_center_hz_213d07), ""))
                2 -> {
                    SettingsSwitchRow(title = appString(R.string.text_limit_treble_correction_280b63), subtitle = appString(R.string.text_limits_transition_over_one_octave_4ebf05), checked = treble, onCheckedChange = { treble = it })
                    if (treble) listOf(Triple("trebleHz", appString(R.string.text_treble_start_hz_614974), ""), Triple("trebleBoost", appString(R.string.text_maximum_treble_boost_db_bfd6e9), ""),
                        Triple("trebleCut", appString(R.string.text_maximum_treble_cut_db_384290), ""), Triple("trebleQ", appString(R.string.text_maximum_treble_q_659c12), "")) else emptyList()
                }
                3 -> {
                    SettingsSwitchRow(title = appString(R.string.text_linked_fit_limits_43f930), subtitle = appString(R.string.text_separate_limits_require_independent_channels_60e8b3), checked = linkedLimits, onCheckedChange = { linkedLimits = it })
                    if (!linkedLimits) listOf("l" to appString(R.string.text_left_8ae1c3), "r" to appString(R.string.text_right_954daa)).flatMap { (prefix, label) -> listOf(
                        Triple("${prefix}Boost", appString(R.string.text_maximum_boost_db_520502, (label)), ""), Triple("${prefix}Cut", appString(R.string.text_maximum_cut_db_eaff3a, (label)), ""),
                        Triple("${prefix}MinQ", appString(R.string.text_minimum_q_270623, (label)), ""), Triple("${prefix}MaxQ", appString(R.string.text_maximum_q_b3dc54, (label)), "")) } else emptyList()
                }
                4 -> {
                    Text(appString(R.string.text_manual_alignment_no_automatic_phase_correction_f49cec), style = MaterialTheme.typography.bodySmall)
                    listOf(Triple("leftTrim", appString(R.string.text_left_trim_db_27818d), ""), Triple("rightTrim", appString(R.string.text_right_trim_db_ba0e33), ""),
                        Triple("leftDelay", appString(R.string.text_left_delay_ms_1f565b), ""), Triple("rightDelay", appString(R.string.text_right_delay_ms_ffbffc), ""))
                }
                else -> listOf(
                Triple("rate", appString(R.string.text_design_sample_rate_hz_bdb79c), appString(R.string.text_8_000_768_000_hz_277a0d)),
                Triple("bands", appString(R.string.text_total_parametric_band_budget_6a6f38), appString(R.string.text_1_128_total_up_to_64_per_channel_336445)),
                Triple("low", appString(R.string.text_minimum_fitting_frequency_hz_357d9e), appString(R.string.text_10_24_000_hz_below_the_maximum_921163)),
                Triple("high", appString(R.string.text_maximum_fitting_frequency_hz_8350af), appString(R.string.text_10_24_000_hz_limited_by_shared_data_and_nyquist_8302ea)),
                Triple("boost", appString(R.string.text_maximum_boost_db_015774), appString(R.string.text_0_24_db_aa7fe4)),
                Triple("cut", appString(R.string.text_maximum_cut_db_38d770), appString(R.string.text_0_30_db_enter_a_positive_value_5ac0ad)),
                Triple("minQ", appString(R.string.text_minimum_q_e71a56), appString(R.string.text_0_1_100_no_greater_than_maximum_q_9d5f73)),
                Triple("maxQ", appString(R.string.text_maximum_q_b595c7), "0.1–100"),
                Triple("smooth", appString(R.string.text_smoothing_width_octaves_52255d), appString(R.string.text_0_1_0_is_off_0_1666667_is_about_1_6_octave_4ddee3)),
            )
            }
            fields.forEach { (key, label, _) ->
                OutlinedTextField(values.getValue(key), { values = values + (key to it); error = null },
                    label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    trailingIcon = {
                        if (key in listOf("bass", "tilt", "ear", "leftTrim", "rightTrim")) IconButton(enabled = !busy, onClick = {
                            val value = values.getValue(key)
                            values = values + (key to if (value.startsWith("-")) value.drop(1) else "-$value")
                            error = null
                        }) { Icon(Icons.Filled.Exposure, appString(R.string.text_change_sign_d00c6c)) }
                    })
            }
            if (section != 4) Text(appString(R.string.text_magnitude_correction_only_phase_is_not_fitted_885ea8), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val submitted = values; val mode = channelMode; val norm = normalization
        val limitTreble = treble; val linkLimits = linkedLimits
        busy = true
        scope.launch {
          try {
           val next = withContext(Dispatchers.Default) {
            fun number(key: String): Double = submitted.getValue(key).replace(',', '.').toDoubleOrNull()
                ?.takeIf { it.isFinite() } ?: throw IllegalArgumentException(appString(R.string.text_enter_a_finite_number_for_every_setting_49042c))
            fun integer(key: String): Int = submitted.getValue(key).toIntOrNull() ?: throw IllegalArgumentException(appString(R.string.text_use_whole_numbers_for_sample_rate_and_band_budget_c979ce))
            val next = TuningFitConfig(integer("rate"), integer("bands"), number("low"), number("high"),
                number("boost"), number("cut"), number("minQ"), number("maxQ"), mode, norm, number("smooth"),
                bassDb = number("bass"), bassFrequencyHz = number("bassHz"), tiltDbPerOctave = number("tilt"),
                earGainDb = number("ear"), earGainFrequencyHz = number("earHz"), trebleStartHz = number("trebleHz"),
                trebleMaxBoostDb = if (limitTreble) number("trebleBoost") else null, trebleMaxCutDb = if (limitTreble) number("trebleCut") else null,
                trebleMaxQ = if (limitTreble) number("trebleQ") else null,
                leftLimits = if (!linkLimits && mode == TuningChannelMode.INDEPENDENT) TuningChannelLimits(number("lBoost"), number("lCut"), number("lMinQ"), number("lMaxQ")) else null,
                rightLimits = if (!linkLimits && mode == TuningChannelMode.INDEPENDENT) TuningChannelLimits(number("rBoost"), number("rCut"), number("rMinQ"), number("rMaxQ")) else null,
                leftTrimDb = number("leftTrim"), rightTrimDb = number("rightTrim"), leftDelayMs = number("leftDelay"), rightDelayMs = number("rightDelay"))
            TuningProjectCodec.validate(project.copy(config = next, generatedFit = null)).config
           }
           onSave(next)
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { error = failure.message ?: appString(R.string.text_check_the_fitting_limits_5de573) }
          finally { busy = false }
        }
    }) { Text(if (busy) appString(R.string.text_validating_c07434) else appString(R.string.text_use_settings_39966a)) } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(appString(R.string.text_cancel_77dfd2)) } })
}

@Composable
internal fun TuningChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.getOrElse(selected) { options.first() }, Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, appString(R.string.text_choose_c2c00f, (title)))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, name -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(index) }) }
            }
        }
    }
}

internal fun TuningChannelMode.label(): String = when (this) {
    TuningChannelMode.LEFT -> appString(R.string.text_left_only_6241b8); TuningChannelMode.RIGHT -> appString(R.string.text_right_only_ef29b6)
    TuningChannelMode.INDEPENDENT -> appString(R.string.text_independent_left_right_d80711); TuningChannelMode.LINKED_AVERAGE -> appString(R.string.text_linked_average_of_left_right_80c6d6)
}
