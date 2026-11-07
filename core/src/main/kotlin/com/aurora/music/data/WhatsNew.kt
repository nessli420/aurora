package com.aurora.music.data

/**
 * Decides which update tours to show. Each platform keeps its own release notes because their text lives in
 * string resources, so everything here works on the release's version name.
 */
object WhatsNew {
    enum class Launch { SHOW, MARK_SEEN, NOTHING }

    /** Compares "3.4.0"-style names part by part. A leading v and anything after '-' or '+' are ignored. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.trim().toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** "3.4.0" reads as "3.4"; "3.4.1" stays as it is. */
    fun shortVersion(version: String): String {
        val core = version.trim().removePrefix("v").removePrefix("V")
        return if (core.count { it == '.' } >= 2 && core.endsWith(".0")) core.removeSuffix(".0") else core
    }

    /** Releases the installed [current] version already includes, newest first. */
    fun <T> released(releases: List<T>, current: String, version: (T) -> String): List<T> =
        releases.filter { compareVersions(version(it), current) <= 0 }
            .sortedWith { a, b -> compareVersions(version(b), version(a)) }

    /**
     * Releases newer than [lastSeen], oldest first, so someone who skipped a few updates sees them in order.
     * With no record of a previous tour (updating from a version without this feature), only the newest release.
     */
    fun <T> unseen(releases: List<T>, lastSeen: String?, current: String, version: (T) -> String): List<T> {
        val out = released(releases, current, version)
        val fresh = if (lastSeen == null) out.take(1) else out.filter { compareVersions(version(it), lastSeen) > 0 }
        return fresh.reversed()
    }

    /**
     * What to do at launch. A fresh install gets setup instead of a tour, and turning tours off still records
     * the version, so switching them back on later doesn't replay old updates.
     */
    fun onLaunch(freshInstall: Boolean, enabled: Boolean, lastSeen: String?, current: String, hasUnseen: Boolean): Launch = when {
        lastSeen != null && compareVersions(lastSeen, current) == 0 -> Launch.NOTHING
        freshInstall || !enabled || !hasUnseen -> Launch.MARK_SEEN
        else -> Launch.SHOW
    }
}
