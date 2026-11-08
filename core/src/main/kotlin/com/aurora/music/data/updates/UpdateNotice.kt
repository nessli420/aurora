package com.aurora.music.data.updates

/**
 * The launch toast names a newer release once. A later, different release gets its own toast, but the same one
 * never comes up twice, and nothing shows when Aurora is up to date or the check failed.
 */
object UpdateNotice {
    fun shouldAnnounce(updateAvailable: Boolean, latestTag: String?, announcedTag: String?): Boolean =
        updateAvailable && !latestTag.isNullOrBlank() && latestTag != announcedTag
}
