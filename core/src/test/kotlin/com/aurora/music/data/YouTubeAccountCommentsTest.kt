package com.aurora.music.data

import com.aurora.music.data.remote.YouTubeCommentPages
import com.aurora.music.data.remote.YouTubeMusicTransport
import com.aurora.music.model.Song
import com.aurora.music.model.TrackComment
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAccountCommentsTest {
    private val watch = JsonParser.parseString("""{"contents": {"itemSectionRenderer": {"sectionIdentifier": "comment-item-section",
        "contents": [{"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "SECTION"}}}}]}}}""").asJsonObject

    private val page = JsonParser.parseString("""{
      "onResponseReceivedEndpoints": [
        {"reloadContinuationItemsCommand": {"continuationItems": [{"commentsHeaderRenderer": {"createRenderer": {"commentSimpleboxRenderer":
          {"submitButton": {"buttonRenderer": {"serviceEndpoint": {"createCommentEndpoint": {"createCommentParams": "POST"}}}}}}}}]}},
        {"reloadContinuationItemsCommand": {"continuationItems": [
          {"commentThreadRenderer": {"commentViewModel": {"commentViewModel": {"commentKey": "k1", "toolbarStateKey": "s1", "toolbarSurfaceKey": "t1", "commentId": "c1"}},
            "replies": {"commentRepliesRenderer": {"contents": [{"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "REPLIES"}}}}]}}}},
          {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "NEXT"}}}}
        ]}}
      ],
      "frameworkUpdates": {"entityBatchUpdate": {"mutations": [
        {"entityKey": "k1", "payload": {"commentEntityPayload": {"properties": {"commentId": "c1", "content": {"content": "so good"}, "publishedTime": "2 years ago"},
          "author": {"displayName": "@fan", "avatarThumbnailUrl": "https://yt3.ggpht.com/a"}, "toolbar": {"likeCountNotliked": "1.2K", "likeCountLiked": "1.3K"}}}},
        {"entityKey": "s1", "payload": {"engagementToolbarStateEntityPayload": {"likeState": "TOOLBAR_LIKE_STATE_LIKED"}}},
        {"entityKey": "t1", "payload": {"engagementToolbarSurfaceEntityPayload": {
          "likeCommand": {"innertubeCommand": {"performCommentActionEndpoint": {"action": "LIKE"}}},
          "unlikeCommand": {"innertubeCommand": {"performCommentActionEndpoint": {"action": "UNLIKE"}}}}}}
      ]}}
    }""").asJsonObject

    private val liked = TrackComment("c1", "so good", -1, "@fan", "https://yt3.ggpht.com/a", 1300, "2 years ago", liked = true)

    @Test fun anAccountsPageCarriesItsLikesAndTheTokensToActWith() {
        assertEquals("SECTION", YouTubeCommentPages.sectionToken(watch))
        assertNull(YouTubeCommentPages.sectionToken(JsonObject()))
        val parsed = YouTubeCommentPages.page(page)
        assertEquals(listOf(liked), parsed.comments)
        // the replies' continuation is not the next page
        assertEquals("NEXT", parsed.next)
        assertEquals("POST", parsed.createParams)
        assertEquals(mapOf("c1" to ("LIKE" to "UNLIKE")), parsed.actions)
        assertEquals(listOf(950, 1200, 29000, 3_400_000, 0), listOf("950", "1.2K", "29K", "3.4M", "").map(YouTubeCommentPages::count))
    }

    @Test fun onlyASignedInAccountLikesAndPosts() = runBlocking {
        val calls = mutableListOf<String>()
        val api = object : YouTubeMusicTransport {
            override suspend fun request(endpoint: String, body: JsonObject) = JsonObject()
            override suspend fun youtube(endpoint: String, body: JsonObject): JsonObject {
                calls += "$endpoint ${body.entrySet().joinToString { (k, v) -> "$k=$v" }}"
                return when (endpoint) {
                    "next" -> if (body.has("videoId")) watch else page
                    "comment/create_comment" -> page
                    else -> JsonObject()
                }
            }
        }
        val song = Song("dQw4w9WgXcQ", "Never Gonna", "Rick", "", "", 213)
        val account = YouTubeMusicBackend(Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC), api)
        assertEquals(listOf(liked), account.comments(song, ServerType.YOUTUBE_MUSIC)!!.comments)
        assertTrue(account.canPostComments(song, ServerType.YOUTUBE_MUSIC) && account.canLikeComments(song, ServerType.YOUTUBE_MUSIC))
        assertTrue(account.likeComment(song, ServerType.YOUTUBE_MUSIC, "c1", false))
        assertFalse(account.likeComment(song, ServerType.YOUTUBE_MUSIC, "unknown", true))
        assertEquals("c1", account.postComment(song, ServerType.YOUTUBE_MUSIC, "hi", -1)!!.id)
        assertEquals(listOf("next videoId=\"dQw4w9WgXcQ\"", "next continuation=\"SECTION\"", "comment/perform_comment_action actions=[\"UNLIKE\"]",
            "comment/create_comment createCommentParams=\"POST\", commentText=\"hi\""), calls)

        // a guest reads the public thread and gets neither
        val read = mutableListOf<String>()
        val guest = YouTubeMusicBackend(Session.guest(ServerType.YOUTUBE_MUSIC, "Guest"), api,
            videoComments = { id, _ -> read += id; CommentPage(listOf(liked.copy(liked = false))) })
        assertEquals(1, guest.comments(song, ServerType.YOUTUBE_MUSIC)!!.comments.size)
        assertEquals(listOf(song.id), read)
        assertFalse(guest.canPostComments(song, ServerType.YOUTUBE_MUSIC) || guest.canLikeComments(song, ServerType.YOUTUBE_MUSIC))
        assertNull(guest.postComment(song, ServerType.YOUTUBE_MUSIC, "hi", -1))
        assertEquals(4, calls.size)
    }
}
