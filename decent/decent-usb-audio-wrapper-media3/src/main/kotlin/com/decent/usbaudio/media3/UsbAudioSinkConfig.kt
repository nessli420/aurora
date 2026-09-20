package com.decent.usbaudio.media3

/** usb transport policy; android fallback is opt-in. */
data class UsbAudioSinkConfig(
    val bitPerfectEnabled: Boolean = true,
    @Deprecated("The Android delegate stays idle during USB playback.")
    val forceRouteToSpeaker: Boolean = true,
    val allowAndroidFallback: Boolean = false,
    val nativeFlacEnabled: Boolean = true,
    val onUsbFailure: (String) -> Unit = {},
)
