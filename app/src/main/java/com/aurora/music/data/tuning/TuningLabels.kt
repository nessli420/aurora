package com.aurora.music.data.tuning

import com.aurora.music.R
import com.aurora.music.localization.appString

val TuningCurveFormat.label: String get() = appString(when (this) {
    TuningCurveFormat.TEXT -> R.string.text_hz_db_text_rew_d34904
    TuningCurveFormat.SQUIG -> R.string.text_squig_measurement_text_1ab632
    TuningCurveFormat.AUTOEQ_RAW -> R.string.text_autoeq_json_raw_measurement_984669
    TuningCurveFormat.AUTOEQ_TARGET -> R.string.text_autoeq_json_target_de676c
    TuningCurveFormat.AUTOEQ_CSV -> R.string.text_autoeq_target_csv_231f90
    TuningCurveFormat.WAVELET -> R.string.text_wavelet_graphiceq_correction_230cc2
})
