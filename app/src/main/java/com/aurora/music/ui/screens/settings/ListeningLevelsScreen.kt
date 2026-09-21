package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
            catch (failure: Exception) { snackbar.showSnackbar(failure.message ?: appString(R.string.text_could_not_save_listening_settings_22937b)) }
            finally { busy = false }
        }
    }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(appString(R.string.text_listening_levels_c1db13), onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SettingsGroup {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(appString(R.string.text_estimated_sound_level_3d1a1a), style = MaterialTheme.typography.titleMedium)
                            when (val measured = estimate) {
                                is ListeningEstimate.Unavailable -> Text(measured.reason, style = MaterialTheme.typography.bodyMedium)
                                is ListeningEstimate.Available -> {
                                    Text(measured.maximumDb?.let { "${levelText(it)} dB SPL" } ?: appString(R.string.text_silent_aa9330),
                                        style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                    Text(appString(R.string.text_l_r_db_31102b, (levelText(measured.leftDb)), (levelText(measured.rightDb)), (levelText(measured.uncertaintyDb))),
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(measured.profileName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(appString(R.string.text_electrical_estimate_from_headphone_sensitivity_not_an_acoustic_me_1f4d0c),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Text(route.route.label, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleSmall)
                    volumeStep?.let { Text(appString(R.string.text_android_volume_step_b09bf1, (it), (volumeMaximum ?: appString(R.string.text_unknown_bc7819))), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
                    if (!bindable) Text(appString(R.string.text_play_through_an_identifiable_headphone_output_to_bind_a_calibrati_2c66fd),
                        Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { editing = null to route }, enabled = bindable && !busy,
                        modifier = Modifier.padding(horizontal = 20.dp)) { Text(appString(R.string.text_add_calibration_d9508f)) }
                }
                state.error?.let { item { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) } }
                items(state.profiles, key = { it.id }) { profile ->
                    SettingsGroup {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            Text(profile.routeLabel.ifBlank { appString(R.string.text_output_not_bound_dbaf18) }, style = MaterialTheme.typography.bodySmall)
                            Text(appString(R.string.text_db_136a33, (profile.gainSetting), (levelText(profile.uncertaintyDb))), style = MaterialTheme.typography.bodySmall)
                            Text(profile.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = !busy && bindable && profile.routeKey == route.route.key,
                                    onClick = { confirming = profile to route }) { Text(appString(R.string.text_use_1d4d43)) }
                                TextButton(enabled = !busy && bindable, onClick = { editing = profile to route }) { Text(appString(R.string.text_edit_bind_e977d1)) }
                                TextButton(enabled = !busy, onClick = { perform { store.removeProfile(profile.id) } }) { Text(appString(R.string.text_remove_e96390)) }
                            }
                        }
                    }
                }
                if (estimate is ListeningEstimate.Available) item {
                    OutlinedButton(onClick = store::invalidateConfirmation, modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(appString(R.string.text_hardware_settings_changed_442104))
                    }
                }
                item { SettingsSectionTitle(appString(R.string.text_local_history_8d1ed5)) }
                item {
                    SettingsGroup {
                        SettingsSwitchRow(title = appString(R.string.text_save_level_samples_c79a47), subtitle = appString(R.string.text_every_30_seconds_up_to_2_880_samples_stored_locally_39373d),
                            checked = state.historyEnabled, onCheckedChange = { enabled -> perform { store.setHistoryEnabled(enabled) } })
                        if (state.history.isNotEmpty()) TextButton(onClick = { clearHistory = true }, enabled = !busy,
                            modifier = Modifier.padding(horizontal = 12.dp)) { Text(appString(R.string.text_clear_history_53b515)) }
                    }
                }
                items(state.history.take(60)) { entry ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.profileName, style = MaterialTheme.typography.bodyMedium)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestampMillis)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(appString(R.string.text_db_8bb555, (levelText(listOfNotNull(entry.leftDb, entry.rightDb).maxOrNull())), (levelText(entry.uncertaintyDb))),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.history.size > 60) item {
                    Text(appString(R.string.text_showing_the_latest_60_samples_596144), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
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
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text(appString(R.string.text_confirm_hardware_d8779b)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(appString(R.string.text_headphones_ecc393, (profile.name)))
                Text(appString(R.string.text_gain_db_cad396, (profile.gainSetting), (levelText(profile.gainDb))))
                Text(appString(R.string.text_hardware_attenuation_db_f75679, (levelText(profile.hardwareAttenuationDb))))
                Text(appString(R.string.text_confirm_again_after_reconnecting_or_changing_hardware_settings_cac3c5), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = {
            confirming = null
            perform { store.confirm(profile.id, expected) }
        }) { Text(appString(R.string.text_these_settings_match_4e8e86)) } }, dismissButton = { TextButton(onClick = { confirming = null }) { Text(appString(R.string.text_cancel_77dfd2)) } })
    }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text(appString(R.string.text_clear_listening_history_5076df)) },
        confirmButton = { TextButton(onClick = { clearHistory = false; perform { store.clearHistory() } }) { Text(appString(R.string.text_clear_719ea3)) } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text(appString(R.string.text_cancel_77dfd2)) } })
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
                SettingsTopBar(appString(R.string.text_headphone_calibration_6fc5b1), onDismiss)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(expected.route.label, style = MaterialTheme.typography.titleMedium) }
                    item { CalibrationField(name, { name = it.take(80) }, appString(R.string.text_headphone_name_3e45cb)) }
                    item { CalibrationField(sensitivity, { sensitivity = it }, appString(R.string.text_sensitivity_031cfc), numeric = true) }
                    item { SegmentedRow(appString(R.string.text_sensitivity_unit_e9d12c), SensitivityUnit.entries.map { it.label }, unit.ordinal) { unit = SensitivityUnit.entries[it] } }
                    item { CalibrationField(impedance, { impedance = it }, appString(R.string.text_impedance_ohms_3727d3), numeric = true) }
                    item { CalibrationField(voltage, { voltage = it }, appString(R.string.text_dac_output_v_rms_full_scale_sine_28c8da), numeric = true) }
                    item { CalibrationField(gainName, { gainName = it.take(80) }, appString(R.string.text_dac_gain_setting_4940ab)) }
                    item { CalibrationField(gain, { gain = it }, appString(R.string.text_additional_gain_db_b50044),
                        helper = appString(R.string.text_use_0_if_the_dac_voltage_already_includes_this_gain_8fe45f)) }
                    item { CalibrationField(hardware, { hardware = it }, appString(R.string.text_hardware_attenuation_db_a52110),
                        helper = appString(R.string.text_measured_attenuation_after_the_dac_gain_use_0_for_a_verified_fixe_5ff94a)) }
                    item { CalibrationField(curve, { curve = it.take(6000) }, appString(R.string.text_measured_android_volume_points_e77c92), singleLine = false,
                        helper = appString(R.string.text_one_index_db_pair_per_line_0_relative_to_full_output_unlisted_ste_64e7c8, (volumeMaximum ?: appString(R.string.text_unknown_bc7819)))) }
                    item { CalibrationField(provenance, { provenance = it.take(600) }, appString(R.string.text_calibration_source_50c2e0), singleLine = false,
                        helper = appString(R.string.text_record_the_sensitivity_source_and_voltage_measurements_including_71bc84)) }
                    item { CalibrationField(uncertainty, { uncertainty = it }, appString(R.string.text_estimated_uncertainty_db_99228f), numeric = true) }
                    item { Text(appString(R.string.text_sensitivity_is_usually_specified_at_1_khz_fit_frequency_response_d5a30d),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    item {
                        Button(onClick = {
                            runCatching {
                                fun number(value: String, label: String) = value.trim().toDoubleOrNull() ?: error(appString(R.string.text_enter_3ffccb, (label)))
                                ListeningMath.validate(ListeningProfile(profile?.id ?: UUID.randomUUID().toString(), name,
                                    expected.route.key, expected.route.label, number(sensitivity, "sensitivity"), unit,
                                    number(impedance, "impedance"), number(voltage, appString(R.string.text_dac_voltage_e5b76c)), gainName, number(gain, "gain"),
                                    number(hardware, appString(R.string.text_hardware_attenuation_bd288e)), ListeningMath.parseVolumeCurve(curve), provenance,
                                    number(uncertainty, "uncertainty"), requireNotNull(volumeMaximum) { appString(R.string.text_the_android_volume_range_is_unavailable_577138) }))
                            }.onSuccess(onSave).onFailure { error = it.message ?: appString(R.string.text_check_the_calibration_values_c16359) }
                        }, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.text_save_calibration_a1068b)) }
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

private fun levelText(level: Double?): String = level?.let { String.format(Locale.ROOT, "%.1f", it) } ?: appString(R.string.text_silent_aa9330)
