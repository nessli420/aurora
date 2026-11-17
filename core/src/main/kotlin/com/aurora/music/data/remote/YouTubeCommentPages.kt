package com.aurora.music.data.remote

import com.aurora.music.model.TrackComment
import com.google.gson.JsonObject

// a signed-in account's view of a video's comments from youtube's web api: its likes, and the tokens to like and post with
object YouTubeCommentPages {
    class Page(val comments: List<TrackComment>, val next: String?, val createParams: String?, val actions: Map<String, Pair<String, String>>)

    // the watch page's comment section, or null when the video has comments turned off
    fun sectionToken(watch: JsonObject): String? = watch.objects("itemSectionRenderer")
        .firstOrNull { it.string("sectionIdentifier") == "comment-item-section" }
        ?.objects("continuationCommand")?.firstOrNull()?.string("token")?.takeIf { it.isNotBlank() }

    fun page(root: JsonObject): Page {
        val items = root.array("onResponseReceivedEndpoints").flatMap { endpoint ->
            endpoint.asJsonObject.entrySet().flatMap { (_, v) -> if (v.isJsonObject) v.asJsonObject.array("continuationItems") else emptyList() }
        }.filter { it.isJsonObject }.map { it.asJsonObject }
        val next = items.lastOrNull()?.obj("continuationItemRenderer")?.objects("continuationCommand")?.firstOrNull()?.string("token")
        val createParams = items.firstNotNullOfOrNull { it.obj("commentsHeaderRenderer").objects("createCommentEndpoint").firstOrNull() }
            ?.string("createCommentParams")
        return threads(root, items.map { it.obj("commentThreadRenderer") }, next?.takeIf { it.isNotBlank() }, createParams?.takeIf { it.isNotBlank() })
    }

    // the comment a post created, as the account sees it
    fun created(root: JsonObject): Page = threads(root, root.objects("commentThreadRenderer"), null, null)

    private fun threads(root: JsonObject, threads: List<JsonObject>, next: String?, createParams: String?): Page {
        val entities = root.obj("frameworkUpdates").obj("entityBatchUpdate").array("mutations").filter { it.isJsonObject }
            .associate { it.asJsonObject.string("entityKey") to it.asJsonObject.obj("payload") }
        val actions = mutableMapOf<String, Pair<String, String>>()
        val comments = threads.mapNotNull { thread ->
            val view = thread.obj("commentViewModel").obj("commentViewModel")
            val entity = entities[view.string("commentKey")]?.obj("commentEntityPayload") ?: return@mapNotNull null
            val id = entity.obj("properties").string("commentId").ifBlank { view.string("commentId") }
            val body = entity.obj("properties").obj("content").string("content").trim()
            if (id.isBlank() || body.isBlank()) return@mapNotNull null
            val liked = entities[view.string("toolbarStateKey")]?.obj("engagementToolbarStateEntityPayload")?.string("likeState") == "TOOLBAR_LIKE_STATE_LIKED"
            val surface = entities[view.string("toolbarSurfaceKey")]?.obj("engagementToolbarSurfaceEntityPayload") ?: JsonObject()
            fun action(command: String) = surface.obj(command).objects("performCommentActionEndpoint").firstOrNull()?.string("action").orEmpty()
            val like = action("likeCommand")
            val unlike = action("unlikeCommand")
            if (like.isNotBlank() || unlike.isNotBlank()) actions[id] = like to unlike
            val toolbar = entity.obj("toolbar")
            TrackComment(id, body, -1, entity.obj("author").string("displayName"), entity.obj("author").string("avatarThumbnailUrl"),
                count(toolbar.string(if (liked) "likeCountLiked" else "likeCountNotliked")), entity.obj("properties").string("publishedTime"), liked)
        }
        return Page(comments, next, createParams, actions)
    }

    // youtube's short counts: 950, 1.2K, 29K, 3.4M
    fun count(text: String): Int {
        val t = text.trim().replace(",", "")
        val n = t.trimEnd('K', 'M', 'B', 'k', 'm', 'b').toDoubleOrNull() ?: return 0
        return (n * when (t.lastOrNull()?.uppercaseChar()) { 'K' -> 1e3; 'M' -> 1e6; 'B' -> 1e9; else -> 1.0 }).toInt()
    }
}
