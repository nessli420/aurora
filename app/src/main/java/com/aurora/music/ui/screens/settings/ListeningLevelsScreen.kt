package com.aurora.music.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.listening.*
import com.aurora.music.data.routes.OutputDeviceCategory
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.data.routes.RouteObservation
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ListeningLevelsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.listeningLevels
    val state by store.state.collectAsStateWithLifecycle()
    val estimate by store.estimate.collectAsStateWithLifecycle()
    val volumeStep by store.volumeStep.collectAsStateWithLifecycle()
    val volumeMaximum by store.volumeMaximum.collectAsStateWithLifecycle()
    val route by container.settingsStore.processingRoutes.observations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<ListeningProfile?, RouteObservation>?>(null) }
    var confirming by remember { mutableStateOf<Pair<ListeningProfile, RouteObservation>?>(null) }
    var clearHistory by remember { mutableStateOf(false) }
    val bindable = route.route.key != null && route.route.kind == ProcessingRouteKind.ANDROID &&
        route.route.category in setOf(OutputDeviceCategory.HEADPHONES, OutputDeviceCategory.USB, OutputDeviceCategory.BLUETOOTH)
    fun perform(action: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        scope.launch {
            try { action().getOrThrow() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: "Could not save listening settings.") }
            finally { busy = false }
        }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar("Listening levels", onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Estimated sound level", style = MaterialTheme.typography.titleMedium)
                            when (val measured = estimate) {
                                is ListeningEstimate.Unavailable -> Text(measured.reason, style = MaterialTheme.typography.bodyMedium)
                                is ListeningEstimate.Available -> {
                                    Text(measured.maximumDb?.let { "${levelText(it)} dB SPL" } ?: "Silent",
                                        style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                    Text("L ${levelText(measured.leftDb)} · R ${levelText(measured.rightDb)} · ±${levelText(measured.uncertaintyDb)} dB",
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(measured.profileName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text("Electrical estimate from headphone sensitivity. Not an acoustic measurement.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Text(route.route.label, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleSmall)
                    volumeStep?.let { Text("Android volume step: $it / ${volumeMaximum ?: "Unknown"}", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
                    if (!bindable) Text("Play through an identifiable headphone output to bind a calibration.",
                        Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { editing = null to route }, enabled = bindable && !busy,
                        modifier = Modifier.padding(horizontal = 20.dp)) { Text("Add calibration") }
                }
                state.error?.let { item { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) } }
                items(state.profiles, key = { it.id }) { profile ->
                    SettingsGroup {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            Text(profile.routeLabel.ifBlank { "Output not bound" }, style = MaterialTheme.typography.bodySmall)
                            Text("${profile.gainSetting} · ±${levelText(profile.uncertaintyDb)} dB", style = MaterialTheme.typography.bodySmall)
                            Text(profile.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = !busy && bindable && profile.routeKey == route.route.key,
                                    onClick = { confirming = profile to route }) { Text("Use") }
                                TextButton(enabled = !busy && bindable, onClick = { editing = profile to route }) { Text("Edit / bind") }
                                TextButton(enabled = !busy, onClick = { perform { store.removeProfile(profile.id) } }) { Text("Remove") }
                            }
                        }
                    }
                }
                if (estimate is ListeningEstimate.Available) item {
                    OutlinedButton(onClick = store::invalidateConfirmation, modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text("Hardware settings changed")
                    }
                }
                item { SettingsSectionTitle("Local history") }
                item {
                    SettingsGroup {
                        SettingsSwitchRow(title = "Save level samples", subtitle = "Every 30 seconds. Up to 2,880 samples stored locally.",
                            checked = state.historyEnabled, onCheckedChange = { enabled -> perform { store.setHistoryEnabled(enabled) } })
                        if (state.history.isNotEmpty()) TextButton(onClick = { clearHistory = true }, enabled = !busy,
                            modifier = Modifier.padding(horizontal = 12.dp)) { Text("Clear history") }
                    }
                }
                items(state.history.take(60)) { entry ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.profileName, style = MaterialTheme.typography.bodyMedium)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestampMillis)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${levelText(listOfNotNull(entry.leftDb, entry.rightDb).maxOrNull())} ±${levelText(entry.uncertaintyDb)} dB",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.history.size > 60) item {
                    Text("Showing the latest 60 samples.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
    }
    editing?.let { (profile, expected) ->
        CalibrationEditor(profile, expected, volumeMaximum, onDismiss = { editing = null }, onSave = { saved ->
            editing = null
            perform { store.saveProfile(saved, expected) }
        })
    }
    confirming?.let { (profile, expected) ->
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text("Confirm hardware") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Headphones: ${profile.name}")
                Text("Gain: ${profile.gainSetting} (${levelText(profile.gainDb)} dB)")
                Text("Hardware attenuation: ${levelText(profile.hardwareAttenuationDb)} dB")
                Text("Confirm again after reconnecting or changing hardware settings.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = {
            confirming = null
            perform { store.confirm(profile.id, expected) }
        }) { Text("These settings match") } }, dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } })
    }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("Clear listening history?") },
        confirmButton = { TextButton(onClick = { clearHistory = false; perform { store.clearHistory() } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("Cancel") } })
}

