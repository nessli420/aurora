package com.aurora.music.data

import com.aurora.music.model.TrackComment
import java.util.Locale

// a page of one service's comments and where the next page starts, null on the last
class CommentPage(val comments: List<TrackComment>, val next: Any? = null)

// one service's comments for the song as far as they have loaded
data class CommentFeed(
    val comments: List<TrackComment> = emptyList(),
    val next: Any? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    // no track there to comment on, comments turned off, or the first page failed
    val missing: Boolean = false,
) {
    val hasMore: Boolean get() = !loaded || next != null
    val empty: Boolean get() = loaded && comments.isEmpty()

    // a failed page ends the feed, so scrolling past it does not retry forever
    fun plus(page: CommentPage?) = CommentFeed((comments + page?.comments.orEmpty()).distinctBy { it.id }, page?.next,
        loaded = true, missing = page == null && comments.isEmpty())
}

// a service may have comments until it loaded none; one you can post to stays while its track exists, for the first comment
private fun CommentFeed?.mayShow(postable: Boolean) = this == null || !empty || (postable && !missing)

// the service the sheet shows: one picked on this song stays, else the last pick while it may have comments, else the first that may
fun shownCommentSource(sources: List<ServerType>, feeds: Map<ServerType, CommentFeed>, pick: ServerType?, pickedHere: Boolean,
    postable: Set<ServerType> = emptySet()): ServerType? {
    if (pickedHere && pick in sources) return pick
    val open = sources.filter { feeds[it].mayShow(it in postable) }
    return pick?.takeIf { it in open } ?: open.firstOrNull() ?: sources.firstOrNull()
}

// the player offers comments while any service may still have some
fun mayHaveComments(sources: List<ServerType>, feeds: Map<ServerType, CommentFeed>, postable: Set<ServerType> = emptySet()) =
    sources.any { feeds[it].mayShow(it in postable) }

// the switch offers the one on screen and the others once their first page turned out to have comments
fun offeredCommentSources(sources: List<ServerType>, feeds: Map<ServerType, CommentFeed>, shown: ServerType?,
    postable: Set<ServerType> = emptySet()): List<ServerType> = sources.filter { it == shown || feeds[it]?.loaded == true && feeds[it].mayShow(it in postable) }

fun commentSourceName(type: ServerType) = when (type) {
    ServerType.YOUTUBE_MUSIC -> "YouTube"
    ServerType.SOUNDCLOUD -> "SoundCloud"
    else -> type.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
}

// 950, 1.2K, 34K, 1.5M
fun compactCount(n: Int): String = when {
    n < 1000 -> n.toString()
    n < 10_000 -> String.format(Locale.getDefault(), "%.1fK", n / 1000.0)
    n < 1_000_000 -> "${n / 1000}K"
    else -> String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0)
}
