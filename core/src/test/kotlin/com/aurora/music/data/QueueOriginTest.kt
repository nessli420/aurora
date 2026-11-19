package com.aurora.music.data

import com.aurora.music.foryou.MIX_KIND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueOriginTest {
    @Test fun onlyThePageThatStartedTheQueueMatches() {
        val playing = QueueOrigin.of("playlist", "1:evening")
        assertEquals(playing, QueueOrigin.of("playlist", "1:evening"))
        assertNotEquals(playing, QueueOrigin.of("album", "1:evening"))
        assertNotEquals(playing, QueueOrigin.of("playlist", "2:evening"))
    }

    @Test fun aMixQueueKeepsItsOriginOnTheNextEdition() {
        val today = QueueOrigin.of(MIX_KIND, "daily-1@20410")
        assertEquals(QueueOrigin.of(MIX_KIND, "time-evening@20410"), QueueOrigin.followed(today, "daily-1@20410", "time-evening@20410"))
        assertEquals(today, QueueOrigin.followed(today, "daily-2@20410", "daily-2@20411"))
        assertEquals("playlist:p", QueueOrigin.followed("playlist:p", "daily-1@20410", "daily-1@20411"))
        assertNull(QueueOrigin.followed(null, "daily-1@20410", "daily-1@20411"))
    }
}
