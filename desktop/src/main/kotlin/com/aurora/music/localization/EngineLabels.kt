package com.aurora.music.localization

import com.aurora.music.R

fun String.localizedSignalLabel(): String = when (this) {
    "Android audio" -> appString(R.string.signal_text_android_audio_87dd9e)
    "Android audio · Mix" -> appString(R.string.signal_text_android_audio_mix_f88264)
    "Direct USB transport" -> appString(R.string.signal_text_direct_usb_transport_ef9881)
    "Processed USB transport" -> appString(R.string.signal_text_processed_usb_transport_a93c89)
    "Cast receiver" -> appString(R.string.signal_text_cast_receiver_61ab9a)
    "Source" -> appString(R.string.signal_text_source_6da13a)
    "Decoder" -> appString(R.string.signal_text_decoder_7be2f0)
    "Processing" -> appString(R.string.signal_text_processing_e63451)
    "Resampling" -> appString(R.string.signal_text_resampling_1bf1aa)
    "Output" -> appString(R.string.signal_text_output_4bed33)
    "Device" -> appString(R.string.signal_text_device_a5a74a)
    "Latency" -> appString(R.string.signal_text_latency_3e3997)
    else -> this
}
