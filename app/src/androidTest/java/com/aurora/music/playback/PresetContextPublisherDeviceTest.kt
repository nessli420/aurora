package com.aurora.music.playback

import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aurora.music.data.*
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.model.Song
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PresetContextPublisherDeviceTest {
    private val session = Session("https://fixture.invalid", "listener", "salt", "token")
    private val origin = PlaybackSourceIdentity.fromSession(session, "album")
    private val playlist = PlaybackCollectionIdentity(PlaybackSourceIdentity.scoped(origin.providerId!!, "playlist", "playlist"), "Evening")
    private val song = Song("track", "Title", "Artist", "Album", "", 60, streamUrl = "https://fixture.invalid/audio",
        genre = "Ambient", suffix = "flac", sampleRateHz = 192000, playbackSource = origin, playbackCollection = playlist)

    private fun item(song: Song = this.song) = MediaItem.Builder().setMediaId(song.id).setUri(song.streamUrl)
        .setMediaMetadata(MediaMetadata.Builder().setAlbumTitle(song.album).setExtras(PresetContextPublisher.extras(song)).build()).build()

    @Test fun observedTranscodeFormatWinsOverOriginalTrackTags() {
        val format = Format.Builder().setSampleRate(48000).setSampleMimeType("audio/mpeg").setContainerMimeType("audio/mpeg").build()
        val actual = PresetContextPublisher.build(item(), true, format, false, false)
        assertEquals(48000, actual.sampleRateHz)
        assertEquals("mp3", actual.codec)
        assertEquals("mp3", actual.container)
        assertEquals(origin.providerId, actual.providerId)
        assertEquals(origin.albumId, actual.albumId)
        assertEquals(playlist.id, actual.playlistId)
        assertEquals("Evening", actual.playlistName)
        assertEquals(setOf("Ambient"), actual.genres)
    }

    @Test fun unknownStreamFormatDoesNotGuessFromFileSuffixOrDeclaredRate() {
        val actual = PresetContextPublisher.build(item(), true, null, null, false)
        assertNull(actual.sampleRateHz)
        assertNull(actual.codec)
        assertNull(actual.container)
        assertNull(actual.androidAuto)
        assertEquals(RuleSource.STREAM, actual.source)
    }

    @Test fun oldDownloadsRemainProviderUnknownWhileTheirPlayableCopyIsKnown() {
        val old = DownloadedSong("download", "Title", "Artist", "Album", "album", "artist", 60,
            "/data/local/tmp/fixture.flac", "", suffix = "flac", serverId = "https://other.invalid")
        val local = old.toSong()
        val actual = PresetContextPublisher.build(item(local), true, null, false, false)
        assertEquals(RuleSource.DOWNLOAD, actual.source)
        assertEquals("flac", actual.container)
        assertNull(actual.providerId)
        assertNull(actual.albumId)
        assertNull(actual.playlistId)
    }

    @Test fun savedQueueRetainsSourceAndPlaylistContextAndOlderQueuesRemainUnknown() {
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(song.toSavedTrack()), SavedTrack::class.java).toSong()
        assertEquals(song.playbackSource, restored.playbackSource)
        assertEquals(song.playbackCollection, restored.playbackCollection)
        val actual = PresetContextPublisher.build(item(restored), true, null, false, false)
        assertEquals(playlist.id, actual.playlistId)
        val old = gson.fromJson("{\"id\":\"old\",\"title\":\"Old\"}", SavedTrack::class.java).toSong()
        assertNull(old.playbackSource)
        assertNull(old.playbackCollection)
    }

    @Test fun idleClearsAllTrackAndCollectionFacts() {
        val idle = PresetContextPublisher.build(item(), false, Format.Builder().setSampleRate(96000).build(), true, false)
        assertFalse(idle.active)
        assertNull(idle.providerId)
        assertNull(idle.playlistId)
        assertNull(idle.sampleRateHz)
        assertEquals(true, idle.androidAuto)
    }
}
