package com.aurora.music.playback

import org.junit.Assert.*
import org.junit.Test

class OutputTrackEvidenceTest {
    private data class Format(val rate: Int, val encoding: Int = 16, val channels: Int = 2)
    private fun tracker() = OutputTrackEvidence<Format> { a, b -> a == b }

    @Test fun lateReleaseOfPreviousFormatDoesNotEraseReplacement() {
        val tracks = tracker()
        val old = Format(44100)
        val replacement = Format(96000, 24)
        tracks.initialized(old)
        tracks.initialized(replacement)
        tracks.released(old)
        assertEquals(replacement, tracks.configuration)
        tracks.released(replacement)
        assertNull(tracks.configuration)
    }

    @Test fun identicalFormatsStillHaveDistinctLifetimes() {
        val tracks = tracker()
        val format = Format(44100)
        tracks.initialized(format)
        tracks.initialized(format.copy())
        tracks.released(format.copy())
        assertEquals(format, tracks.configuration)
        tracks.released(format.copy())
        assertNull(tracks.configuration)
    }

    @Test fun releasingCurrentDoesNotResurrectAnOlderPendingRelease() {
        val tracks = tracker()
        val old = Format(44100)
        val current = Format(48000)
        tracks.initialized(old)
        tracks.initialized(current)
        tracks.released(current)
        assertNull(tracks.configuration)
        tracks.released(old)
        assertNull(tracks.configuration)
    }

    @Test fun unknownReleaseCannotEraseTheCurrentObservation() {
        val tracks = tracker()
        val current = Format(48000)
        tracks.initialized(current)
        tracks.released(Format(96000, 24))
        assertEquals(current, tracks.configuration)
    }
}
