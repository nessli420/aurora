package com.aurora.music.data

/**
 * Merged mode opens Home and Library on "library": local files plus Navidrome, Jellyfin and Plex. With only
 * streaming accounts merged and no music on the device that view is blank, so when it comes back empty and the
 * user hasn't picked a view themselves, the first streaming view takes over instead of an empty screen.
 */
object EmptyViewFallback {
    const val LIBRARY = "library"

    /** The view to switch to, or null to stay. [views] are the choice ids in screen order. */
    fun pick(views: List<String>, current: String, chosen: Boolean, empty: Boolean): String? =
        if (chosen || !empty || current != LIBRARY || views.firstOrNull() != LIBRARY) null
        else views.drop(1).firstOrNull()
}

val HomeData.isBlank: Boolean
    get() = newReleases.isEmpty() && recentlyPlayed.isEmpty() && mostPlayed.isEmpty() && random.isEmpty() &&
        playlists.isEmpty() && artists.isEmpty() && starred.isEmpty() && sections.all { it.items.isEmpty() }
