package com.aurora.music.data

import com.aurora.music.data.SpotifyMirrorCommand.Pause
import com.aurora.music.data.SpotifyMirrorCommand.Play
import com.aurora.music.data.SpotifyMirrorCommand.Resume
import com.aurora.music.data.SpotifyMirrorCommand.Seek
import com.aurora.music.data.SpotifyMirrorCommand.Stop
import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyMirrorFollowerTest {
    private val song = Song("4uLU6hMCjMI75M1A2tKUQC", "Track", "Artist", "Album", "", 200)
    private val follower = SpotifyMirrorFollower()

    private fun report(event: PlaybackReportEvent, state: PlaybackReportState, positionMs: Long, session: String = "a") =
        PlaybackReport(song, session, event, state, positionMs, 200_000, 0, 0)

    private fun follow(event: PlaybackReportEvent, state: PlaybackReportState, positionMs: Long, nowMs: Long,
        session: String = "a", enabled: Boolean = true) = follower.follow(report(event, state, positionMs, session), nowMs, enabled)

    @Test fun followsPauseResumeSeekAndStop() {
        assertEquals(listOf(Play(song.id, 0)), follow(PlaybackReportEvent.START, PlaybackReportState.PLAYING, 0, 0))
        // the 30 second heartbeat on schedule changes nothing
        assertEquals(emptyList<SpotifyMirrorCommand>(), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PLAYING, 30_000, 30_400))
        assertEquals(listOf(Pause), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PAUSED, 41_000, 41_000))
        // a seek while paused moves the copy without starting it
        assertEquals(listOf(Seek(90_000)), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PAUSED, 90_000, 60_000))
        assertEquals(listOf(Resume), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PLAYING, 90_000, 70_000))
        assertEquals(listOf(Seek(10_000)), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PLAYING, 10_000, 80_000))
        assertEquals(emptyList<SpotifyMirrorCommand>(), follow(PlaybackReportEvent.SCROBBLE, PlaybackReportState.PLAYING, 40_000, 110_000))
        assertEquals(listOf(Stop), follow(PlaybackReportEvent.STOP, PlaybackReportState.STOPPED, 45_000, 115_000))
        assertEquals(emptyList<SpotifyMirrorCommand>(), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PLAYING, 46_000, 116_000))
    }

    @Test fun bufferingKeepsPlayingAndSkipStartsTheNextSong() {
        follow(PlaybackReportEvent.START, PlaybackReportState.PLAYING, 0, 0)
        assertEquals(emptyList<SpotifyMirrorCommand>(), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.BUFFERING, 2_000, 2_000))
        assertEquals(listOf(Stop), follow(PlaybackReportEvent.STOP, PlaybackReportState.STOPPED, 3_000, 3_000))
        assertEquals(listOf(Play(song.id, 0)), follow(PlaybackReportEvent.START, PlaybackReportState.PLAYING, 0, 3_100, session = "b"))
        // a late report from the song before is not this one's
        assertEquals(emptyList<SpotifyMirrorCommand>(), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PAUSED, 3_000, 3_200))
    }

    @Test fun turningOffStopsOnceAndNeverStarts() {
        follow(PlaybackReportEvent.START, PlaybackReportState.PLAYING, 0, 0)
        assertEquals(listOf(Stop), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PLAYING, 30_000, 30_000, enabled = false))
        assertEquals(emptyList<SpotifyMirrorCommand>(), follow(PlaybackReportEvent.PROGRESS, PlaybackReportState.PLAYING, 60_000, 60_000, enabled = false))
        assertEquals(emptyList<SpotifyMirrorCommand>(), follow(PlaybackReportEvent.START, PlaybackReportState.PLAYING, 0, 70_000, session = "b", enabled = false))
    }
}
