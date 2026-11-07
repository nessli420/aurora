package com.aurora.music.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResumePointsTest {
    private val root: File = Files.createTempDirectory("aurora-resume").toFile()

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun episodesAndAudiobooksAreLongFormButSongsRadioAndMixesAreNot() {
        assertTrue(LongForm.applies("podcast:ep1", 0))
        assertTrue(LongForm.applies("plex-book", 45 * 60))
        assertFalse(LongForm.applies("song", 4 * 60))
        assertFalse(LongForm.applies("radio:fip", 0))
        assertFalse(LongForm.applies("aurora-mix:1", 90 * 60))
    }

    @Test fun shortSpokenWordChaptersGetSkipButtonsButShortSongsDoNot() {
        assertTrue(LongForm.skips("plex-chapter", 12 * 60, "Audiobook"))
        assertTrue(LongForm.skips("plex-chapter", 8 * 60, "Books & Spoken"))
        assertTrue(LongForm.skips("local-chapter", 5 * 60, "Fantasy\u0000Hörbuch"))
        assertTrue(LongForm.skips("plex-book", 45 * 60, ""))
        assertFalse(LongForm.skips("song", 4 * 60, "Pop"))
        assertFalse(LongForm.skips("song", 4 * 60, "Spoken Rock Anthems Vol 2"))
        assertFalse(LongForm.skips("radio:talk", 0, "Podcast"))
    }

    @Test fun eachKindFollowsItsOwnSetting() {
        val podcastsOnly = PlaybackPrefs(resumePodcasts = true, resumeLongTracks = false)
        assertTrue(LongForm.resumes("podcast:ep1", 0, podcastsOnly))
        assertFalse(LongForm.resumes("plex-book", 45 * 60, podcastsOnly))
        val booksOnly = PlaybackPrefs(resumePodcasts = false, resumeLongTracks = true)
        assertFalse(LongForm.resumes("podcast:ep1", 50 * 60, booksOnly))
        assertTrue(LongForm.resumes("plex-book", 45 * 60, booksOnly))
    }

    @Test fun aPointSurvivesARestartAndFinishedOrBarelyStartedOnesAreForgotten() {
        val points = ResumePoints(root)
        points.record("podcast:ep1", 754, 1800)
        points.record("podcast:ep2", 1790, 1800)
        points.record("podcast:ep3", 6, 1800)
        points.flushNow()

        val reopened = ResumePoints(root)
        assertEquals(754, reopened.positionSec("podcast:ep1"))
        assertNull(reopened.positionSec("podcast:ep2"))
        assertNull(reopened.positionSec("podcast:ep3"))

        reopened.record("podcast:ep1", 1785, 1800)
        assertNull(reopened.positionSec("podcast:ep1"))
    }
}
