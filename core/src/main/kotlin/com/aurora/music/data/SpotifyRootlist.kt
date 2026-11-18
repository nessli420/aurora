package com.aurora.music.data

import com.aurora.music.data.remote.array
import com.aurora.music.data.remote.obj
import com.aurora.music.data.remote.string
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

// the account's rootlist: what sits between spotify:start-group:<id>:<name> and spotify:end-group:<id> is in that folder
internal object SpotifyRootlist {
    private const val PLAYLIST = "spotify:playlist:"
    private val random = SecureRandom()

    fun parse(rootlist: JsonObject): PlaylistFolder {
        class Open(val id: String, val name: String) { val entries = ArrayList<PlaylistFolderEntry>() }
        val open = ArrayDeque<Open>().apply { addLast(Open("", "")) }
        val seen = HashSet<String>()
        fun close() {
            val done = open.removeLast()
            open.last().entries += PlaylistFolderEntry.Folder(PlaylistFolder(done.id, done.name, done.entries))
        }
        for (item in rootlist.obj("contents").array("items")) {
            val uri = item.takeIf { it.isJsonObject }?.asJsonObject?.string("uri").orEmpty()
            val parts = uri.split(':', limit = 4)
            when {
                uri.startsWith(PLAYLIST) -> open.last().entries += PlaylistFolderEntry.Item(uri.removePrefix(PLAYLIST))
                parts.size >= 3 && parts[1] == "start-group" -> if (seen.add(parts[2])) open.addLast(Open(parts[2], parts.getOrNull(3)?.let(::decode).orEmpty()))
                parts.size == 3 && parts[1] == "end-group" -> if (open.size > 1 && open.last().id == parts[2]) close()
            }
        }
        while (open.size > 1) close()
        return PlaylistFolder("", "", open.last().entries)
    }

    fun start(id: String, name: String? = null): String =
        "spotify:start-group:$id" + (name?.let { ":" + URLEncoder.encode(it.trim().replace(Regex("\\s+"), " "), "UTF-8") } ?: "")

    fun end(id: String): String = "spotify:end-group:$id"

    // 8 random bytes in hex, as the web player names a new folder
    fun newId(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun decode(name: String): String = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
}
