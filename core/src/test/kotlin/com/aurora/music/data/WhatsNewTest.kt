package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    private val releases = listOf("3.4.0", "3.6.0", "3.5.0")

    private fun unseen(lastSeen: String?, current: String) = WhatsNew.unseen(releases, lastSeen, current) { it }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(WhatsNew.compareVersions("3.10.0", "3.9.2") > 0)
        assertEquals(0, WhatsNew.compareVersions("3.4", "3.4.0"))
        assertEquals(0, WhatsNew.compareVersions("v3.4.0-beta1", "3.4.0"))
        assertTrue(WhatsNew.compareVersions("3.3.0", "3.4.0") < 0)
    }

    @Test
    fun shortensTrailingZeroOnly() {
        assertEquals("3.4", WhatsNew.shortVersion("3.4.0"))
        assertEquals("3.4.1", WhatsNew.shortVersion("3.4.1"))
        assertEquals("3.4", WhatsNew.shortVersion("3.4"))
    }

    @Test
    fun releasedSkipsVersionsNotInstalledYet() {
        assertEquals(listOf("3.5.0", "3.4.0"), WhatsNew.released(releases, "3.5.0") { it })
        assertEquals(emptyList<String>(), WhatsNew.released(releases, "3.3.0") { it })
    }

    @Test
    fun unseenListsSkippedUpdatesOldestFirst() {
        assertEquals(listOf("3.5.0", "3.6.0"), unseen(lastSeen = "3.4.0", current = "3.6.0"))
        assertEquals(emptyList<String>(), unseen(lastSeen = "3.6.0", current = "3.6.0"))
    }

    @Test
    fun withoutRecordOnlyTheNewestReleaseShows() {
        assertEquals(listOf("3.5.0"), unseen(lastSeen = null, current = "3.5.2"))
    }

    @Test
    fun launchDecision() {
        assertEquals(WhatsNew.Launch.SHOW, WhatsNew.onLaunch(false, true, "3.3.0", "3.4.0", hasUnseen = true))
        assertEquals(WhatsNew.Launch.NOTHING, WhatsNew.onLaunch(false, true, "3.4.0", "3.4.0", hasUnseen = false))
        // a new install sees setup, not a tour
        assertEquals(WhatsNew.Launch.MARK_SEEN, WhatsNew.onLaunch(true, true, null, "3.4.0", hasUnseen = true))
        // turned off: still recorded, so turning it back on doesn't replay this update
        assertEquals(WhatsNew.Launch.MARK_SEEN, WhatsNew.onLaunch(false, false, "3.3.0", "3.4.0", hasUnseen = true))
        // a patch with no notes just moves the marker
        assertEquals(WhatsNew.Launch.MARK_SEEN, WhatsNew.onLaunch(false, true, "3.4.0", "3.4.1", hasUnseen = false))
    }
}
