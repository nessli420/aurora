package com.aurora.music.localization

import com.aurora.music.R
import com.aurora.music.data.rules.*
import com.aurora.music.data.routes.*

val RuleSource.localizedLabel: String get() = appString(when (this) {
    RuleSource.LOCAL_FILE -> R.string.text_local_file_576d5a
    RuleSource.DOWNLOAD -> R.string.text_download_a479c9
    RuleSource.STREAM -> R.string.text_stream_df0638
    RuleSource.RADIO -> R.string.text_radio_b11bf1
    RuleSource.PODCAST -> R.string.text_podcast_bafb6e
})

val RulePlaybackMode.localizedLabel: String get() = appString(when (this) {
    RulePlaybackMode.LOCAL -> R.string.text_local_playback_f3fe4f
    RulePlaybackMode.ANDROID_AUTO -> R.string.text_android_auto_beec0a
    RulePlaybackMode.CAST -> R.string.text_cast_60745a
})

val RuleField.localizedLabel: String get() = appString(when (this) {
    RuleField.ROUTE -> R.string.text_output_4bed33
    RuleField.HEADPHONES -> R.string.text_headphones_453cce
    RuleField.SOURCE -> R.string.text_source_6da13a
    RuleField.PROVIDER -> R.string.text_provider_7ceee3
    RuleField.ALBUM_ID -> R.string.text_album_id_f7b6dc
    RuleField.ALBUM_NAME -> R.string.text_album_dfb4c9
    RuleField.GENRE -> R.string.text_genre_2aa31f
    RuleField.PLAYLIST_ID -> R.string.text_playlist_id_3e9a53
    RuleField.PLAYLIST_NAME -> R.string.text_playlist_cd95b4
    RuleField.SAMPLE_RATE -> R.string.text_sample_rate_7a0316
    RuleField.CODEC -> R.string.text_codec_a33676
    RuleField.CONTAINER -> R.string.text_container_e6443a
    RuleField.CONTEXT -> R.string.text_playback_context_35175d
})

val OutputDeviceCategory.localizedLabel: String get() = appString(when (this) {
    OutputDeviceCategory.SPEAKER -> R.string.text_speaker_7c23b0
    OutputDeviceCategory.EARPIECE -> R.string.text_earpiece_90fd41
    OutputDeviceCategory.HEADPHONES -> R.string.text_wired_headphones_56e99c
    OutputDeviceCategory.BLUETOOTH -> R.string.text_bluetooth_output_2215ca
    OutputDeviceCategory.USB -> R.string.text_usb_audio_c9b51c
    OutputDeviceCategory.HDMI -> R.string.text_hdmi_output_8346ec
    OutputDeviceCategory.OTHER -> R.string.text_android_output_0a3516
})


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
