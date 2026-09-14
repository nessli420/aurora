package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
        "minQ" to initial.minQ.toString(), "maxQ" to initial.maxQ.toString(), "smooth" to initial.smoothingOctaves.toString())) }
    var channelMode by remember { mutableStateOf(initial.channelMode) }
    var normalization by remember { mutableStateOf(initial.normalization) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Fitting settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningChoiceRow("Channel intent", TuningChannelMode.entries.map { it.label() }, channelMode.ordinal) {
                if (!busy) { channelMode = TuningChannelMode.entries[it]; error = null }
            }
            Text(when (channelMode) {
                TuningChannelMode.LEFT -> "Fit only the left measurement and route its EQ to the left channel."
                TuningChannelMode.RIGHT -> "Fit only the right measurement and route its EQ to the right channel."
                TuningChannelMode.INDEPENDENT -> "Fit each imported channel separately, sharing the total band budget. Supply one or both measurements."
                TuningChannelMode.LINKED_AVERAGE -> "Explicitly average left and right magnitudes for one EQ applied to both channels. Raw imports stay separate."
            }, style = MaterialTheme.typography.bodySmall)
            TuningChoiceRow("Level normalization", listOf("None", "Match mean 200–2000 Hz"), normalization.ordinal) {
                if (!busy) { normalization = TuningNormalization.entries[it]; error = null }
            }
            Text("Normalization adjusts fitting copies. Imported magnitude and phase samples remain unchanged.", style = MaterialTheme.typography.bodySmall)
            listOf(
                Triple("rate", "Design sample rate · Hz", "8,000–768,000 Hz"),
                Triple("bands", "Total parametric band budget", "1–64 across all fitted channels"),
                Triple("low", "Minimum fitting frequency · Hz", "10–24,000 Hz; below the maximum"),
                Triple("high", "Maximum fitting frequency · Hz", "10–24,000 Hz; limited by shared data and Nyquist"),
                Triple("boost", "Maximum boost · dB", "0–24 dB"),
                Triple("cut", "Maximum cut · dB", "0–30 dB, entered as a positive limit"),
                Triple("minQ", "Minimum Q", "0.1–100; no greater than maximum Q"),
                Triple("maxQ", "Maximum Q", "0.1–100"),
                Triple("smooth", "Smoothing width · octaves", "0–1; 0 is off, 0.1666667 is about 1/6 octave"),
            ).forEach { (key, label, help) ->
                OutlinedTextField(values.getValue(key), { values = values + (key to it); error = null },
                    label = { Text(label) }, supportingText = { Text(help) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            Text("This fitter generates peaking filters for magnitude correction. Phase is not fitted.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        val submitted = values; val mode = channelMode; val norm = normalization
        busy = true
        scope.launch {
          try {
           val next = withContext(Dispatchers.Default) {
            fun number(key: String): Double = submitted.getValue(key).replace(',', '.').toDoubleOrNull()
                ?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Enter a finite number for every setting.")
            fun integer(key: String): Int = submitted.getValue(key).toIntOrNull() ?: throw IllegalArgumentException("Sample rate and band budget must be whole numbers.")
            val next = TuningFitConfig(integer("rate"), integer("bands"), number("low"), number("high"),
                number("boost"), number("cut"), number("minQ"), number("maxQ"), mode, norm, number("smooth"))
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
