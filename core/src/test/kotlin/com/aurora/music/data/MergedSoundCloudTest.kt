package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MergedSoundCloudTest {
    private val soundcloud = Session("https://soundcloud.com", "listener", "", "reference", ServerType.SOUNDCLOUD, userId = "42")
    private val yt = Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC, userId = "account")
    private val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)
    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)

    private val scSong = Song("275215991", "sake bomb", "Topherblaine", "", "", 191, streamUrl = "aurora-sc://track/275215991")
    private val ytSong = Song("youtube-id", "Still love you", "GLM-RST", "Still", "", 146, streamUrl = "aurora-yt://video/youtube-id")
    private val localSong = Song("file", "Local only", "Someone", "Disk", "", 180, streamUrl = "file:///local.flac")

    private fun source(session: Session, songs: List<Song>, searches: MutableList<String> = mutableListOf()): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> session
                "home" -> HomeData(sections = listOf(HomeFeedSection("shelf", "For you", items = songs.map { HomeFeedItem.Track(it) })))
                "search" -> { searches += args!![0] as String; SearchResults(songs = songs) }
                "allSongs", "librarySongs", "starredSongs", "songsPage", "matchingSongs" -> songs
                "starredCount" -> songs.size
                "allAlbums", "allArtists", "allPlaylists" -> emptyList<Any>()
                "playbackSourceIdentity" -> PlaybackSourceIdentity.fromSession(session, (args!![0] as Song).albumId)
                "downloadUrl" -> "https://cdn.example/${args!![0]}/playlist.m3u8"
                "supportsComments" -> session.type == ServerType.SOUNDCLOUD || session.type == ServerType.YOUTUBE_MUSIC
                "canPostComments" -> session.type == args!![1]
                "getSignedIn" -> true
                "comments" -> com.aurora.music.data.CommentPage(listOf(com.aurora.music.model.TrackComment(
                    "${session.type.name.lowercase()}-${(args!![0] as Song).id}-${args[2] ?: 1}", "fire", 42000, "fan", "")), "page2".takeIf { args[2] == null })
                "postComment" -> com.aurora.music.model.TrackComment("new-${session.type.name.lowercase()}-${(args!![0] as Song).id}", args[2] as String, args[3] as Long, "me", "")
                "likeComment" -> session.type == ServerType.YOUTUBE_MUSIC && args!![2] == "c1"
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    @Test fun soundcloudJoinsTheMergeAsItsOwnCatalogue() = runBlocking {
        assertTrue(ServerType.SOUNDCLOUD.supportsMergedLibrary && ServerType.SOUNDCLOUD.isCatalogue)
        val searches = mutableListOf<String>()
        val merged = MergedBackend(listOf(source(local, listOf(localSong)), source(yt, listOf(ytSong)), source(soundcloud, listOf(scSong), searches)), soundcloud)
        assertEquals(listOf("library", "discovery", "soundcloud"), merged.homeFeeds.map { it.id })
        assertEquals(listOf("library", "youtube", "soundcloud"), merged.libraryViews.map { it.id })
        assertEquals(listOf("sake bomb"), merged.home("soundcloud").sections.flatMap { it.items }.map { (it as HomeFeedItem.Track).song.title })
        assertEquals(listOf("sake bomb"), merged.search("bomb", "soundcloud").songs.map { it.title })
        assertEquals(listOf("bomb"), searches)
        // catalogues stay out of the merged library and its likes keep to their own view
        assertEquals(listOf("Local only"), merged.libraryView("library").allSongs().map { it.title })
        assertEquals(listOf("sake bomb"), merged.detail("liked", "soundcloud")!!.tracks.map { it.title })
        // downloads ask the owning source, not the merged play link
        val wrapped = merged.detail("liked", "soundcloud")!!.tracks.single().id
        assertEquals("https://cdn.example/275215991/playlist.m3u8", merged.downloadUrl(wrapped, 0, true))
    }

    @Test fun songsFromElsewhereBorrowTheCommentsOfTheirSoundcloudUpload() = runBlocking {
        val hosted = Song("nd-1", "sake bomb", "Topherblaine", "Singles", "", 191, streamUrl = "https://server.invalid/stream")
        // the upload carries the artist in its title and comes from a fan account
        val upload = scSong.copy(title = "Topherblaine - sake bomb", artist = "some uploader", durationSec = 191)
        val searches = mutableListOf<String>()
        val merged = MergedBackend(listOf(source(server, listOf(hosted)), source(soundcloud, listOf(upload), searches)), server)
        val track = merged.libraryView("library").allSongs().single()
        assertTrue(merged.supportsComments(track))
        assertEquals(listOf(ServerType.SOUNDCLOUD), merged.commentSources(track))
        assertEquals(listOf("soundcloud-275215991-1"), merged.comments(track, ServerType.SOUNDCLOUD)!!.comments.map { it.id })
        assertNull(merged.comments(track, ServerType.YOUTUBE_MUSIC))
        assertEquals("new-soundcloud-275215991", merged.postComment(track, ServerType.SOUNDCLOUD, "yo", 1000)!!.id)
        assertEquals(listOf("sake bomb Topherblaine"), searches)
        val other = MergedBackend(listOf(source(server, listOf(hosted.copy(title = "Elsewhere"))), source(soundcloud, listOf(upload))), server)
        // no upload to match means no comments to show or post to
        assertNull(other.comments(other.libraryView("library").allSongs().single(), ServerType.SOUNDCLOUD))
    }

    @Test fun youtubeCommentsJoinSoundcloudsAndPageThroughTheTwin() = runBlocking {
        val hosted = Song("nd-1", "Still love you", "GLM-RST", "Still", "", 146, streamUrl = "https://server.invalid/stream")
        val upload = scSong.copy(title = "Still love you", artist = "GLM-RST", durationSec = 146)
        val ytSearches = mutableListOf<String>()
        val merged = MergedBackend(listOf(source(server, listOf(hosted)), source(yt, listOf(ytSong), ytSearches), source(soundcloud, listOf(upload))), server)
        val track = merged.libraryView("library").allSongs().single()
        // soundcloud's timed comments come first, whatever order the sources were added in
        assertEquals(listOf(ServerType.SOUNDCLOUD, ServerType.YOUTUBE_MUSIC), merged.commentSources(track))
        val first = merged.comments(track, ServerType.YOUTUBE_MUSIC)!!
        assertEquals(listOf("youtube_music-youtube-id-1"), first.comments.map { it.id })
        assertEquals(listOf("youtube_music-youtube-id-page2"), merged.comments(track, ServerType.YOUTUBE_MUSIC, first.next)!!.comments.map { it.id })
        assertEquals(1, ytSearches.size)
        // a youtube music song shows its own comments and soundcloud's, and posts only to soundcloud
        val own = merged.detail("liked", "youtube")!!.tracks.single()
        assertEquals(listOf(ServerType.SOUNDCLOUD, ServerType.YOUTUBE_MUSIC), merged.commentSources(own))
        assertEquals("youtube_music-youtube-id-1", merged.comments(own, ServerType.YOUTUBE_MUSIC)!!.comments.single().id)
        assertTrue(merged.canPostComments(own, ServerType.SOUNDCLOUD) && merged.canPostComments(own, ServerType.YOUTUBE_MUSIC))
        assertEquals("new-soundcloud-${upload.id}", merged.postComment(own, ServerType.SOUNDCLOUD, "yo", 1000)!!.id)
        assertEquals("new-youtube_music-youtube-id", merged.postComment(own, ServerType.YOUTUBE_MUSIC, "yo", -1)!!.id)
        // likes are youtube's alone, on its own songs and through a twin
        assertTrue(merged.canLikeComments(own, ServerType.YOUTUBE_MUSIC) && !merged.canLikeComments(own, ServerType.SOUNDCLOUD))
        assertTrue(merged.likeComment(own, ServerType.YOUTUBE_MUSIC, "c1", true))
        assertTrue(merged.likeComment(track, ServerType.YOUTUBE_MUSIC, "c1", true))
        assertFalse(merged.likeComment(track, ServerType.SOUNDCLOUD, "c1", true))
    }

    @Test fun mergeOptionsFollowTheSourceASongComesFrom() = runBlocking {
        val hosted = Song("nd-1", "sake bomb", "Topherblaine", "Singles", "", 191, streamUrl = "https://server.invalid/stream")
        val merged = MergedBackend(listOf(source(server, listOf(hosted)), source(soundcloud, listOf(scSong))), server)
        val fromServer = merged.libraryView("library").allSongs().single()
        val fromSoundcloud = merged.detail("liked", "soundcloud")!!.tracks.single()
        assertEquals(ServerType.SUBSONIC, merged.sourceType(fromServer))
        assertEquals(ServerType.SOUNDCLOUD, merged.sourceType(fromSoundcloud))
        val options = MergeFeatures(commentsOff = setOf(ServerType.SUBSONIC.name), canvas = false)
        assertFalse(options.commentsFor(ServerType.SUBSONIC))
        assertTrue(options.commentsFor(ServerType.SOUNDCLOUD))
        assertFalse(options.canvasFor(ServerType.SPOTIFY))
        assertTrue(options.videosFor(ServerType.SUBSONIC))
        assertFalse(MergeFeatures(videos = false).videosFor(ServerType.YOUTUBE_MUSIC))
        val noYoutube = MergeFeatures(youtubeCommentsOff = setOf(ServerType.SUBSONIC.name))
        assertFalse(noYoutube.commentsFrom(ServerType.YOUTUBE_MUSIC, ServerType.SUBSONIC))
        assertTrue(noYoutube.commentsFrom(ServerType.SOUNDCLOUD, ServerType.SUBSONIC))
        assertTrue(noYoutube.commentsFrom(ServerType.YOUTUBE_MUSIC, ServerType.SOUNDCLOUD))
        assertFalse(MergeFeatures(youtubeComments = false).commentsFrom(ServerType.YOUTUBE_MUSIC, ServerType.YOUTUBE_MUSIC))
    }
}
