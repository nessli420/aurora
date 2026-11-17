package com.aurora.music.data

import com.aurora.music.data.remote.YouTubeComments
import com.aurora.music.model.TrackComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.Description

class CommentFeedTest {
    private val sc = ServerType.SOUNDCLOUD
    private val yt = ServerType.YOUTUBE_MUSIC
    private fun comment(id: String) = TrackComment(id, "hi", -1, "fan", "")

    @Test fun youtubeCommentsMapToPlainUntimedRows() {
        val item = CommentsInfoItem(0, "https://www.youtube.com/watch?v=x", "").apply {
            commentId = "Ugy1"
            commentText = Description("Ok let&#39;s be honest<br>it&amp;s I&apos;m <a href=\"https://www.youtube.com/watch?v=x&amp;t=143\">2:23</a>", Description.Type.HTML)
            uploaderName = "@fan"
            uploaderAvatars = listOf(Image("https://yt3.ggpht.com/big", 176, 176, Image.ResolutionLevel.MEDIUM),
                Image("https://yt3.ggpht.com/small", 88, 88, Image.ResolutionLevel.LOW))
            likeCount = 429000
            textualUploadDate = "6 years ago"
        }
        assertEquals(TrackComment("Ugy1", "Ok let's be honest\nit&s I'm 2:23", -1, "@fan", "https://yt3.ggpht.com/small", 429000, "6 years ago"),
            YouTubeComments.comment(item))
        assertNull(YouTubeComments.comment(CommentsInfoItem(0, "", "").apply { commentId = "Ugy2"; commentText = Description("<br>", Description.Type.HTML) }))
        assertEquals("plain <b>", YouTubeComments.text(Description(" plain <b> ", Description.Type.PLAIN_TEXT)))
    }

    @Test fun feedsPageOnAndEndOnTheLastOrAFailedPage() {
        val first = CommentFeed().plus(CommentPage(listOf(comment("a"), comment("b")), "next"))
        assertTrue(first.hasMore && !first.empty)
        val second = first.plus(CommentPage(listOf(comment("b"), comment("c"))))
        assertEquals(listOf("a", "b", "c"), second.comments.map { it.id })
        assertFalse(second.hasMore)
        assertTrue(CommentFeed().hasMore)
        assertTrue(CommentFeed().plus(null).empty && CommentFeed().plus(null).missing)
        assertFalse(CommentFeed().plus(CommentPage(emptyList())).missing)
        assertFalse(first.plus(null).hasMore || first.plus(null).missing)
    }

    @Test fun theSheetOpensOnTheLastPickWhileItMayHaveComments() {
        val sources = listOf(sc, yt)
        val loaded = mapOf(sc to CommentFeed(listOf(comment("a")), loaded = true))
        assertEquals(sc, shownCommentSource(sources, emptyMap(), null, false))
        assertEquals(yt, shownCommentSource(sources, loaded, yt, false))
        // soundcloud turned up nothing, so youtube opens even without a pick
        val scEmpty = mapOf(sc to CommentFeed(loaded = true))
        assertEquals(yt, shownCommentSource(sources, scEmpty, null, false))
        // a remembered pick with nothing on this song falls back, one picked here stays
        val ytEmpty = loaded + (yt to CommentFeed(loaded = true))
        assertEquals(sc, shownCommentSource(sources, ytEmpty, yt, false))
        assertEquals(yt, shownCommentSource(sources, ytEmpty, yt, true))
        assertEquals(sc, shownCommentSource(listOf(sc), emptyMap(), yt, true))
        assertNull(shownCommentSource(emptyList(), emptyMap(), yt, true))
    }

    @Test fun theSwitchOffersOnlySourcesThatHaveComments() {
        val sources = listOf(sc, yt)
        val has = CommentFeed().plus(CommentPage(listOf(comment("a"))))
        // youtube's first page is still on its way, so only the one on screen is offered
        assertEquals(listOf(sc), offeredCommentSources(sources, mapOf(sc to has), sc))
        assertEquals(sources, offeredCommentSources(sources, mapOf(sc to has, yt to has), sc))
        val ytEmpty = mapOf(sc to has, yt to CommentFeed().plus(CommentPage(emptyList())))
        assertEquals(listOf(sc), offeredCommentSources(sources, ytEmpty, sc))
        assertEquals(sources, offeredCommentSources(sources, ytEmpty, yt))
        // soundcloud's track without comments stays for the first post, but not when there is no track at all
        val scNone = mapOf(sc to CommentFeed().plus(CommentPage(emptyList())), yt to has)
        assertEquals(sources, offeredCommentSources(sources, scNone, yt, postable = setOf(sc)))
        assertEquals(listOf(yt), offeredCommentSources(sources, scNone, yt))
        assertEquals(listOf(yt), offeredCommentSources(sources, scNone + (sc to CommentFeed().plus(null)), yt, postable = setOf(sc)))
        // the player offers comments while any service may still have some
        assertTrue(mayHaveComments(sources, emptyMap()))
        assertTrue(mayHaveComments(sources, mapOf(sc to CommentFeed().plus(null))))
        assertFalse(mayHaveComments(sources, mapOf(sc to CommentFeed().plus(null), yt to CommentFeed().plus(CommentPage(emptyList())))))
        assertEquals(listOf("950", "1.2K", "34K", "1.5M"), listOf(950, 1234, 34_567, 1_500_000).map { compactCount(it) }.map { it.replace(',', '.') })
    }
}
