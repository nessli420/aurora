package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.aurora.music.data.OutputRatePolicyCodec
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import com.aurora.music.playback.engine.OutputDitherMode

@Composable
fun OutputRateSettings(policy: OutputRatePolicy, onChange: ((OutputRatePolicy) -> OutputRatePolicy) -> Unit) {
    val modes = listOf(appString(R.string.text_follow_source_e0e6b1), appString(R.string.text_fixed_rate_4869d0), appString(R.string.text_compatible_maximum_932602))
    val rates = OutputRatePolicyCodec.RATES.map { appString(R.string.text_khz_dd177d, (it / 1000.0)) }
    val ditherOptions = listOf(appString(R.string.text_off_e3de5a), "TPDF", appString(R.string.text_noise_shaped_c7a434))
    Column {
        SettingsSectionTitle(appString(R.string.text_sample_rate_7a0316))
        SettingsGroup {
            SettingsDropdownRow(appString(R.string.text_policy_bb9cf1), modes[policy.mode.ordinal], modes, policy.mode.ordinal) { index ->
                onChange { it.copy(mode = OutputRateMode.entries[index]) }
            }
            if (policy.mode != OutputRateMode.FOLLOW_SOURCE) {
                val fixed = policy.mode == OutputRateMode.FIXED
                val selected = if (fixed) policy.fixedRate else policy.maximumRate
                SettingsRowDivider(20.dp)
                SettingsDropdownRow(if (fixed) appString(R.string.text_rate_3a9c73) else appString(R.string.text_maximum_a8df2f),
                    appString(R.string.text_khz_dd177d, (selected / 1000.0)), rates, OutputRatePolicyCodec.RATES.indexOf(selected)) { index ->
                    onChange { current -> if (fixed) current.copy(fixedRate = OutputRatePolicyCodec.RATES[index])
                        else current.copy(maximumRate = OutputRatePolicyCodec.RATES[index]) }
                }
                if (!fixed) {
                    SettingsRowDivider(20.dp)
                    SettingsSwitchRow(title = appString(R.string.text_keep_44_1_48_khz_family_aa509c), checked = policy.preserveFamily) { value ->
                        onChange { it.copy(preserveFamily = value) }
                    }
                }
            }
            SettingsRowDivider(20.dp)
            SettingsDropdownRow(appString(R.string.text_integer_dither_bca696), ditherOptions[policy.ditherMode.ordinal], ditherOptions, policy.ditherMode.ordinal,
                subtitle = if (policy.ditherMode == OutputDitherMode.NOISE_SHAPED) appString(R.string.text_first_order_shaping_uses_tpdf_below_44_1_khz_459cb0) else null) { index ->
                onChange { it.copy(tpdfDither = index != 0, noiseShaping = index == 2) }
            }
        }
    }
}
