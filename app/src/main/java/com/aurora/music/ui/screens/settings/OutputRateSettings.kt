package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.OutputRatePolicyCodec
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy

@Composable
fun OutputRateSettings(policy: OutputRatePolicy, onChange: (OutputRatePolicy) -> Unit) {
    SettingsGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sample rate", style = MaterialTheme.typography.titleMedium)
            OutputRateMenu("Policy", when (policy.mode) {
                OutputRateMode.FOLLOW_SOURCE -> "Follow source"
                OutputRateMode.FIXED -> "Fixed rate"
                OutputRateMode.COMPATIBLE_MAXIMUM -> "Compatible maximum"
            }, listOf("Follow source", "Fixed rate", "Compatible maximum")) { index ->
                onChange(policy.copy(mode = OutputRateMode.entries[index]))
            }
            if (policy.mode != OutputRateMode.FOLLOW_SOURCE) {
                val fixed = policy.mode == OutputRateMode.FIXED
                val selected = if (fixed) policy.fixedRate else policy.maximumRate
                OutputRateMenu(if (fixed) "Rate" else "Maximum", "${selected / 1000.0} kHz",
                    OutputRatePolicyCodec.RATES.map { "${it / 1000.0} kHz" }) { index ->
                    onChange(if (fixed) policy.copy(fixedRate = OutputRatePolicyCodec.RATES[index])
                        else policy.copy(maximumRate = OutputRatePolicyCodec.RATES[index]))
                }
                if (!fixed) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep 44.1/48 kHz family", Modifier.weight(1f))
                    Switch(policy.preserveFamily, { onChange(policy.copy(preserveFamily = it)) })
                }
                Text("Precision Android and processed USB. Applies when output reopens.", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("TPDF dither for integer output", Modifier.weight(1f))
                Switch(policy.tpdfDither, { onChange(policy.copy(tpdfDither = it)) })
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