@Composable
private fun CalibrationEditor(profile: ListeningProfile?, expected: RouteObservation, currentVolumeMaximum: Int?, onDismiss: () -> Unit, onSave: (ListeningProfile) -> Unit) {
    val volumeMaximum = remember { currentVolumeMaximum }
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var sensitivity by remember { mutableStateOf(profile?.sensitivityDb?.toString().orEmpty()) }
    var unit by remember { mutableStateOf(profile?.sensitivityUnit ?: SensitivityUnit.DB_PER_VOLT) }
    var impedance by remember { mutableStateOf(profile?.impedanceOhms?.toString().orEmpty()) }
    var voltage by remember { mutableStateOf(profile?.fullScaleVrms?.toString().orEmpty()) }
    var gainName by remember { mutableStateOf(profile?.gainSetting.orEmpty()) }
    var gain by remember { mutableStateOf(profile?.gainDb?.toString().orEmpty()) }
    var hardware by remember { mutableStateOf(profile?.hardwareAttenuationDb?.toString().orEmpty()) }
    var curve by remember { mutableStateOf(profile?.takeIf { it.volumeMaximum == volumeMaximum }?.volumeCurve
        ?.joinToString("\n") { "${it.index}=${it.attenuationDb}" }.orEmpty()) }
    var provenance by remember { mutableStateOf(profile?.provenance.orEmpty()) }
    var uncertainty by remember { mutableStateOf(profile?.uncertaintyDb?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                SettingsTopBar("Headphone calibration", onDismiss)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(expected.route.label, style = MaterialTheme.typography.titleMedium) }
                    item { CalibrationField(name, { name = it.take(80) }, "Headphone name") }
                    item { CalibrationField(sensitivity, { sensitivity = it }, "Sensitivity", numeric = true) }
                    item { SegmentedRow("Sensitivity unit", SensitivityUnit.entries.map { it.label }, unit.ordinal) { unit = SensitivityUnit.entries[it] } }
                    item { CalibrationField(impedance, { impedance = it }, "Impedance (ohms)", numeric = true) }
                    item { CalibrationField(voltage, { voltage = it }, "DAC output (V RMS, full-scale sine)", numeric = true) }
                    item { CalibrationField(gainName, { gainName = it.take(80) }, "DAC gain setting") }
                    item { CalibrationField(gain, { gain = it }, "Additional gain (dB)",
                        helper = "Use 0 if the DAC voltage already includes this gain.") }
                    item { CalibrationField(hardware, { hardware = it }, "Hardware attenuation (dB)",
                        helper = "Measured attenuation after the DAC gain. Use 0 for a verified fixed output.") }
                    item { CalibrationField(curve, { curve = it.take(6000) }, "Measured Android volume points", singleLine = false,
                        helper = "One index=dB pair per line (0–${volumeMaximum ?: "Unknown"}), relative to full output. Unlisted steps stay unavailable.") }
                    item { CalibrationField(provenance, { provenance = it.take(600) }, "Calibration source", singleLine = false,
                        helper = "Record the sensitivity source and voltage measurements, including the load.") }
                    item { CalibrationField(uncertainty, { uncertainty = it }, "Estimated uncertainty (±dB)", numeric = true) }
                    item { Text("Sensitivity is usually specified at 1 kHz. Fit, frequency response and load affect the estimate.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    item {
                        Button(onClick = {
                            runCatching {
                                fun number(value: String, label: String) = value.trim().toDoubleOrNull() ?: error("Enter $label.")
                                ListeningMath.validate(ListeningProfile(profile?.id ?: UUID.randomUUID().toString(), name,
                                    expected.route.key, expected.route.label, number(sensitivity, "sensitivity"), unit,
                                    number(impedance, "impedance"), number(voltage, "DAC voltage"), gainName, number(gain, "gain"),
                                    number(hardware, "hardware attenuation"), ListeningMath.parseVolumeCurve(curve), provenance,
                                    number(uncertainty, "uncertainty"), requireNotNull(volumeMaximum) { "The Android volume range is unavailable." }))
                            }.onSuccess(onSave).onFailure { error = it.message ?: "Check the calibration values." }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save calibration") }
                    }
                    item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
                }
            }
        }
    }
}

@Composable
private fun CalibrationField(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean = false,
    singleLine: Boolean = true, helper: String? = null) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        supportingText = helper?.let { { Text(it) } })
}

private fun levelText(level: Double?): String = level?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "Silent"
