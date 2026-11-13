package com.aurora.music.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAllowedTest {
    @Test fun wifiAlwaysAllowed() {
        assertTrue(downloadAllowed(offline = false, networkUp = true, onWifi = true, mobileData = false))
        assertTrue(downloadAllowed(offline = false, networkUp = true, onWifi = true, mobileData = true))
    }

    @Test fun mobileDataOnlyWhenAllowed() {
        assertFalse(downloadAllowed(offline = false, networkUp = true, onWifi = false, mobileData = false))
        assertTrue(downloadAllowed(offline = false, networkUp = true, onWifi = false, mobileData = true))
    }

    @Test fun offlineOrNoNetworkNeverDownloads() {
        assertFalse(downloadAllowed(offline = true, networkUp = true, onWifi = true, mobileData = true))
        assertFalse(downloadAllowed(offline = false, networkUp = false, onWifi = false, mobileData = true))
    }
}
