package com.aurora.music.data.remote

import com.aurora.music.data.CommentPage
import com.aurora.music.model.TrackComment
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.Description
import java.util.Locale

// a video's comment threads through newpipe, which the platform has set up; blocks, so call it off the main thread
object YouTubeComments {
    private class Cursor(val url: String, val page: Page)

    // null when the video has its comments turned off
    fun page(videoId: String, next: Any?): CommentPage? {
        val cursor = next as? Cursor
        val url = cursor?.url ?: "https://www.youtube.com/watch?v=$videoId"
        // youtube words the ages in the app's language
        val extractor = ServiceList.YouTube.getCommentsExtractor(url).apply { forceLocalization(Localization.fromLocale(Locale.getDefault())) }
        if (cursor != null) return extractor.getPage(cursor.page).let { page(url, it.items, it.nextPage) }
        val info = CommentsInfo.getInfo(extractor)
        if (info.isCommentsDisabled) return null
        return page(url, info.relatedItems, info.nextPage)
    }

    private fun page(url: String, items: List<CommentsInfoItem>, next: Page?) =
        CommentPage(items.mapNotNull(::comment), next?.takeIf { Page.isValid(it) }?.let { Cursor(url, it) })

    fun comment(item: CommentsInfoItem): TrackComment? {
        val body = text(item.commentText)
        if (item.commentId.isNullOrBlank() || body.isBlank()) return null
        // the smallest avatar youtube offers is still 88px, plenty for the row
        val avatar = item.uploaderAvatars.minByOrNull { it.height }?.url.orEmpty()
        return TrackComment(item.commentId, body, -1, item.uploaderName.orEmpty(), avatar,
            item.likeCount.coerceAtLeast(0), item.textualUploadDate.orEmpty())
    }

    private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")

    fun text(d: Description?): String {
        val content = d?.content().orEmpty()
        if (d?.type() != Description.Type.HTML) return content.trim()
        return content.replace(BREAK, "\n").replace(TAG, "").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&#x27;", "'").replace("&nbsp;", " ").replace("&amp;", "&").trim()
    }
}
