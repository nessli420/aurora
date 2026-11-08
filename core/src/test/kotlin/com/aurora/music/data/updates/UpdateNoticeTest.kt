package com.aurora.music.data.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNoticeTest {
    @Test fun aNewReleaseIsAnnouncedOnce() {
        assertTrue(UpdateNotice.shouldAnnounce(true, "V3.3.1", null))
        assertFalse(UpdateNotice.shouldAnnounce(true, "V3.3.1", "V3.3.1"))
    }

    @Test fun aLaterReleaseGetsItsOwnNotice() {
        assertTrue(UpdateNotice.shouldAnnounce(true, "V3.4.0", "V3.3.1"))
    }

    @Test fun nothingWhenUpToDateOrUnknown() {
        assertFalse(UpdateNotice.shouldAnnounce(false, "V3.3.1", null))
        assertFalse(UpdateNotice.shouldAnnounce(true, null, null))
        assertFalse(UpdateNotice.shouldAnnounce(true, "", null))
    }
}
