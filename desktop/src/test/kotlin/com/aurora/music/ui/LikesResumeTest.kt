package com.aurora.music.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LikesResumeTest {
    @Test fun focusPullsOncePerWindowOnlyWhenSignedInAndOnline() {
        val minute = 60_000_000_000L
        assertTrue(likesResumeDue(null, minute, sessionReady = true, offline = false))
        assertFalse(likesResumeDue(null, minute, sessionReady = null, offline = false))
        assertFalse(likesResumeDue(null, minute, sessionReady = false, offline = false))
        assertFalse(likesResumeDue(null, minute, sessionReady = true, offline = true))
        assertFalse(likesResumeDue(minute, 5 * minute, sessionReady = true, offline = false))
        assertTrue(likesResumeDue(minute, 6 * minute, sessionReady = true, offline = false))
    }
}
