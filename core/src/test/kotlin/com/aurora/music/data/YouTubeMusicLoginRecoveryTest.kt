package com.aurora.music.data

import com.aurora.music.data.remote.YouTubeMusicClient
import com.aurora.music.data.remote.YouTubeMusicLoginRecovery
import com.aurora.music.data.remote.YouTubeMusicWebSession
import org.junit.Assert.*
import org.junit.Test

class YouTubeMusicLoginRecoveryTest {
    private val challenge = "https://accounts.google.com/v3/signin/challenge/dp"
    private val signedIn = "SID=fixture; HSID=fixture; SSID=fixture; SAPISID=fixture"

    @Test fun incompleteVerificationIsNeverReloadedOnATimer() {
        val recovery = YouTubeMusicLoginRecovery()
        assertNull(recovery.destination(challenge, "GAPS=fixture; SID=", "", 0))
        assertNull(recovery.destination(challenge, "GAPS=fixture; SID=", "", 600_000))
    }

    @Test fun stalledSignedInGooglePageResumesHandoffOnce() {
        val recovery = YouTubeMusicLoginRecovery()
        assertNull(recovery.destination(challenge, signedIn, "", 0))
        assertNull(recovery.destination(challenge, signedIn, "", 2_999))
        assertEquals(YouTubeMusicWebSession.LOGIN_URL, recovery.destination(challenge, signedIn, "", 3_000))
        assertNull(recovery.destination(challenge, signedIn, "", 60_000))
    }

    @Test fun readyMusicSessionCanLeaveStalledVerificationButNeverAutoConnects() {
        val recovery = YouTubeMusicLoginRecovery()
        val cookie = "__Secure-3PAPISID=fixture"
        assertNull(recovery.destination(challenge, "", cookie, 0))
        assertEquals(YouTubeMusicClient.ORIGIN, recovery.destination(challenge, "", cookie, 3_000))
        assertNull(recovery.destination(YouTubeMusicClient.ORIGIN, signedIn, cookie, 6_000))
    }

    @Test fun normalNavigationOrLostSessionCancelsPendingRecovery() {
        val recovery = YouTubeMusicLoginRecovery()
        assertNull(recovery.destination(challenge, signedIn, "", 0))
        assertNull(recovery.destination(YouTubeMusicClient.ORIGIN, signedIn, "", 3_000))
        assertNull(recovery.destination(challenge, signedIn, "", 4_000))
        assertNull(recovery.destination(challenge, "", "", 7_000))
        assertNull(recovery.destination(challenge, signedIn, "", 8_000))
        assertEquals(YouTubeMusicWebSession.LOGIN_URL, recovery.destination(challenge, signedIn, "", 11_000))
    }
}
