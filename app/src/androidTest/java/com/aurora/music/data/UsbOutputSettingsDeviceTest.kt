package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsbOutputSettingsDeviceTest {
    private val store get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        as AuroraApplication).container.settingsStore

    @Test fun usbPoliciesPersistInSnapshotsAndMalformedBackupLeavesPreferencesIntact() = runBlocking {
        val original = store.exportPrefs()
        try {
            assertFalse(PlaybackPrefs().usbDsdExperimental == true)
            store.setUsbDsdMode(UsbDsdMode.NATIVE)
            store.setUsbDsdExperimental(true)
            store.setUsbOutputMode(UsbOutputMode.PROCESSED)
            store.setUsbFallbackPolicy(UsbFallbackPolicy.ANDROID)
            val saved = store.exportPrefs()
            val playback = store.playbackPrefs.first()
            assertEquals(true, playback.usbDsdExperimental)
            assertEquals(UsbOutputMode.PROCESSED, playback.usbOutputMode)
            assertEquals(UsbFallbackPolicy.ANDROID, playback.usbFallbackPolicy)
            assertEquals(UsbOutputMode.PROCESSED, ProcessingPlaybackPrefs.from(playback).usbOutputMode)
            assertTrue(store.restoreBackupPrefs(saved.copy(strings = saved.strings +
                (UsbOutputPolicy.FALLBACK_KEY to "unknown"))).isFailure)
            assertEquals(saved, store.exportPrefs())
            store.setUsbOutputMode(UsbOutputMode.DIRECT)
            store.setUsbFallbackPolicy(UsbFallbackPolicy.PAUSE)
            store.restoreBackupPrefs(saved).getOrThrow()
            assertEquals(playback, store.playbackPrefs.first())
            val legacy = saved.copy(strings = saved.strings - UsbOutputPolicy.MODE_KEY - UsbOutputPolicy.FALLBACK_KEY,
                booleans = saved.booleans - "usb_dsd_experimental")
            store.restoreBackupPrefs(legacy).getOrThrow()
            assertEquals(UsbOutputMode.DIRECT, store.playbackPrefs.first().usbOutputMode)
            assertEquals(UsbFallbackPolicy.PAUSE, store.playbackPrefs.first().usbFallbackPolicy)
            assertEquals(false, store.playbackPrefs.first().usbDsdExperimental)
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }
}
