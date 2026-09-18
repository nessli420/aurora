package com.aurora.music.ui.screens.settings

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
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Fitting settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningChoiceRow("Channel intent", TuningChannelMode.entries.map { it.label() }, channelMode.ordinal) {
                if (!busy) { channelMode = TuningChannelMode.entries[it]; error = null }
            }
            Text(when (channelMode) {
                TuningChannelMode.LEFT -> "Left-channel correction."
                TuningChannelMode.RIGHT -> "Right-channel correction."
                TuningChannelMode.INDEPENDENT -> "Separate correction per channel. Shares the band budget."
                TuningChannelMode.LINKED_AVERAGE -> "Averages both measurements for a shared correction."
            }, style = MaterialTheme.typography.bodySmall)
            TuningChoiceRow("Level normalization", listOf("None", "Match mean 200–2000 Hz"), normalization.ordinal) {
                if (!busy) { normalization = TuningNormalization.entries[it]; error = null }
            }
            TuningChoiceRow("Controls", listOf("Fit limits", "Target shape", "Treble", "Channel limits", "Trim and delay"), section) { section = it }
            val fields = when (section) {
                1 -> listOf(Triple("bass", "Bass · dB", ""), Triple("bassHz", "Bass corner · Hz", ""),
                    Triple("tilt", "Tilt · dB/octave", ""), Triple("ear", "Ear gain · dB", ""), Triple("earHz", "Ear gain center · Hz", ""))
                2 -> {
                    SettingsSwitchRow(title = "Limit treble correction", subtitle = "Limits transition over one octave.", checked = treble, onCheckedChange = { treble = it })
                    if (treble) listOf(Triple("trebleHz", "Treble start · Hz", ""), Triple("trebleBoost", "Maximum treble boost · dB", ""),
                        Triple("trebleCut", "Maximum treble cut · dB", ""), Triple("trebleQ", "Maximum treble Q", "")) else emptyList()
                }
                3 -> {
                    SettingsSwitchRow(title = "Linked fit limits", subtitle = "Separate limits require independent channels.", checked = linkedLimits, onCheckedChange = { linkedLimits = it })
                    if (!linkedLimits) listOf("l" to "Left", "r" to "Right").flatMap { (prefix, label) -> listOf(
                        Triple("${prefix}Boost", "$label maximum boost · dB", ""), Triple("${prefix}Cut", "$label maximum cut · dB", ""),
                        Triple("${prefix}MinQ", "$label minimum Q", ""), Triple("${prefix}MaxQ", "$label maximum Q", "")) } else emptyList()
                }
                4 -> {
                    Text("Manual alignment. No automatic phase correction.", style = MaterialTheme.typography.bodySmall)
                    listOf(Triple("leftTrim", "Left trim - dB", ""), Triple("rightTrim", "Right trim - dB", ""),
                        Triple("leftDelay", "Left delay - ms", ""), Triple("rightDelay", "Right delay - ms", ""))
                }
                else -> listOf(
                Triple("rate", "Design sample rate · Hz", "8,000–768,000 Hz"),
                Triple("bands", "Total parametric band budget", "1–128 total; up to 64 per channel"),
                Triple("low", "Minimum fitting frequency · Hz", "10–24,000 Hz; below the maximum"),
                Triple("high", "Maximum fitting frequency · Hz", "10–24,000 Hz; limited by shared data and Nyquist"),
                Triple("boost", "Maximum boost · dB", "0–24 dB"),
                Triple("cut", "Maximum cut · dB", "0–30 dB; enter a positive value"),
                Triple("minQ", "Minimum Q", "0.1–100; no greater than maximum Q"),
                Triple("maxQ", "Maximum Q", "0.1–100"),
                Triple("smooth", "Smoothing width · octaves", "0–1; 0 is off, 0.1666667 is about 1/6 octave"),
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
                        }) { Icon(Icons.Filled.Exposure, "Change sign") }
                    })
            }
            if (section != 4) Text("Magnitude correction only. Phase is not fitted.", style = MaterialTheme.typography.bodySmall)
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
                ?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Enter a finite number for every setting.")
            fun integer(key: String): Int = submitted.getValue(key).toIntOrNull() ?: throw IllegalArgumentException("Use whole numbers for sample rate and band budget.")
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
          catch (failure: Exception) { error = failure.message ?: "Check the fitting limits." }
          finally { busy = false }
        }
    }) { Text(if (busy) "Validating…" else "Use settings") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
internal fun TuningChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.getOrElse(selected) { options.first() }, Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, "Choose $title")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, name -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(index) }) }
            }
        }
    }
}

internal fun TuningChannelMode.label(): String = when (this) {
    TuningChannelMode.LEFT -> "Left only"; TuningChannelMode.RIGHT -> "Right only"
    TuningChannelMode.INDEPENDENT -> "Independent left / right"; TuningChannelMode.LINKED_AVERAGE -> "Linked average of left / right"
}
