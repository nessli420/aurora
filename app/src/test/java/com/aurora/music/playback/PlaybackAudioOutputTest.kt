package com.aurora.music.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import com.aurora.music.data.SignalFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioOutputTest {
    @Test fun pcmFormatNamesIntegerAndFloatPcm() {
        assertEquals(SignalFormat(rateHz = 44_100, bitDepth = 16, channels = 2, encoding = "integer PCM"),
            PlaybackAudioOutput.pcmFormat(44_100, C.ENCODING_PCM_16BIT, 2))
        assertEquals(SignalFormat(rateHz = 96_000, bitDepth = 32, channels = 2, encoding = "float PCM"),
            PlaybackAudioOutput.pcmFormat(96_000, C.ENCODING_PCM_FLOAT, 2))
        assertEquals(SignalFormat(rateHz = null, bitDepth = 24, channels = null, encoding = "integer PCM"),
            PlaybackAudioOutput.pcmFormat(0, C.ENCODING_PCM_24BIT, 0))
        assertEquals(SignalFormat(rateHz = 48_000, bitDepth = null, channels = 2, encoding = null),
            PlaybackAudioOutput.pcmFormat(48_000, C.ENCODING_AC3, 2))
    }

    @Test fun routeCategoryKeepsTheExistingLabels() {
        assertEquals("Bluetooth output (codec unknown)", PlaybackAudioOutput.routeCategory(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals("Bluetooth output (codec unknown)", PlaybackAudioOutput.routeCategory(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("wired output", PlaybackAudioOutput.routeCategory(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals("speaker", PlaybackAudioOutput.routeCategory(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("USB audio device", PlaybackAudioOutput.routeCategory(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals("selected Android output", PlaybackAudioOutput.routeCategory(AudioDeviceInfo.TYPE_HDMI))
    }

    @Test fun onlyUsbDeviceTypesCountAsUsb() {
        assertTrue(PlaybackAudioOutput.isUsb(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertTrue(PlaybackAudioOutput.isUsb(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertTrue(PlaybackAudioOutput.isUsb(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertFalse(PlaybackAudioOutput.isUsb(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(PlaybackAudioOutput.isUsb(AudioDeviceInfo.TYPE_WIRED_HEADSET))
    }
}
