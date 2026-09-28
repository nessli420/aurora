package com.aurora.music.ui.screens.visualizer

import com.aurora.music.R
import com.aurora.music.data.VisualizerStyle
import com.aurora.music.localization.appString

fun VisualizerStyle.label(v: Int) = when (v) {
    BARS -> appString(R.string.text_spectrum_bars_6379ce); MIRROR_BARS -> appString(R.string.text_mirror_bars_b4cfbc); WAVEFORM -> appString(R.string.text_waveform_200f14)
    FILLED_WAVE -> appString(R.string.text_filled_wave_5eaa91); RADIAL_BARS -> appString(R.string.text_radial_spectrum_477a35); RADIAL_WAVE -> appString(R.string.text_radial_wave_03beca)
    PARTICLES -> appString(R.string.text_particles_07cdbc); FLUID -> appString(R.string.text_fluid_blob_088ed9); COMBO -> appString(R.string.text_combo_dcae58)
    SMOOTH_CURVE -> appString(R.string.text_spectrum_curve_f0b0b0); DOT_GRID -> appString(R.string.text_dot_matrix_d4d639); RINGS -> appString(R.string.text_pulse_rings_0a7012)
    ORB -> appString(R.string.text_orb_980143); LADDER -> appString(R.string.text_led_ladder_dc67bb); HORIZON -> appString(R.string.text_horizon_eda243); CONSTELLATION -> appString(R.string.text_constellation_785bdc)
    PEAK_DOTS -> appString(R.string.text_peak_dots_3fb145); SPECTRUM_LINE -> appString(R.string.text_neon_line_af89a0); AURORA -> appString(R.string.text_aurora_eeee9b)
    SPECTRAL_RIVER -> appString(R.string.text_spectral_river_b18902); SPECTRAL_TERRAIN -> appString(R.string.text_terrain_flyover_ba8ceb); CURL_FLOW -> appString(R.string.text_curl_flow_19ab49)
    STRANGE_ATTRACTOR -> appString(R.string.text_strange_attractor_70352b); CYMATIC -> appString(R.string.text_cymatics_459c6a); SUPERFORMULA_BLOOM -> appString(R.string.text_bloom_3ef84d)
    WORMHOLE -> appString(R.string.text_wormhole_18880e)
    PLASMA -> appString(R.string.text_liquid_chrome_af5cd1); SILK_VEIL -> appString(R.string.text_silk_veil_fa6add); NEBULA -> appString(R.string.text_nebula_drift_4aee62)
    HARMONOGRAPH -> appString(R.string.text_harmonograph_225500); INK_BLOOM -> appString(R.string.text_ink_bloom_32d8dc)
    else -> appString(R.string.text_spectrum_bars_6379ce)
}
