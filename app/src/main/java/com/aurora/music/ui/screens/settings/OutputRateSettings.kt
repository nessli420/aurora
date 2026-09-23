package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.OutputRatePolicyCodec
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import com.aurora.music.playback.engine.OutputDitherMode

@Composable
fun OutputRateSettings(policy: OutputRatePolicy, onChange: ((OutputRatePolicy) -> OutputRatePolicy) -> Unit) {
    SettingsGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appString(R.string.text_sample_rate_7a0316), style = MaterialTheme.typography.titleMedium)
            OutputRateMenu(appString(R.string.text_policy_bb9cf1), when (policy.mode) {
                OutputRateMode.FOLLOW_SOURCE -> appString(R.string.text_follow_source_e0e6b1)
                OutputRateMode.FIXED -> appString(R.string.text_fixed_rate_4869d0)
                OutputRateMode.COMPATIBLE_MAXIMUM -> appString(R.string.text_compatible_maximum_932602)
            }, listOf(appString(R.string.text_follow_source_e0e6b1), appString(R.string.text_fixed_rate_4869d0), appString(R.string.text_compatible_maximum_932602))) { index ->
                onChange { it.copy(mode = OutputRateMode.entries[index]) }
            }
            if (policy.mode != OutputRateMode.FOLLOW_SOURCE) {
                val fixed = policy.mode == OutputRateMode.FIXED
                val selected = if (fixed) policy.fixedRate else policy.maximumRate
                OutputRateMenu(if (fixed) appString(R.string.text_rate_3a9c73) else appString(R.string.text_maximum_a8df2f), appString(R.string.text_khz_dd177d, (selected / 1000.0)),
                    OutputRatePolicyCodec.RATES.map { appString(R.string.text_khz_dd177d, (it / 1000.0)) }) { index ->
                    onChange { current -> if (fixed) current.copy(fixedRate = OutputRatePolicyCodec.RATES[index])
                        else current.copy(maximumRate = OutputRatePolicyCodec.RATES[index]) }
                }
                if (!fixed) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.text_keep_44_1_48_khz_family_aa509c), Modifier.weight(1f))
                    Switch(policy.preserveFamily, { value -> onChange { it.copy(preserveFamily = value) } })
                }
                Text(appString(R.string.text_precision_android_and_processed_usb_applies_when_output_reopens_bf3f55), style = MaterialTheme.typography.bodySmall)
            }
            val ditherOptions = listOf(appString(R.string.text_off_e3de5a), "TPDF", appString(R.string.text_noise_shaped_c7a434))
            OutputRateMenu(appString(R.string.text_integer_dither_bca696), ditherOptions[policy.ditherMode.ordinal], ditherOptions) { index ->
                onChange { it.copy(tpdfDither = index != 0, noiseShaping = index == 2) }
            }
            if (policy.ditherMode == OutputDitherMode.NOISE_SHAPED) {
                Text(appString(R.string.text_first_order_shaping_uses_tpdf_below_44_1_khz_459cb0), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OutputRateMenu(label: String, value: String, options: List<String>, select: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text("$label: $value") }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEachIndexed { index, title -> DropdownMenuItem({ Text(title) }, { select(index); expanded = false }) }
        }
    }
}
