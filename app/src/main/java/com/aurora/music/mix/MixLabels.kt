package com.aurora.music.mix

import com.aurora.music.R
import com.aurora.music.localization.appString

val FadeCurve.label: String get() = appString(when (this) {
    FadeCurve.SMOOTH -> R.string.text_smooth_7f93ca
    FadeCurve.LINEAR -> R.string.text_linear_af502f
    FadeCurve.POWER -> R.string.text_equal_power_70e301
    FadeCurve.CUT -> R.string.text_cut_38d13b
})

val StemMode.label: String get() = appString(when (this) {
    StemMode.FULL -> R.string.text_original_c0a806
    StemMode.VOCALS -> R.string.text_vocals_only_337845
    StemMode.BACKING -> R.string.text_backing_only_3b542d
})
