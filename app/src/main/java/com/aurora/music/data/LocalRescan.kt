package com.aurora.music.data

data class LocalRescanResult(val added: Int, val removed: Int, val total: Int) {
    val changed: Boolean get() = added > 0 || removed > 0
}

object LocalRescan {
    val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "wma", "alac", "ape", "dsf", "dff", "mka")

    fun result(before: Set<String>, after: Set<String>) =
        LocalRescanResult(added = (after - before).size, removed = (before - after).size, total = after.size)

    fun roots(paths: Collection<String>): List<String> {
        val kept = ArrayList<String>()
        paths.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct().sortedBy { it.length }.forEach { path ->
            if (kept.none { path == it || path.startsWith("$it/") }) kept += path
        }
        return kept
    }
}
